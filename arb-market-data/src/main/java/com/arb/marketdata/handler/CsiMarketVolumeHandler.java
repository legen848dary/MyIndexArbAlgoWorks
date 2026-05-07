package com.arb.marketdata.handler;

import com.arb.marketdata.gateway.MarketVolumeGateway;
import com.arb.sbe.Exchange;

/** Simulates the CSI market-volume feed (IEV and daily volume for CSI300 constituents). */
public final class CsiMarketVolumeHandler {

    private final MarketVolumeGateway gateway;

    public CsiMarketVolumeHandler(final MarketVolumeGateway gateway) {
        this.gateway = gateway;
    }

    public void onVolume(final String symbol, final long iev, final long dailyVolume) {
        gateway.publish(symbol, Exchange.CSI, iev, dailyVolume, System.nanoTime());
    }
}
