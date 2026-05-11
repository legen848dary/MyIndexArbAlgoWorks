package com.arb.gateway;

import com.arb.sbe.*;

import java.nio.charset.StandardCharsets;

/**
 * Serialises decoded SBE messages into compact JSON strings for WebSocket dispatch.
 *
 * <p>Uses manual string building (no Jackson/Gson) to keep gateway dependency surface minimal.
 * The gateway is NOT on the ultra-low-latency hot path, so string allocation is acceptable.
 *
 * <p>Price fields are transmitted as raw fixed-point longs (scaled ×10^4). The frontend
 * divides by 10_000.0 to render human-readable prices.
 */
public final class JsonMessages {

    private static final int SYM_LEN = 12;
    private static final int MSG_LEN = 128;

    private JsonMessages() {}

    /** Serialise a MarketDataTick decode result to JSON. */
    public static String marketData(final MarketDataTickDecoder tick) {
        final byte[] symBuf = new byte[SYM_LEN];
        tick.getSymbol(symBuf, 0);
        return "{\"type\":\"MARKET_DATA\",\"symbol\":\"" + trimmed(symBuf, SYM_LEN) +
               "\",\"exchange\":\"" + tick.exchange().name() +
               "\",\"price\":" + tick.price() +
               ",\"qty\":" + tick.qty() +
               ",\"ts\":" + tick.timestamp() + "}";
    }

    /** Serialise an FvUpdate decode result to JSON. */
    public static String fvUpdate(final FvUpdateDecoder fv) {
        final byte[] symBuf = new byte[SYM_LEN];
        fv.getSymbol(symBuf, 0);
        return "{\"type\":\"FV_UPDATE\",\"symbol\":\"" + trimmed(symBuf, SYM_LEN) +
               "\",\"exchange\":\"" + fv.exchange().name() +
               "\",\"futuresFv\":" + fv.futuresFv() +
               ",\"navPerUnit\":" + fv.navPerUnit() +
               ",\"basis\":" + fv.basis() +
               ",\"annualisedBasisBps100\":" + fv.annualisedBasisBps() +
               ",\"ts\":" + fv.timestamp() + "}";
    }

    /** Serialise an OrderRequest decode result to JSON. */
    public static String orderRequest(final OrderRequestDecoder msg) {
        final byte[] symBuf = new byte[SYM_LEN];
        msg.getSymbol(symBuf, 0);
        return "{\"type\":\"ORDER_REQUEST\",\"orderId\":" + msg.orderId() +
               ",\"symbol\":\"" + trimmed(symBuf, SYM_LEN) +
               "\",\"side\":\"" + msg.side().name() +
               "\",\"price\":" + msg.price() +
               ",\"qty\":" + msg.qty() +
               ",\"orderType\":\"" + msg.orderType().name() +
               "\",\"basketId\":" + msg.basketId() +
               ",\"legIndex\":" + msg.legIndex() +
               ",\"ts\":" + System.currentTimeMillis() + "}";
    }

    /** Serialise an OrderUpdate decode result to JSON. */
    public static String orderUpdate(final OrderUpdateDecoder upd) {
        final byte[] symBuf = new byte[SYM_LEN];
        upd.getSymbol(symBuf, 0);
        return "{\"type\":\"ORDER_UPDATE\",\"orderId\":" + upd.orderId() +
               ",\"symbol\":\"" + trimmed(symBuf, SYM_LEN) +
               "\",\"side\":\"" + upd.side().name() +
               "\",\"fillPrice\":" + upd.fillPrice() +
               ",\"fillQty\":" + upd.fillQty() +
               ",\"status\":\"" + upd.status().name() +
               "\",\"rejectCode\":" + upd.rejectCode() +
               ",\"basketId\":" + upd.basketId() +
               ",\"legIndex\":" + upd.legIndex() +
               ",\"ts\":" + upd.timestamp() + "}";
    }

    /** Serialise a LatencyStats decode result to JSON. */
    public static String latencyStats(final LatencyStatsDecoder s) {
        final byte[] catBuf = new byte[8];
        s.getCategory(catBuf, 0);
        int clen = 8;
        while (clen > 0 && (catBuf[clen - 1] == 0 || catBuf[clen - 1] == ' ')) clen--;
        final String cat = new String(catBuf, 0, clen, StandardCharsets.US_ASCII);
        return "{\"type\":\"LATENCY_STATS\",\"category\":\"" + cat +
               "\",\"b0Sub1us\":"   + s.b0Sub1us()    +
               ",\"b1to5us\":"      + s.b1to5us()     +
               ",\"b5to10us\":"     + s.b5to10us()    +
               ",\"b10to50us\":"    + s.b10to50us()   +
               ",\"b50to100us\":"   + s.b50to100us()  +
               ",\"b100to500us\":"  + s.b100to500us() +
               ",\"bOver500us\":"   + s.bOver500us()  +
               ",\"minNs\":"        + s.minNs()       +
               ",\"maxNs\":"        + s.maxNs()       +
               ",\"avgNs\":"        + s.avgNs()       +
               ",\"sampleCount\":"  + s.sampleCount() +
               ",\"ts\":"           + s.timestamp()   + "}";
    }

    /** Extract trimmed symbol string from an OrderRequest (for persistence). */
    public static String trimmedOrderRequestSymbol(final OrderRequestDecoder msg) {
        final byte[] buf = new byte[SYM_LEN];
        msg.getSymbol(buf, 0);
        return trimmed(buf, SYM_LEN);
    }

    /** Serialise a simulation status update to JSON. */
    public static String simulationStatus(final String profile, final String phase, final long tickCount) {
        final boolean running = !"STOPPED".equals(phase);
        return "{\"type\":\"SIMULATION_STATUS\",\"running\":" + running +
               ",\"profile\":\"" + profile +
               "\",\"phase\":\"" + phase +
               "\",\"tickCount\":" + tickCount +
               ",\"ts\":" + System.currentTimeMillis() + "}";
    }

    /** Serialise a SystemEvent decode result to JSON. */
    public static String systemEvent(final SystemEventDecoder evt) {
        final byte[] msgBuf = new byte[MSG_LEN];
        evt.getMessage(msgBuf, 0);
        // Escape inner double quotes in the message string
        final String msg = trimmed(msgBuf, MSG_LEN).replace("\"", "\\\"");
        return "{\"type\":\"SYSTEM_EVENT\",\"eventType\":\"" + evt.eventType().name() +
               "\",\"message\":\"" + msg +
               "\",\"ts\":" + evt.timestamp() + "}";
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    static String trimmed(final byte[] buf, final int maxLen) {
        int len = maxLen;
        while (len > 0 && (buf[len - 1] == 0 || buf[len - 1] == ' ')) len--;
        return new String(buf, 0, len, StandardCharsets.US_ASCII);
    }
}
