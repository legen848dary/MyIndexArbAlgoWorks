package com.arb.marketdata.handler;

import com.arb.marketdata.gateway.QuoteGateway;
import com.arb.marketdata.normalizer.PriceNormalizer;
import com.arb.sbe.Exchange;

/** Simulates a TAIFEX quote feed (IEP, bid, ask for TAIEX constituents in TWD). */
public final class TaifexQuoteHandler {

    private final QuoteGateway gateway;

    public TaifexQuoteHandler(final QuoteGateway gateway) {
        this.gateway = gateway;
    }

    public void onQuote(
        final String symbol,
        final double iep,
        final double bid,
        final double ask)
    {
        gateway.publish(
            symbol, Exchange.TAIFEX,
            PriceNormalizer.normalize(iep),
            PriceNormalizer.normalize(bid),
            PriceNormalizer.normalize(ask),
            System.nanoTime());
    }
}
