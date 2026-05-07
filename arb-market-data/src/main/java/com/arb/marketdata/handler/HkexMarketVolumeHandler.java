package com.arb.marketdata.handler;

import com.arb.marketdata.gateway.MarketVolumeGateway;
import com.arb.sbe.Exchange;

/** Simulates the HKEX market-volume feed (IEV and daily volume for HSI constituents). */
public final class HkexMarketVolumeHandler {

    private final MarketVolumeGateway gateway;

    public HkexMarketVolumeHandler(final MarketVolumeGateway gateway) {
        this.gateway = gateway;
    }

    public void onVolume(final String symbol, final long iev, final long dailyVolume) {
        gateway.publish(symbol, Exchange.HKEX, iev, dailyVolume, System.nanoTime());
    }
}
