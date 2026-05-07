package com.arb.gambit.realtime;

import com.arb.common.Channels;
import com.arb.common.aeron.AeronPublisher;
import com.arb.common.aeron.AeronSubscriber;
import com.arb.sbe.*;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fair-value engine — the hot-path bridge between market data and strategy signals.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li>Subscribes to {@code MARKET_DATA_CHANNEL} (stream 1001).</li>
 *   <li>On each {@code MarketDataTick} (templateId=1): computes FV and publishes
 *       {@code FvUpdate} (templateId=7) to {@code FV_CHANNEL} (stream 1005).</li>
 *   <li>Reads {@code dividendPv} via {@link AtomicLong#getAcquire()} — lock-free,
 *       cheaper than a full {@code volatile} read (LoadLoad fence only).</li>
 *   <li>Zero heap allocation on the poll/publish path — all SBE flyweights pre-allocated.</li>
 * </ul>
 *
 * <h3>Warm-path bridge</h3>
 * The {@link com.arb.gambit.model.DividendCalendar} runs periodically on a separate thread
 * and writes a freshly-computed dividend PV via {@link AtomicLong#setRelease}.
 * This engine reads it via {@link AtomicLong#getAcquire} — single-writer, single-reader,
 * acquire-release ordering, no full StoreLoad barrier required.
 */
public final class FvEngine implements AutoCloseable {

    private static final int FV_MSG_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + FvUpdateEncoder.BLOCK_LENGTH;

    // Placeholder parameters — Phase 3 will wire these from config / ReferenceDataStore
    private static final int  DEFAULT_RISK_FREE_RATE_BPS = 250;  // 2.50% HIBOR
    private static final int  DEFAULT_DAYS_TO_EXPIRY     = 30;

    private final AeronSubscriber subscriber;
    private final AeronPublisher  fvPublisher;
    private final AtomicLong      dividendPv;   // written by warm path via setRelease()

    // ── Pre-allocated SBE flyweights (zero-GC) ───────────────────────────────
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final MarketDataTickDecoder tickDecoder  = new MarketDataTickDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final FvUpdateEncoder      fvEncoder     = new FvUpdateEncoder();
    private final UnsafeBuffer         txBuffer      =
        new UnsafeBuffer(ByteBuffer.allocateDirect(256));

    // Pre-allocated byte array for zero-GC symbol copy
    private final byte[] symbolBytes = new byte[MarketDataTickDecoder.symbolLength()];

    private volatile boolean running = false;

    public FvEngine(
        final AeronSubscriber subscriber,
        final AeronPublisher  fvPublisher,
        final AtomicLong      dividendPv)
    {
        this.subscriber  = subscriber;
        this.fvPublisher = fvPublisher;
        this.dividendPv  = dividendPv;
    }

    /** Start the event loop on the calling thread. Blocks until {@link #stop()} is called. */
    public void start() {
        running = true;
        while (running) {
            subscriber.poll(this::onFragment);
        }
    }

    public void stop() {
        running = false;
    }

    private void onFragment(
        final DirectBuffer buffer,
        final int          offset,
        final int          length,
        final Header       header)
    {
        headerDecoder.wrap(buffer, offset);
        if (headerDecoder.templateId() != MarketDataTickDecoder.TEMPLATE_ID) return;

        tickDecoder.wrap(
            buffer,
            offset + MessageHeaderDecoder.ENCODED_LENGTH,
            headerDecoder.blockLength(),
            headerDecoder.version()
        );

        final long spotIndex = tickDecoder.price();
        // Acquire-release read — no full StoreLoad fence, sufficient for single-writer pattern
        final long pv        = dividendPv.getAcquire();

        final long fv        = FuturesFvCalculator.computeFv(
            spotIndex, DEFAULT_RISK_FREE_RATE_BPS, DEFAULT_DAYS_TO_EXPIRY, pv);
        final long basis     = spotIndex - fv;
        final long basisBps  = FuturesFvCalculator.annualisedBasisBps(
            spotIndex, fv, spotIndex, DEFAULT_DAYS_TO_EXPIRY);

        // Zero-GC symbol copy into pre-allocated byte array
        tickDecoder.getSymbol(symbolBytes, 0);

        fvEncoder.wrapAndApplyHeader(txBuffer, 0, headerEncoder)
            .putSymbol(symbolBytes, 0)
            .exchange(tickDecoder.exchange())
            .navPerUnit(0L)
            .futuresFv(fv)
            .basis(basis)
            .annualisedBasisBps(basisBps)
            .timestamp(tickDecoder.timestamp());

        fvPublisher.publish(txBuffer, 0, FV_MSG_LENGTH);
    }

    @Override
    public void close() {
        subscriber.close();
        fvPublisher.close();
    }
}
