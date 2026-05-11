package com.arb.execution;

import com.arb.common.aeron.AeronPublisher;
import com.arb.sbe.*;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.*;

/**
 * Simulates an exchange connector with async delayed fills.
 *
 * <p>For each accepted order:
 * <ol>
 *   <li>Schedules a fill to arrive 5–10 seconds later (simulates network + matching-engine round-trip).</li>
 *   <li>Publishes a {@code FILLED OrderUpdate} to {@code ORDER_UPDATE_STREAM} (1003) via {@link #drainPending()}.</li>
 * </ol>
 *
 * <p>All encoder instances are pre-allocated (zero-GC on the hot path).
 *
 * <h3>Testing</h3>
 * Inject {@code minLatencyNs=0, maxLatencyNs=0} (unused in async mode) or subclass for unit tests.
 */
public class MockExchangeConnector {

    private static final int BUFFER_SIZE = 256;

    private final AeronPublisher          publisher;
    private final long                    minLatencyNs;
    private final long                    maxLatencyNs;
    private final UnsafeBuffer            txBuffer;
    private final MessageHeaderEncoder    headerEncoder;
    private final OrderUpdateEncoder      updateEncoder;
    private final ConcurrentLinkedQueue<Runnable> pendingFills = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService fillTimer = Executors.newSingleThreadScheduledExecutor(
        r -> Thread.ofPlatform().name("fill-timer").daemon(true).unstarted(r));

    public MockExchangeConnector(final AeronPublisher publisher,
                                 final long minLatencyNs,
                                 final long maxLatencyNs) {
        this.publisher      = publisher;
        this.minLatencyNs   = minLatencyNs;
        this.maxLatencyNs   = maxLatencyNs;
        this.txBuffer       = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE));
        this.headerEncoder  = new MessageHeaderEncoder();
        this.updateEncoder  = new OrderUpdateEncoder();
    }

    /**
     * Simulate a round-trip to the exchange and publish a fill.
     *
     * @param orderId   correlation ID from the OrderRequest
     * @param symbol    instrument symbol
     * @param side      BUY or SELL
     * @param fillPrice fill price (fixed-point 10^4)
     * @param fillQty   fill quantity in lots
     */
    public void fill(final long orderId,
                     final String symbol,
                     final Side side,
                     final long fillPrice,
                     final long fillQty,
                     final long basketId,
                     final short legIndex) {
        final long delayMs = 5_000L + ThreadLocalRandom.current().nextLong(5_001L);
        System.out.printf("[FILL-SCHEDULED] orderId=%d basketId=%d leg=%d %s %s qty=%d — fills in %dms%n",
            orderId, basketId, legIndex, symbol, side.name(), fillQty, delayMs);
        fillTimer.schedule(() -> pendingFills.offer(() -> {
            System.out.printf("[FILL] orderId=%d basketId=%d leg=%d %s %s qty=%d @%d%n",
                orderId, basketId, legIndex, symbol, side.name(), fillQty, fillPrice);
            publishUpdate(orderId, symbol, side, fillPrice, fillQty, OrderStatus.FILLED, (short) 0, basketId, legIndex);
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    /** Drain pending fills onto the calling thread. Call from the execution poll loop. */
    public void drainPending() {
        Runnable task;
        while ((task = pendingFills.poll()) != null) task.run();
    }

    public void close() {
        fillTimer.shutdownNow();
    }

    /**
     * Publish a rejection notification (no delay — risk reject is immediate).
     *
     * @param rejectCode 1=fat_finger_qty, 2=fat_finger_price, 3=position_limit
     */
    public void reject(final long orderId,
                       final String symbol,
                       final Side side,
                       final short rejectCode,
                       final long basketId,
                       final short legIndex) {
        System.out.printf("[REJECT] orderId=%d basketId=%d leg=%d %s code=%d%n",
            orderId, basketId, legIndex, symbol, rejectCode);
        publishUpdate(orderId, symbol, side, 0L, 0L, OrderStatus.REJECTED, rejectCode, basketId, legIndex);
    }

    private void publishUpdate(final long orderId,
                                final String symbol,
                                final Side side,
                                final long fillPrice,
                                final long fillQty,
                                final OrderStatus status,
                                final short rejectCode,
                                final long basketId,
                                final short legIndex) {
        if (publisher == null) return; // test subclass with null publisher
        final int msgLen = (int) updateEncoder.wrapAndApplyHeader(txBuffer, 0, headerEncoder)
            .orderId(orderId)
            .symbol(symbol)
            .side(side)
            .fillPrice(fillPrice)
            .fillQty(fillQty)
            .status(status)
            .rejectCode(rejectCode)
            .timestamp(System.nanoTime())
            .basketId(basketId)
            .legIndex(legIndex)
            .encodedLength() + MessageHeaderEncoder.ENCODED_LENGTH;

        publisher.publish(txBuffer, 0, msgLen);
    }
}
