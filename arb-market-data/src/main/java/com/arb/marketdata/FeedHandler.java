package com.arb.marketdata;

/**
 * Contract for all exchange feed handlers.
 * Implementations receive raw ticks from a simulated feed and delegate
 * to the MarketDataGateway for normalization and Aeron publishing.
 */
public interface FeedHandler {

    /**
     * Called when a raw price tick arrives from the exchange feed.
     *
     * @param symbol instrument symbol (e.g. "0700.HK")
     * @param price  raw double price in the exchange's native currency
     */
    void onTick(String symbol, double price);
}
