package com.arb.strategy.sequencer;

import com.arb.common.aeron.AeronPublisher;
import com.arb.common.aeron.AeronSubscriber;
import com.arb.sbe.*;
import com.arb.strategy.OrderSink;
import com.arb.strategy.Strategy;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Single-threaded deterministic event loop — the heart of the strategy engine.
 *
 * <p>Polls one mandatory subscriber (MARKET_DATA_STREAM) plus any number of extra
 * subscribers added via {@link #addSubscriber}. Typically a second subscriber for
 * FV_STREAM and a third for CONTROL_STREAM are added by {@code StrategyMain}.
 */
public final class ArbSequencer implements AutoCloseable {

    // ── Aeron I/O ────────────────────────────────────────────────────────────
    private final AeronSubscriber            subscriber;
    private final List<AeronSubscriber>      extraSubscribers = new ArrayList<>();
    private final AeronPublisher             publisher;
    private final Strategy                   strategy;
    private       Consumer<String>           commandHandler   = null;

    // ── Pre-allocated SBE flyweights (zero-GC) ───────────────────────────────
    private final MessageHeaderDecoder       headerDecoder  = new MessageHeaderDecoder();
    private final MarketDataTickDecoder      tickDecoder    = new MarketDataTickDecoder();
    private final QuoteTickDecoder           quoteDecoder   = new QuoteTickDecoder();
    private final MarketVolumeTickDecoder    volDecoder     = new MarketVolumeTickDecoder();
    private final ReferenceDataRecordDecoder refDataDecoder = new ReferenceDataRecordDecoder();
    private final FvUpdateDecoder            fvDecoder      = new FvUpdateDecoder();
    private final SystemEventDecoder         sysEvtDecoder  = new SystemEventDecoder();
    private final MessageHeaderEncoder       headerEncoder  = new MessageHeaderEncoder();
    private final OrderRequestEncoder        orderEncoder   = new OrderRequestEncoder();
    private final UnsafeBuffer               txBuffer       =
        new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    private final byte[]                     ctrlMsgBuf     = new byte[128];

    private volatile boolean running = false;
    private long nextOrderId  = 0L;
    private long nextBasketId = 1L;
    /** Monotonic sequence number stamped on every OrderRequest published to ORDER_STREAM. */
    private long orderSeqNo   = 0L;

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
                final long seqNo   = ++orderSeqNo;   // monotonically increasing; 1-based
                final int msgLen = (int) orderEncoder.wrapAndApplyHeader(txBuffer, 0, headerEncoder)
                    .symbol(symbol)
                    .side(side)
                    .price(price)
                    .qty(qty)
                    .orderType(orderType)
                    .orderId(orderId)
                    .basketId(basketId)
                    .legIndex((short) legIndex)
                    .seqNo(seqNo)
                    .encodedLength() + MessageHeaderEncoder.ENCODED_LENGTH;
                // Hot thread — no logging here; [ARB BASKET] in each strategy summarises the submission
                publisher.publish(txBuffer, 0, msgLen);
            }
        };
    }

    /** Add an extra Aeron subscriber to be polled in the main loop (e.g. FV_STREAM, CONTROL_STREAM). */
    public void addSubscriber(final AeronSubscriber sub) {
        extraSubscribers.add(sub);
    }

    /**
     * Register a handler for {@code SystemEvent} SBE messages arriving on any subscriber.
     * Used to handle {@code START_STRATEGY:Name} / {@code STOP_STRATEGY:Name} commands
     * forwarded from the dashboard via CONTROL_STREAM.
     */
    public void setCommandHandler(final Consumer<String> handler) {
        this.commandHandler = handler;
    }

    /** Returns a new unique basket ID for grouping a multi-leg trade. */
    public long allocateBasketId() {
        return nextBasketId++;
    }

    public void start() {
        running = true;
        while (running) {
            subscriber.poll(this::onFragment);
            for (int i = 0; i < extraSubscribers.size(); i++) {
                extraSubscribers.get(i).poll(this::onFragment);
            }
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
        final int templateId  = headerDecoder.templateId();
        final int msgOffset   = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        final int blockLength = headerDecoder.blockLength();
        final int version     = headerDecoder.version();

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
            case SystemEventDecoder.TEMPLATE_ID:
                // Control commands from dashboard — warm path, allocation acceptable
                if (commandHandler != null) {
                    sysEvtDecoder.wrap(buffer, msgOffset, blockLength, version);
                    sysEvtDecoder.getMessage(ctrlMsgBuf, 0);
                    int end = ctrlMsgBuf.length;
                    while (end > 0 && (ctrlMsgBuf[end - 1] == 0 || ctrlMsgBuf[end - 1] == ' ')) end--;
                    commandHandler.accept(new String(ctrlMsgBuf, 0, end, StandardCharsets.US_ASCII));
                }
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
