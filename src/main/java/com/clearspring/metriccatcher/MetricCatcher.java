package com.clearspring.metriccatcher;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.util.LRUMap;
import org.codehaus.jackson.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.CounterMetric;
import com.yammer.metrics.core.GaugeMetric;
import com.yammer.metrics.core.HistogramMetric;
import com.yammer.metrics.core.MeterMetric;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.TimerMetric;

public class MetricCatcher extends Thread {
    private static final Logger logger = LoggerFactory.getLogger(MetricCatcher.class);
    AtomicBoolean shutdown = new AtomicBoolean();

    private ObjectMapper mapper = new ObjectMapper();
    private DatagramSocket socket;
    private Map<String, Metric> metricCache;

    public MetricCatcher(DatagramSocket socket, Map<String, Metric> metricCache) throws IOException {
        this.socket = socket;
        this.metricCache = metricCache;
    }

    /**
     * Grab metric messages off the listening socket, creating and updating
     * them as needed.
     */
    @Override
    public void run() {
        // Arbitrary. One metric with a reasonable name is less than 200b
        // This (http://stackoverflow.com/q/3712151/17339) implies that 64 bit
        // leenuks will handle packets up to 24,258b, so let's assume we won't
        // get anything larger than that.  Note that this is a hard limit-you
        // can't accumulate from the socket so anything larger is truncated.
        byte[] data = new byte[24258];

        // Keep track of the last 1000 packets we've seen
        Map<String, Boolean> recentMessages = new LRUMap<String, Boolean>(10, 1000);
        byte[] json = null;

        while (shutdown.get() == false) {
            DatagramPacket received = new DatagramPacket(data, data.length);
            try {
                // Pull in network data
                socket.receive(received);
                json = received.getData();
                String jsonMD5 = DigestUtils.md5Hex(json);
                if (logger.isDebugEnabled())
                    logger.debug("Got packet from " + received.getAddress() + ":" + received.getPort());
                if (logger.isTraceEnabled()) {
                    String jsonString = new String(json);
                    logger.trace("JSON: " + jsonString + " MD5: " + jsonMD5);
                }

                // Skip if this packet has been seen already
                if (recentMessages.containsKey(jsonMD5)) {
                    logger.info("Not processing duplicate message <" + jsonMD5 + ">");
                    continue;
                }
                recentMessages.put(jsonMD5, Boolean.TRUE);

                TypeReference<List<JSONMetric>> typeRef = new TypeReference<List<JSONMetric>>() {};
                List<JSONMetric> jsonMetrics = mapper.readValue(json, typeRef);

                // Parse all of the metrics in the message
                for (JSONMetric jsonMetric : jsonMetrics) {
                    if (!metricCache.containsKey(jsonMetric.getName())) {
                        logger.info("Creating new " + jsonMetric.getType().name() + " metric for '" + jsonMetric.getName() + "'");
                        Metric newMetric = createMetric(jsonMetric);
                        metricCache.put(jsonMetric.getName(), newMetric);
                    }

                    // Record the update
                    logger.debug("Updating '" + jsonMetric.getName() + "' with <" + jsonMetric.getValue() + ">");
                    updateMetric(metricCache.get(jsonMetric.getName()), jsonMetric.getValue());
                }
            } catch (IOException e) {
                logger.warn("IO error: " + e);
                String jsonString = new String(json);
                logger.warn("JSON: " + jsonString);
            }
        }

        socket.close();
    }

    /**
     * Create a Metric object from a JSONMetric
     *
     * @param jsonMetric A JSONMetric to make a Metric from
     * @return A Metric equivalent to the given JSONMetric
     */
    protected Metric createMetric(JSONMetric jsonMetric) {
        // Split the name from the JSON on dots for the metric group/type/name
        MetricName metricName;
        ArrayList<String> parts = new ArrayList<String>(Arrays.asList(jsonMetric.getName().split("\\.")));
        if (parts.size() >= 3)
            metricName = new MetricName(parts.remove(0), parts.remove(0), StringUtils.join(parts, "."));
        else
            metricName = new MetricName(jsonMetric.getName(), "", "");

        Class<?> metricType = jsonMetric.getMetricClass();
        if (metricType == GaugeMetric.class) {
            return Metrics.newGauge(metricName, new GaugeMetricImpl());
        } else if (metricType == CounterMetric.class) {
            return Metrics.newCounter(metricName);
        } else if (metricType == MeterMetric.class) {
            // TODO timeunit
            return Metrics.newMeter(metricName, jsonMetric.getName(), TimeUnit.MINUTES);
        } else if (metricType == HistogramMetric.class) {
            if (jsonMetric.getType().equals("biased"))
                return Metrics.newHistogram(metricName, true);
            else
                return Metrics.newHistogram(metricName, false);
        } else if (metricType == TimerMetric.class) {
            return Metrics.newTimer(metricName, TimeUnit.MICROSECONDS, TimeUnit.SECONDS);
        }

        // Uh-oh
        return null;
    }

    /**
     * Update various metric types.
     *
     * Gauge:
     *
     * Counter:
     *     Increment or decrement based upon sign of value
     *     Clear counter if given 0
     *
     * Meter:
     *     mark() the meter with the given value
     *
     * Histogram:
     *     update() the histogram with the given value
     *
     * Timer:
     *
     * @param metric The metric to update
     * @param value The value to supply when updating the metric
     */
    protected void updateMetric(Metric metric, double value) {
        if (metric.getClass() == GaugeMetric.class) {
                ((GaugeMetricImpl)metric).setValue((long)value);
        } else if (metric.getClass() == CounterMetric.class) {
            if (value > 0)
                ((CounterMetric)metric).inc((long)value);
            else if (value < 0)
                ((CounterMetric)metric).dec((long)value * -1);
            else
                ((CounterMetric)metric).clear();
        } else if (metric.getClass() == MeterMetric.class) {
            ((MeterMetric)metric).mark((long)value);
        } else if (metric.getClass() == HistogramMetric.class) {
            // TODO clearing?  How about no, so that we can record 0 values; it'll clear over time...
            ((HistogramMetric)metric).update((long)value);
        } else if (metric.getClass() == TimerMetric.class) {
            ((TimerMetric)metric).update((long)value, TimeUnit.MICROSECONDS);
        }
    }

    /**
     * Shutdown this MetricCatcher
     */
    public void shutdown() {
        logger.info("Got shutdown signal");
        shutdown.set(true);
        logger.debug("Shutdown set");
        this.interrupt();
        logger.info("Done shutting down");
    }
}