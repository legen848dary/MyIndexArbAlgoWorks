package com.arb.strategy;

import com.arb.sbe.MarketDataTickDecoder;
import com.arb.sbe.MarketVolumeTickDecoder;
import com.arb.sbe.QuoteTickDecoder;
import com.arb.sbe.ReferenceDataRecordDecoder;

/**
 * Plug-and-play strategy interface.
 * Implementations receive market events on the single Sequencer thread — no synchronization needed.
 * The {@link OrderSink} is injected per-call so implementations remain stateless w.r.t. order routing.
 *
 * <p>All {@code on*} methods except {@link #onMarketData} and {@link #onTimer} have {@code default}
 * no-op implementations so existing strategies do not need to handle message types they ignore.
 */
public interface Strategy {

    /**
     * Called for every decoded {@link MarketDataTickDecoder} on the MARKET_DATA_CHANNEL.
     * The decoder is a flyweight positioned at this message — do NOT store a reference across calls.
     */
    void onMarketData(MarketDataTickDecoder tick, OrderSink orders);

    /**
     * Called for every decoded {@link QuoteTickDecoder} (templateId=4): IEP, bid, ask.
     * Default no-op — override in strategies that consume quote data.
     */
    default void onQuote(QuoteTickDecoder quote, OrderSink orders) {}

    /**
     * Called for every decoded {@link MarketVolumeTickDecoder} (templateId=5): IEV, daily volume.
     * Default no-op — override in strategies that consume volume data.
     */
    default void onMarketVolume(MarketVolumeTickDecoder vol, OrderSink orders) {}

    /**
     * Called for every decoded {@link ReferenceDataRecordDecoder} (templateId=6): static ref data.
     * No {@link OrderSink} — reference data does not trigger orders.
     * Default no-op — override in strategies that need lot/tick size or constituent weight.
     */
    default void onReferenceData(ReferenceDataRecordDecoder refData) {}

    /**
     * Called periodically by the sequencer's timer wheel.
     *
     * @param nowNanos current epoch time in nanoseconds
     * @param orders   sink to submit order requests
     */
    void onTimer(long nowNanos, OrderSink orders);
}

