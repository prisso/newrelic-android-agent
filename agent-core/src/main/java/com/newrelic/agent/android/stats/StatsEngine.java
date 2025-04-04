/**
 * Copyright 2022-present New Relic Corporation. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.newrelic.agent.android.stats;

import static com.newrelic.agent.android.metric.MetricNames.SESSION_START;
import static com.newrelic.agent.android.metric.MetricNames.SUPPORTABILITY_SESSION_START_COUNT_VALUE_OVERFLOW;

import com.newrelic.agent.android.Agent;
import com.newrelic.agent.android.ApplicationFramework;
import com.newrelic.agent.android.TaskQueue;
import com.newrelic.agent.android.harvest.DeviceInformation;
import com.newrelic.agent.android.harvest.HarvestAdapter;
import com.newrelic.agent.android.logging.AgentLog;
import com.newrelic.agent.android.logging.AgentLogManager;
import com.newrelic.agent.android.metric.Metric;
import com.newrelic.agent.android.metric.MetricNames;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple hash-backed Statistics engine to contain custom metrics. Initially,
 * these metrics are used by the agent itself to report supportability metrics.
 */
public class StatsEngine extends HarvestAdapter {
    public final static StatsEngine INSTANCE = new StatsEngine();
    public final static StatsEngine SUPPORTABILITY = new SupportabilityDecorator();
    final static AgentLog log = AgentLogManager.getAgentLog();

    public boolean enabled = true;

    private final ConcurrentHashMap<String, Metric> statsMap = new ConcurrentHashMap<String, Metric>();

    private StatsEngine() {
        // You should never externally call new on a singleton.
    }

    /**
     * Returns a singleton of the StatsEngine instance
     *
     * @return Global instance used by the agent.
     */
    public static StatsEngine get() {
        return INSTANCE;
    }

    /**
     * The SUPPORTABILITY instance should be used for internal supportability metrics.
     * Call this method rather than get()
     *
     * @return Teh instance of a STatsEngine that decorates metric name templates with platform
     * and version info.
     */
    public static StatsEngine notice() {
        return SUPPORTABILITY;
    }

    /**
     * Increment a metric by 1.
     *
     * @param name Name of the metric to increment by 1.
     */
    public void inc(String name) {
        Metric m = lazyGet(name);

        synchronized (m) {
            m.increment();
        }
    }

    /**
     * Increment a metric count by a positive value.
     *
     * @param name  Name of the metric to increment.
     * @param count Number to increment this metric by. Negative increments are ignored.
     */
    public void inc(String name, long count) {
        Metric m = lazyGet(name);

        synchronized (m) {
            m.increment(count);
        }
    }

    /**
     * Record a sample. This will also increment callCount by 1.
     *
     * @param name  Name of the metric.
     * @param value The sampled value.
     */
    public void sample(String name, float value) {
        Metric m = lazyGet(name);

        synchronized (m) {
            m.sample(value);
        }
    }

    /**
     * Record a sample data usage metric. This will also increment callCount by 1.
     *
     * @param name          Name of the metric.
     * @param bytesSent     The sampled value.
     * @param bytesReceived The sampled value.
     */
    public void sampleMetricDataUsage(String name, float bytesSent, float bytesReceived) {
        Metric m = lazyGet(name);

        synchronized (m) {
            m.sampleMetricDataUsage(bytesSent, bytesReceived);
        }
    }

    /**
     * Record a time in milliseconds. This will also increment callCount by 1.
     *
     * @param name Name of the metric.
     * @param time Time this call took in milliseconds. Negative time values are ignored.
     */
    public void sampleTimeMs(String name, long time) {
        if (time >= 0f && name != null) {
            sample(name, (float) time / 1000f);
        } else {
            log.error("StatsEngine.sampleTimeMs() called with negative time value[" + time + "] or missing metric name.");
        }
    }

    /**
     * Add metrics to the Harvester
     */
    public static void populateMetrics() {
        for (ConcurrentHashMap.Entry<String, Metric> entry : INSTANCE.getStatsMap().entrySet()) {
            Metric metric = entry.getValue();
            sanitizeSessionStartMetric(metric);
            TaskQueue.queue(metric);
        }

        for (ConcurrentHashMap.Entry<String, Metric> entry : SUPPORTABILITY.getStatsMap().entrySet()) {
            Metric metric = entry.getValue();
            TaskQueue.queue(metric);
        }
    }

    private static void sanitizeSessionStartMetric(Metric metric) {
        //sanitize the metrics values for Session/Start
        if (metric.getCount() > 1 && (metric.getName().contains(SESSION_START))) {
            StatsEngine.SUPPORTABILITY.inc(SUPPORTABILITY_SESSION_START_COUNT_VALUE_OVERFLOW);
            metric.setTotal(1.0);
            metric.setCount(1);
            metric.setMin(1.0);
            metric.setMax(1.0);
            metric.setSumOfSquares(1.0);
        }
    }

    /**
     * Calculate total of metrics data usage
     */
    public static void calculateMetricsDataUseage() {
        DeviceInformation deviceInformation = Agent.getDeviceInformation();
        String dataUsageName = MetricNames.SUPPORTABILITY_DESTINATION_OUTPUT_BYTES
                .replace(MetricNames.TAG_FRAMEWORK, deviceInformation.getApplicationFramework().name())
                .replace(MetricNames.TAG_DESTINATION, MetricNames.METRIC_DATA_USAGE_COLLECTOR);

        String COLLECTOR_CONNECT_URI = MetricNames.METRIC_DATA_USAGE_COLLECTOR + "/connect";
        String COLLECTOR_DATA_URI = MetricNames.METRIC_DATA_USAGE_COLLECTOR + "/data";
        String COLLECTOR_MOBILE_F = MetricNames.METRIC_DATA_USAGE_COLLECTOR + "/f";
        String COLLECTOR_MOBILE_CRASH = MetricNames.METRIC_DATA_USAGE_COLLECTOR + "/mobile_crash";

        long totalInteractionCount = 0;
        float totalBytesSent = 0;
        float totalBytesReceived = 0;
        for (ConcurrentHashMap.Entry<String, Metric> entry : INSTANCE.getStatsMap().entrySet()) {
            Metric metric = entry.getValue();
            String name = metric.getName();
            if (name.contains(COLLECTOR_CONNECT_URI)
                    || name.contains(COLLECTOR_DATA_URI)
                    || name.contains(COLLECTOR_MOBILE_F)
                    || name.contains(COLLECTOR_MOBILE_CRASH)) {
                totalInteractionCount += metric.getCount();
                totalBytesSent += metric.getTotal();
                totalBytesReceived += metric.getExclusive();
            }
        }

        for (ConcurrentHashMap.Entry<String, Metric> entry : SUPPORTABILITY.getStatsMap().entrySet()) {
            Metric metric = entry.getValue();
            String name = metric.getName();
            if (name.contains(COLLECTOR_CONNECT_URI)
                    || name.contains(COLLECTOR_DATA_URI)
                    || name.contains(COLLECTOR_MOBILE_F)
                    || name.contains(COLLECTOR_MOBILE_CRASH)) {
                totalInteractionCount += metric.getCount();
                totalBytesSent += metric.getTotal();
                totalBytesReceived += metric.getExclusive();
            }
        }

        //totalInteractionCount - 1 because we call it twice which will be increment again
        StatsEngine.get().inc(dataUsageName, totalInteractionCount - 1);
        StatsEngine.get().sampleMetricDataUsage(dataUsageName, totalBytesSent, totalBytesReceived);
    }

    @Override
    public void onHarvest() {
        calculateMetricsDataUseage();
        populateMetrics();
    }

    @Override
    public void onHarvestComplete() {
        reset();
    }

    /**
     * Reset the Stats Engine.  This is usually called after a successful harvest cycle.
     */
    public static void reset() {
        INSTANCE.getStatsMap().clear();
        SUPPORTABILITY.getStatsMap().clear();
    }

    /**
     * Disable the Stats Engine (enabled by default). This is mostly useful in
     * test scenarios.
     */
    public synchronized static void disable() {
        INSTANCE.enabled = false;
        SUPPORTABILITY.enabled = false;
    }

    /**
     * Enable the Stats Engine (enabled by default).
     */
    public synchronized static void enable() {
        INSTANCE.enabled = true;
        SUPPORTABILITY.enabled = true;
    }

    /**
     * Return stats collection.
     *
     * @return ConcurrentHashMap<String, Metric>
     */
    public ConcurrentHashMap<String, Metric> getStatsMap() {
        return statsMap;
    }

    protected Metric lazyGet(String name) {
        Metric m = statsMap.get(name);

        if (m == null) {
            m = new Metric(name);

            if (enabled) {
                statsMap.put(name, m);
            }
        }

        return m;
    }

    static class SupportabilityDecorator extends StatsEngine {

        String emptyIfNull(String s) {
            return s == null ? "" : s;
        }

        @Override
        protected Metric lazyGet(String name) {
            DeviceInformation deviceInformation = Agent.getDeviceInformation();
            String framework = emptyIfNull(null);
            String frameworkVersion = emptyIfNull(null);
            String f = emptyIfNull(deviceInformation.getApplicationFrameworkVersion());
            String a = emptyIfNull(deviceInformation.getAgentVersion());

            if (deviceInformation.getApplicationFramework() != null) {
                if (Objects.requireNonNull(deviceInformation.getApplicationFramework()) == ApplicationFramework.Native) {
                    if (!(f.isEmpty() || f.equals(a))) {
                        framework = emptyIfNull(deviceInformation.getApplicationFramework().name());
                        frameworkVersion = emptyIfNull(deviceInformation.getApplicationFrameworkVersion());
                    }
                } else {
                    framework = emptyIfNull(deviceInformation.getApplicationFramework().name());
                    if (!f.equals(a)) {
                        frameworkVersion = emptyIfNull(deviceInformation.getApplicationFrameworkVersion());
                    }
                }
            }

            name = name.replaceAll(MetricNames.TAG_FRAMEWORK, emptyIfNull(framework))
                    .replaceAll(MetricNames.TAG_FRAMEWORK_VERSION, emptyIfNull(frameworkVersion));

            // compress empty namespaces
            while (name.contains("//")) {
                name = name.replaceAll("//", "/");
            }

            return super.lazyGet(name);
        }
    }

}