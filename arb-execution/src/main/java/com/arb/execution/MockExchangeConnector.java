package com.arb.execution;

import com.arb.common.aeron.AeronPublisher;
import com.arb.sbe.*;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates an exchange connector on the hot path.
 *
 * <p>For each accepted order:
 * <ol>
 *   <li>Busy-spins for a configurable latency window (default 10–50 μs) to simulate
 *       network + matching-engine round-trip.</li>
 *   <li>Publishes a {@code FILLED OrderUpdate} to {@code ORDER_UPDATE_STREAM} (1003).</li>
 * </ol>
 *
 * <p>All encoder instances are pre-allocated (zero-GC on the hot path).
 *
 * <h3>Testing</h3>
 * Inject {@code minLatencyNs=0, maxLatencyNs=0} to skip busy-spin in unit tests.
 */
public class MockExchangeConnector {

    private static final int BUFFER_SIZE = 256;

    private final AeronPublisher          publisher;
    private final long                    minLatencyNs;
    private final long                    maxLatencyNs;
    private final UnsafeBuffer            txBuffer;
    private final MessageHeaderEncoder    headerEncoder;
    private final OrderUpdateEncoder      updateEncoder;

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
                     final long fillQty) {
        simulateLatency();
        publishUpdate(orderId, symbol, side, fillPrice, fillQty,
                      OrderStatus.FILLED, (short) 0);
    }

    /**
     * Publish a rejection notification (no latency simulation — risk reject is immediate).
     *
     * @param rejectCode 1=fat_finger_qty, 2=fat_finger_price, 3=position_limit
     */
    public void reject(final long orderId,
                       final String symbol,
                       final Side side,
                       final short rejectCode) {
        publishUpdate(orderId, symbol, side, 0L, 0L, OrderStatus.REJECTED, rejectCode);
    }

    private void simulateLatency() {
        if (maxLatencyNs <= 0) return;
        final long range    = maxLatencyNs - minLatencyNs;
        final long jitter   = range > 0 ? ThreadLocalRandom.current().nextLong(range) : 0L;
        final long deadline = System.nanoTime() + minLatencyNs + jitter;
        //noinspection StatementWithEmptyBody
        while (System.nanoTime() < deadline) { /* busy-spin: simulates exchange round-trip */ }
    }

    private void publishUpdate(final long orderId,
                                final String symbol,
                                final Side side,
                                final long fillPrice,
                                final long fillQty,
                                final OrderStatus status,
                                final short rejectCode) {
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
            .encodedLength() + MessageHeaderEncoder.ENCODED_LENGTH;

        publisher.publish(txBuffer, 0, msgLen);
    }
}
