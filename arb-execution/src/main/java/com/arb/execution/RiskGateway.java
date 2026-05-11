package com.arb.execution;

import com.arb.common.aeron.AeronSubscriber;
import com.arb.sbe.*;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.collections.Object2LongHashMap;

import java.nio.charset.StandardCharsets;

/**
 * Pre-trade risk gateway. Subscribes to {@code ORDER_STREAM} (1002), applies risk checks,
 * and either forwards to {@link MockExchangeConnector} or publishes a rejection.
 *
 * <h3>Risk checks (in order)</h3>
 * <ol>
 *   <li><b>Fat-finger quantity</b>: {@code qty > maxQtyPerOrderLots} → reject code 1</li>
 *   <li><b>Fat-finger price</b>: price deviates &gt; {@code maxPriceDeviationBps100} from last
 *       known price → reject code 2 (skipped if no last price or config value is 0)</li>
 *   <li><b>Position limit</b>: |currentNet + newQty| &gt; {@code maxNetPositionLots} → reject code 3</li>
 * </ol>
 *
 * <p>All decoder/encoder instances are pre-allocated (zero-GC on hot path).
 */
public final class RiskGateway {

    private static final short REJECT_FAT_FINGER_QTY   = 1;
    private static final short REJECT_FAT_FINGER_PRICE = 2;
    private static final short REJECT_POSITION_LIMIT   = 3;

    private static final int SYM_LEN = 12;

    private final RiskConfig             config;
    private final PositionBook           positions;
    private final MockExchangeConnector  connector;

    // Pre-allocated decoders (zero-GC)
    private final MessageHeaderDecoder   headerDecoder = new MessageHeaderDecoder();
    private final OrderRequestDecoder    orderDecoder  = new OrderRequestDecoder();
    private final byte[]                 symBuf        = new byte[SYM_LEN];

    // Tracks last known prices per symbol for fat-finger price check
    private final Object2LongHashMap<String> lastPrices;

    private final com.arb.common.metrics.LatencyRecorder riskCheckLatency = new com.arb.common.metrics.LatencyRecorder();

    public RiskGateway(final RiskConfig config,
                       final PositionBook positions,
                       final MockExchangeConnector connector) {
        this.config     = config;
        this.positions  = positions;
        this.connector  = connector;
        this.lastPrices = new Object2LongHashMap<>(32, 0.65f, Long.MIN_VALUE);
    }

    /**
     * Process one Aeron fragment. Called from the subscriber's poll loop.
     */
    public void onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header) {
        headerDecoder.wrap(buffer, offset);
        if (headerDecoder.templateId() != OrderRequestDecoder.TEMPLATE_ID) return;
        final long t0 = System.nanoTime();

        orderDecoder.wrap(buffer,
            offset + MessageHeaderDecoder.ENCODED_LENGTH,
            headerDecoder.blockLength(),
            headerDecoder.version());

        final long orderId = orderDecoder.orderId();
        final Side side    = orderDecoder.side();
        final long price   = orderDecoder.price();
        final long qty     = orderDecoder.qty();
        final long basketId  = orderDecoder.basketId();
        final short legIndex = orderDecoder.legIndex();

        orderDecoder.getSymbol(symBuf, 0);
        int slen = SYM_LEN;
        while (slen > 0 && symBuf[slen - 1] == 0) slen--;
        final String symbol = new String(symBuf, 0, slen, StandardCharsets.US_ASCII);

        // Check 1: fat-finger quantity
        if (qty > config.maxQtyPerOrderLots) {
            connector.reject(orderId, symbol, side, REJECT_FAT_FINGER_QTY, basketId, legIndex);
            riskCheckLatency.record(System.nanoTime() - t0);
            return;
        }

        // Check 2: fat-finger price
        if (config.maxPriceDeviationBps100 > 0 && price > 0) {
            final long lastPrice = lastPrices.getValue(symbol);
            if (lastPrice != Long.MIN_VALUE && lastPrice > 0) {
                final long devBps100 = Math.abs(price - lastPrice) * 10_000L * 100L / lastPrice;
                if (devBps100 > config.maxPriceDeviationBps100) {
                    connector.reject(orderId, symbol, side, REJECT_FAT_FINGER_PRICE, basketId, legIndex);
                    riskCheckLatency.record(System.nanoTime() - t0);
                    return;
                }
            }
        }

        // Check 3: position limit
        final long positionDelta = side == Side.BUY ? qty : -qty;
        if (!positions.isWithinLimit(symbol, positionDelta, config.maxNetPositionLots)) {
            connector.reject(orderId, symbol, side, REJECT_POSITION_LIMIT, basketId, legIndex);
            riskCheckLatency.record(System.nanoTime() - t0);
            return;
        }

        // All checks passed — update position book and forward to exchange
        positions.applyDelta(symbol, positionDelta);
        lastPrices.put(symbol, price);
        connector.fill(orderId, symbol, side, price, qty, basketId, legIndex);
        riskCheckLatency.record(System.nanoTime() - t0);
    }

    /**
     * Update the last known price for a symbol (warm-path, from market data feed).
     * Enables fat-finger price checks.
     */
    public void updateLastPrice(final String symbol, final long priceScaled4) {
        lastPrices.put(symbol, priceScaled4);
    }

    public com.arb.common.metrics.LatencyRecorder riskCheckLatencyRecorder() { return riskCheckLatency; }

    /**
     * Start a blocking event loop subscribing to ORDER_STREAM.
     * Call on a dedicated execution thread.
     */
    public void run(final AeronSubscriber subscriber) {
        while (!Thread.currentThread().isInterrupted()) {
            subscriber.poll(this::onFragment);
        }
    }
}
