package com.finsafe.idempotencygateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    /**
     * Simulated payment processing delay in milliseconds.
     */
    private long processingDelayMs = 2000;

    /**
     * How often expired keys are purged from the in-memory store.
     */
    private long purgeIntervalMs = 3_600_000;

    private final Key key = new Key();

    public long getProcessingDelayMs() {
        return processingDelayMs;
    }

    public void setProcessingDelayMs(long processingDelayMs) {
        this.processingDelayMs = processingDelayMs;
    }

    public long getPurgeIntervalMs() {
        return purgeIntervalMs;
    }

    public void setPurgeIntervalMs(long purgeIntervalMs) {
        this.purgeIntervalMs = purgeIntervalMs;
    }

    public Key getKey() {
        return key;
    }

    public static class Key {
        /**
         * Time-to-live for stored idempotency keys, in hours.
         */
        private long ttlHours = 24;

        public long getTtlHours() {
            return ttlHours;
        }

        public void setTtlHours(long ttlHours) {
            this.ttlHours = ttlHours;
        }
    }
}
