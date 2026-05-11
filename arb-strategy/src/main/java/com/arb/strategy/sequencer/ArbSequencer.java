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
 */
public final class ArbSequencer implements AutoCloseable {

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
    private long nextOrderId  = 0L;
    private long nextBasketId = 1L; // basket IDs start at 1 (0 = standalone)

    private final OrderSink orderSink;

    public ArbSequencer(
        final AeronSubscriber subscriber,
        final AeronPublisher  publisher,
        final Strategy        strategy)
    {
        this.subscriber = subscriber;
        this.publisher  = publisher;
        this.strategy   = strategy;
        this.orderSink  = new OrderSink() {
            @Override
            public void send(final String symbol, final Side side, final long price, final long qty, final OrderType orderType) {
                sendLeg(0L, 0, symbol, side, price, qty, orderType);
            }

            @Override
            public void sendLeg(final long basketId, final int legIndex, final String symbol, final Side side, final long price, final long qty, final OrderType orderType) {
                final long orderId = nextOrderId++;
                final int msgLen = (int) orderEncoder.wrapAndApplyHeader(txBuffer, 0, headerEncoder)
                    .symbol(symbol)
                    .side(side)
                    .price(price)
                    .qty(qty)
                    .orderType(orderType)
                    .orderId(orderId)
                    .basketId(basketId)
                    .legIndex((short) legIndex)
                    .encodedLength() + MessageHeaderEncoder.ENCODED_LENGTH;
                publisher.publish(txBuffer, 0, msgLen);
                System.out.printf("[SEQ] orderId=%d basketId=%d leg=%d %s %s qty=%d price=%d%n",
                    orderId, basketId, legIndex, symbol, side.name(), qty, price);
            }
        };
    }

    /** Returns a new unique basket ID for grouping a multi-leg trade. */
    public long allocateBasketId() {
        return nextBasketId++;
    }

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
                break;
        }
    }

    @Override
    public void close() {
        subscriber.close();
        publisher.close();
    }
}
