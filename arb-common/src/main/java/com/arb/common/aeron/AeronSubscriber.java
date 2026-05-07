package com.arb.common.aeron;

import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;

/**
 * Zero-allocation wrapper around an Aeron {@link Subscription}.
 * Drives the poll loop — callers supply a {@link FragmentHandler} to process messages.
 */
public class AeronSubscriber implements AutoCloseable {

    private static final int DEFAULT_FRAGMENT_LIMIT = 10;

    private final Subscription subscription;
    private final int fragmentLimit;

    public AeronSubscriber(final Subscription subscription) {
        this(subscription, DEFAULT_FRAGMENT_LIMIT);
    }

    public AeronSubscriber(final Subscription subscription, final int fragmentLimit) {
        this.subscription = subscription;
        this.fragmentLimit = fragmentLimit;
    }

    /**
     * Polls for available messages, dispatching each to {@code handler}.
     *
     * @return number of fragments received during this poll
     */
    public int poll(final FragmentHandler handler) {
        return subscription.poll(handler, fragmentLimit);
    }

    public boolean isConnected() {
        return subscription.isConnected();
    }

    @Override
    public void close() {
        subscription.close();
    }
}
