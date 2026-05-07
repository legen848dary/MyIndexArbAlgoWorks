package com.arb.strategy.sequencer;

import com.arb.common.Channels;
import com.arb.common.aeron.AeronPublisher;
import com.arb.common.aeron.AeronSubscriber;
import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * Single-threaded deterministic event loop — the heart of the strategy engine.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li>Reads from {@code MARKET_DATA_CHANNEL} (stream {@link Channels#MARKET_DATA_STREAM}).</li>
 *   <li>Decodes each SBE frame using pre-allocated flyweight decoders (zero heap allocation).</li>
 *   <li>Dispatches to the registered {@link Strategy}.</li>
 *   <li>Strategy signals orders via {@link OrderSink}; the sequencer encodes them as
 *       {@code OrderRequest} SBE messages and publishes to {@code ORDER_CHANNEL}.</li>
 * </ul>
 *
 * <p>All encoder/decoder instances are fields (never re-allocated). The {@link OrderSink}
 * implementation is a pre-allocated lambda field — no closure allocation on each message.
 */
public final class ArbSequencer implements AutoCloseable {

    private static final int ORDER_MSG_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + OrderRequestEncoder.BLOCK_LENGTH;

    // ── Aeron I/O ────────────────────────────────────────────────────────────
    private final AeronSubscriber subscriber;
    private final AeronPublisher  publisher;
    private final Strategy        strategy;

    // ── Pre-allocated SBE flyweights (zero-GC) ───────────────────────────────
    private final MessageHeaderDecoder       headerDecoder  = new MessageHeaderDecoder();
    private final MarketDataTickDecoder      tickDecoder    = new MarketDataTickDecoder();
    private final QuoteTickDecoder           quoteDecoder   = new QuoteTickDecoder();
    private final MarketVolumeTickDecoder    volDecoder     = new MarketVolumeTickDecoder();
    private final ReferenceDataRecordDecoder refDataDecoder = new ReferenceDataRecordDecoder();
    private final FvUpdateDecoder            fvDecoder      = new FvUpdateDecoder();
    private final MessageHeaderEncoder       headerEncoder  = new MessageHeaderEncoder();
    private final OrderRequestEncoder        orderEncoder   = new OrderRequestEncoder();
    private final UnsafeBuffer               txBuffer       =
        new UnsafeBuffer(ByteBuffer.allocateDirect(256));

    private volatile boolean running = false;

    /** Monotonically increasing order ID for correlation with execution updates. */
    private long nextOrderId = 0L;

    /** Pre-allocated OrderSink — encodes OrderRequest and publishes to ORDER_CHANNEL. */
    private final OrderSink orderSink;

    public ArbSequencer(
        final AeronSubscriber subscriber,
        final AeronPublisher  publisher,
        final Strategy        strategy)
    {
        this.subscriber = subscriber;
        this.publisher  = publisher;
        this.strategy   = strategy;
        // Initialised after publisher is set so the lambda captures an initialised final field
        this.orderSink = (symbol, side, price, qty, orderType) ->
            publisher.publish(
                txBuffer, 0,
                (int) orderEncoder.wrapAndApplyHeader(txBuffer, 0, headerEncoder)
                    .symbol(symbol)
                    .side(side)
                    .price(price)
                    .qty(qty)
                    .orderType(orderType)
                    .orderId(nextOrderId++)
                    .encodedLength() + MessageHeaderEncoder.ENCODED_LENGTH
            );
    }

    /**
     * Start the event loop on the calling thread. Blocks until {@link #stop()} is called.
     */
    public void start() {
        running = true;
        while (running) {
            subscriber.poll(this::onFragment);
        }
    }

    /** Signal the event loop to exit cleanly. */
    public void stop() {
        running = false;
    }

    /**
     * Fragment handler — called by Aeron per decoded frame.
     * All paths are zero-allocation: decoders are flyweights; orderSink is a pre-allocated field.
     * Dispatches by SBE templateId to the appropriate Strategy method.
     */
    private void onFragment(
        final DirectBuffer buffer,
        final int          offset,
        final int          length,
        final Header       header)
    {
        headerDecoder.wrap(buffer, offset);
        final int templateId   = headerDecoder.templateId();
        final int msgOffset    = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        final int blockLength  = headerDecoder.blockLength();
        final int version      = headerDecoder.version();

        switch (templateId) {
            case MarketDataTickDecoder.TEMPLATE_ID:
                tickDecoder.wrap(buffer, msgOffset, blockLength, version);
                strategy.onMarketData(tickDecoder, orderSink);
                break;
            case QuoteTickDecoder.TEMPLATE_ID:
                quoteDecoder.wrap(buffer, msgOffset, blockLength, version);
                strategy.onQuote(quoteDecoder, orderSink);
                break;
            case MarketVolumeTickDecoder.TEMPLATE_ID:
                volDecoder.wrap(buffer, msgOffset, blockLength, version);
                strategy.onMarketVolume(volDecoder, orderSink);
                break;
            case ReferenceDataRecordDecoder.TEMPLATE_ID:
                refDataDecoder.wrap(buffer, msgOffset, blockLength, version);
                strategy.onReferenceData(refDataDecoder);
                break;
            case FvUpdateDecoder.TEMPLATE_ID:
                fvDecoder.wrap(buffer, msgOffset, blockLength, version);
                strategy.onFvUpdate(fvDecoder, orderSink);
                break;
            default:
                break; // unknown templateId — skip silently
        }
    }

    @Override
    public void close() {
        subscriber.close();
        publisher.close();
    }
}
