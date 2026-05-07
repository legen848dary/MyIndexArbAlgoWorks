package com.arb.marketdata.handler;

import com.arb.marketdata.FeedHandler;
import com.arb.marketdata.gateway.MarketDataGateway;
import com.arb.marketdata.normalizer.PriceNormalizer;
import com.arb.sbe.Exchange;

/**
 * Simulates the Taiwan Futures Exchange (TAIFEX) feed.
 * Handles TWSE Index, TSMC SSF, and Taiwan 50 ETF (0050) priced in TWD.
 */
public class TaifexFeedHandler implements FeedHandler {

    private final MarketDataGateway gateway;

    public TaifexFeedHandler(final MarketDataGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void onTick(final String symbol, final double price) {
        final long normalizedPrice = PriceNormalizer.normalize(price);
        final long timestamp       = System.nanoTime();
        gateway.publish(symbol, Exchange.TAIFEX, normalizedPrice, 0L, timestamp);
    }
}
