package com.arb.marketdata.handler;

import com.arb.marketdata.FeedHandler;
import com.arb.marketdata.gateway.MarketDataGateway;
import com.arb.marketdata.normalizer.PriceNormalizer;
import com.arb.sbe.Exchange;

/**
 * Simulates the Hong Kong Exchange (HKEX) feed.
 * Handles HSI Index constituents priced in HKD.
 */
public class HkexFeedHandler implements FeedHandler {

    private final MarketDataGateway gateway;

    public HkexFeedHandler(final MarketDataGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void onTick(final String symbol, final double price) {
        final long normalizedPrice = PriceNormalizer.normalize(price);
        final long timestamp       = System.nanoTime();
        gateway.publish(symbol, Exchange.HKEX, normalizedPrice, 0L, timestamp);
    }
}
