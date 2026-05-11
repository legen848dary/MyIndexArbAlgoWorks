package com.arb.gateway.persistence;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Chronicle Map / Chronicle Queue implementation stub.
 *
 * TODO: When integrating Chronicle libraries:
 * - net.openhft:chronicle-map for off-heap KV (orderId lookup)
 * - net.openhft:chronicle-queue for append-only event log (audit/replay)
 */
public final class ChronicleMapTradeRepository implements TradeRepository {

    public ChronicleMapTradeRepository() {
        throw new UnsupportedOperationException(
            "ChronicleMapTradeRepository not yet implemented. " +
            "Add chronicle-map + chronicle-queue deps and implement per class Javadoc.");
    }

    @Override public void saveOrderRequest(long o, long b, int l, String sy, String si, long p, long q, long t) {}
    @Override public void saveOrderUpdate(long o, long b, String s, long fp, long fq, long t) {}
    @Override public List<Map<String, Object>> findRecentOrders(int page, int pageSize) { return Collections.emptyList(); }
    @Override public void close() {}
}
