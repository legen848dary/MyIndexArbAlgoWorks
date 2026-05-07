package com.arb.marketdata.handler;

import com.arb.marketdata.gateway.MarketVolumeGateway;
import com.arb.sbe.Exchange;

/** Simulates the TAIFEX market-volume feed (IEV and daily volume for TAIEX constituents). */
public final class TaifexMarketVolumeHandler {

    private final MarketVolumeGateway gateway;

    public TaifexMarketVolumeHandler(final MarketVolumeGateway gateway) {
        this.gateway = gateway;
    }

    public void onVolume(final String symbol, final long iev, final long dailyVolume) {
        gateway.publish(symbol, Exchange.TAIFEX, iev, dailyVolume, System.nanoTime());
    }
}
