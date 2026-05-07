package com.arb.marketdata.handler;

import com.arb.marketdata.gateway.QuoteGateway;
import com.arb.marketdata.normalizer.PriceNormalizer;
import com.arb.sbe.Exchange;

/** Simulates an HKEX quote feed (IEP, bid, ask for HSI constituents in HKD). */
public final class HkexQuoteHandler {

    private final QuoteGateway gateway;

    public HkexQuoteHandler(final QuoteGateway gateway) {
        this.gateway = gateway;
    }

    public void onQuote(
        final String symbol,
        final double iep,
        final double bid,
        final double ask)
    {
        gateway.publish(
            symbol, Exchange.HKEX,
            PriceNormalizer.normalize(iep),
            PriceNormalizer.normalize(bid),
            PriceNormalizer.normalize(ask),
            System.nanoTime());
    }
}
