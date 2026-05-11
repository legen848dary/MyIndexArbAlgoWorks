package com.arb.gateway.persistence;

import java.util.List;
import java.util.Map;

public interface TradeRepository extends AutoCloseable {
    void saveOrderRequest(long orderId, long basketId, int legIndex,
                          String symbol, String side,
                          long price, long qty, long ts);
    void saveOrderUpdate(long orderId, long basketId,
                         String status, long fillPrice, long fillQty, long ts);
    List<Map<String, Object>> findRecentOrders(int page, int pageSize);
    @Override void close();
}
