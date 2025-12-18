package com.amazonaws.lambda.durable.retry;

/**
 * Jitter strategy for retry delays to prevent thundering herd problems.
 * 
 * Jitter reduces simultaneous retry attempts by spreading retries out over
 * a randomized delay interval, which helps prevent overwhelming services
 * when many clients retry at the same time.
 */
public enum JitterStrategy {

    /**
     * No jitter - use exact calculated delay.
     * This provides predictable timing but may cause thundering herd issues.
     */
    NONE,

    /**
     * Full jitter - random delay between 0 and calculated delay.
     * This provides maximum spread but may result in very short delays.
     */
    FULL,

    /**
     * Half jitter - random delay between 50% and 100% of calculated delay.
     * This provides good spread while maintaining reasonable minimum delays.
     */
    HALF
}
