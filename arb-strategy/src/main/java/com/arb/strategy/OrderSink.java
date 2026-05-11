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
     * Encodes with basketId=0 and legIndex=0 (standalone, single-leg order).
     */
    void send(String symbol, Side side, long price, long qty, OrderType orderType);

    /**
     * Encode and publish a basket leg order (2-leg arb trade).
     * Default delegates to {@link #send} for backward compat; ArbSequencer overrides to encode basketId/legIndex.
     *
     * @param basketId  unique ID grouping both legs of one arb trade
     * @param legIndex  1 = first leg (futures/ETF), 2 = second leg (constituent basket/spot)
     */
    default void sendLeg(long basketId, int legIndex, String symbol, Side side, long price, long qty, OrderType orderType) {
        send(symbol, side, price, qty, orderType);
    }
}
