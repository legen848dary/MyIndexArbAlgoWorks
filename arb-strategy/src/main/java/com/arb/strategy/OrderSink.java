package com.arb.strategy;

import com.arb.sbe.OrderType;
import com.arb.sbe.Side;

/**
 * Zero-allocation callback interface for submitting order requests from a {@link Strategy}.
 * The sequencer provides a pre-allocated implementation backed by an SBE encoder + Aeron publisher.
 */
@FunctionalInterface
public interface OrderSink {

    /**
     * Encode and publish one {@code OrderRequest} SBE message to the ORDER_CHANNEL.
     * All parameters are primitives or pre-allocated strings — no heap allocation on the hot path.
     *
     * @param symbol    instrument symbol (max 12 chars)
     * @param side      BUY or SELL
     * @param price     fixed-point price scaled by 10^4 (0 for MARKET orders)
     * @param qty       quantity
     * @param orderType MARKET or LIMIT
     */
    void send(String symbol, Side side, long price, long qty, OrderType orderType);
}
