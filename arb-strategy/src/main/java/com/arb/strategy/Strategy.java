package com.arb.strategy;

import com.arb.sbe.MarketDataTickDecoder;

/**
 * Plug-and-play strategy interface.
 * Implementations receive market events on the single Sequencer thread — no synchronization needed.
 * The {@link OrderSink} is injected per-call so implementations remain stateless w.r.t. order routing.
 */
public interface Strategy {

    /**
     * Called for every decoded {@link MarketDataTickDecoder} on the MARKET_DATA_CHANNEL.
     * The decoder is a flyweight positioned at this message — do NOT store a reference across calls.
     *
     * @param tick   SBE flyweight positioned at the current tick (zero-allocation)
     * @param orders sink to submit order requests
     */
    void onMarketData(MarketDataTickDecoder tick, OrderSink orders);

    /**
     * Called periodically by the sequencer's timer wheel.
     *
     * @param nowNanos current epoch time in nanoseconds
     * @param orders   sink to submit order requests
     */
    void onTimer(long nowNanos, OrderSink orders);
}
