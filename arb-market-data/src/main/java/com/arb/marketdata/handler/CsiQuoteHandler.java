package com.arb.marketdata.handler;

import com.arb.marketdata.gateway.QuoteGateway;
import com.arb.marketdata.normalizer.PriceNormalizer;
import com.arb.sbe.Exchange;

/** Simulates a CSI quote feed (IEP, bid, ask for CSI300 constituents in CNY). */
public final class CsiQuoteHandler {

    private final QuoteGateway gateway;

    public CsiQuoteHandler(final QuoteGateway gateway) {
        this.gateway = gateway;
    }

    public void onQuote(
        final String symbol,
        final double iep,
        final double bid,
        final double ask)
    {
        gateway.publish(
            symbol, Exchange.CSI,
            PriceNormalizer.normalize(iep),
            PriceNormalizer.normalize(bid),
            PriceNormalizer.normalize(ask),
            System.nanoTime());
    }
}
