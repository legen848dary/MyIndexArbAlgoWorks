package com.arb.marketdata.handler;

import com.arb.marketdata.FeedHandler;
import com.arb.marketdata.gateway.MarketDataGateway;
import com.arb.marketdata.normalizer.PriceNormalizer;
import com.arb.sbe.Exchange;

/**
 * Simulates the CSI (China Securities Index) feed.
 * Handles CSI 300 constituents and futures priced in CNY.
 */
public class CsiFeedHandler implements FeedHandler {

    private final MarketDataGateway gateway;

    public CsiFeedHandler(final MarketDataGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void onTick(final String symbol, final double price) {
        final long normalizedPrice = PriceNormalizer.normalize(price);
        final long timestamp       = System.nanoTime();
        gateway.publish(symbol, Exchange.CSI, normalizedPrice, 0L, timestamp);
    }
}
