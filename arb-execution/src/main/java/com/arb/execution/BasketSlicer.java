package com.arb.execution;

import com.arb.sbe.OrderType;
import com.arb.sbe.Side;
import com.arb.strategy.OrderSink;

import java.util.List;

/**
 * Decomposes a multi-leg basket instruction into individual {@link OrderSink#send} calls.
 *
 * <p>Each {@link BasketLeg} is an immutable record carrying the full parameters for one
 * instrument leg. The slicer fires them sequentially, preserving order.
 *
 * <p>Prototype assumption: all legs are treated as independent orders (no leg-locking or
 * partial fill aggregation in Phase 4).
 */
public final class BasketSlicer {

    /**
     * Immutable descriptor for one basket leg.
     *
     * @param symbol       instrument symbol (max 12 chars)
     * @param side         BUY or SELL
     * @param priceScaled4 limit price, fixed-point 10^4 (0 for MARKET)
     * @param qtyLots      quantity in lots
     * @param orderType    MARKET or LIMIT
     */
    public record BasketLeg(String symbol, Side side, long priceScaled4, long qtyLots, OrderType orderType) {}

    /**
     * Slice a basket into individual orders, dispatching each leg through {@code sink}.
     *
     * @param legs ordered list of basket legs; must not be null
     * @param sink order routing interface (zero-GC hot path)
     */
    public void slice(final List<BasketLeg> legs, final OrderSink sink) {
        for (int i = 0, n = legs.size(); i < n; i++) {
            final BasketLeg leg = legs.get(i);
            sink.send(leg.symbol(), leg.side(), leg.priceScaled4(), leg.qtyLots(), leg.orderType());
        }
    }
}
