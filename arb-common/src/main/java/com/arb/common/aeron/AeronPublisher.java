package com.arb.common.aeron;

import io.aeron.Publication;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Zero-allocation wrapper around an Aeron {@link Publication}.
 * Retries automatically on BACK_PRESSURED and ADMIN_ACTION — never drops messages.
 */
public class AeronPublisher implements AutoCloseable {

    private static final int MAX_SPIN_RETRIES = 100_000;

    private final Publication publication;

    public AeronPublisher(final Publication publication) {
        this.publication = publication;
    }

    /**
     * Offers a message to the publication, spinning on back-pressure.
     *
     * @return positive position on success, negative error code on terminal failure
     */
    public long publish(final UnsafeBuffer buffer, final int offset, final int length) {
        long result;
        int retries = 0;
        do {
            result = publication.offer(buffer, offset, length);
            if (result == Publication.NOT_CONNECTED ||
                result == Publication.CLOSED ||
                result == Publication.MAX_POSITION_EXCEEDED) {
                return result;
            }
            if (++retries > MAX_SPIN_RETRIES) {
                return result;
            }
        } while (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION);
        return result;
    }

    public boolean isConnected() {
        return publication.isConnected();
    }

    public int sessionId() {
        return publication.sessionId();
    }

    @Override
    public void close() {
        publication.close();
    }
}
