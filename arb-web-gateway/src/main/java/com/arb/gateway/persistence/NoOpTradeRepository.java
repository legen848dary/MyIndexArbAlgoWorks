package com.arb.gateway.persistence;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Discards all data. Used when arb.persistence.type=none. */
public final class NoOpTradeRepository implements TradeRepository {
    @Override public void saveOrderRequest(long o, long b, int l, String sy, String si, long p, long q, long t) {}
    @Override public void saveOrderUpdate(long o, long b, String s, long fp, long fq, long t) {}
    @Override public List<Map<String, Object>> findRecentOrders(int page, int pageSize) { return Collections.emptyList(); }
    @Override public void close() {}
}
