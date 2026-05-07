package com.arb.marketdata.handler;

import com.arb.marketdata.gateway.ReferenceDataGateway;
import com.arb.sbe.Exchange;

/**
 * Publishes static reference data records for all three exchanges.
 * Called at startup (cold path); allocation profile is not constrained.
 */
public final class ReferenceDataHandler {

    private final ReferenceDataGateway gateway;

    public ReferenceDataHandler(final ReferenceDataGateway gateway) {
        this.gateway = gateway;
    }

    public void onRecord(
        final String   symbol,
        final Exchange exchange,
        final long     lotSize,
        final long     tickSize,
        final String   currency,
        final long     constituentWeight)
    {
        gateway.publish(symbol, exchange, lotSize, tickSize, currency, constituentWeight);
    }
}
