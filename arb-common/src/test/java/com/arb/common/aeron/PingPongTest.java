package com.arb.common.aeron;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 0 acceptance test: verifies Aeron IPC backbone.
 * Sends 10,000 messages over an embedded MediaDriver and asserts all are received.
 */
class PingPongTest {

    private static final String IPC_CHANNEL   = "aeron:ipc";
    private static final int    STREAM_ID      = 1;
    private static final int    MESSAGE_COUNT  = 10_000;
    private static final String PAYLOAD        = "ping";

    private MediaDriver driver;
    private Aeron       aeron;

    @BeforeEach
    void setUp() {
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);
        driver = MediaDriver.launchEmbedded(driverCtx);

        final Aeron.Context aeronCtx = new Aeron.Context()
            .aeronDirectoryName(driver.aeronDirectoryName());
        aeron = Aeron.connect(aeronCtx);
    }

    @AfterEach
    void tearDown() {
        aeron.close();
        driver.close();
    }

    @Test
    void shouldSendAndReceive10kMessagesOverIpc() {
        final AtomicInteger received = new AtomicInteger(0);
        final UnsafeBuffer  buffer   = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        final int           length   = buffer.putStringWithoutLengthAscii(0, PAYLOAD);

        try (Publication pub = aeron.addPublication(IPC_CHANNEL, STREAM_ID);
             Subscription sub = aeron.addSubscription(IPC_CHANNEL, STREAM_ID)) {

            final AeronPublisher  publisher  = new AeronPublisher(pub);
            final AeronSubscriber subscriber = new AeronSubscriber(sub);
            final FragmentHandler handler    = (buf, offset, len, hdr) -> received.incrementAndGet();

            // Wait for subscriber to connect before publishing
            while (!pub.isConnected()) {
                Thread.onSpinWait();
            }

            final long startNs = System.nanoTime();

            for (int i = 0; i < MESSAGE_COUNT; i++) {
                publisher.publish(buffer, 0, length);
            }

            while (received.get() < MESSAGE_COUNT) {
                subscriber.poll(handler);
                Thread.onSpinWait();
            }

            final long durationNs      = System.nanoTime() - startNs;
            final long avgLatencyNs    = durationNs / MESSAGE_COUNT;
            final long avgLatencyUs    = avgLatencyNs / 1_000;

            System.out.printf(
                "[PingPongTest] %,d messages | total: %,d ms | avg latency: %d ns (%d µs)%n",
                MESSAGE_COUNT, durationNs / 1_000_000, avgLatencyNs, avgLatencyUs
            );

            assertEquals(MESSAGE_COUNT, received.get(), "All messages must be received");
        }
    }
}
