package com.arb.gateway.persistence;

import java.sql.*;
import java.util.*;

public final class H2TradeRepository implements TradeRepository {

    private final Connection conn;

    public H2TradeRepository(final String jdbcUrl) {
        try {
            Class.forName("org.h2.Driver");
            conn = DriverManager.getConnection(jdbcUrl, "sa", "");
            createTables();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise H2: " + e.getMessage(), e);
        }
    }

    public H2TradeRepository() {
        this("jdbc:h2:mem:arb;DB_CLOSE_DELAY=-1");
    }

    private void createTables() throws SQLException {
        try (final Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS order_requests (
                    order_id   BIGINT PRIMARY KEY,
                    basket_id  BIGINT NOT NULL,
                    leg_index  INT NOT NULL,
                    symbol     VARCHAR(16) NOT NULL,
                    side       VARCHAR(4) NOT NULL,
                    price      BIGINT NOT NULL,
                    qty        BIGINT NOT NULL,
                    ts         BIGINT NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS order_updates (
                    id         IDENTITY PRIMARY KEY,
                    order_id   BIGINT NOT NULL,
                    basket_id  BIGINT NOT NULL,
                    status     VARCHAR(16) NOT NULL,
                    fill_price BIGINT NOT NULL,
                    fill_qty   BIGINT NOT NULL,
                    ts         BIGINT NOT NULL
                )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_req_basket ON order_requests(basket_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_upd_order  ON order_updates(order_id)");
        }
    }

    @Override
    public synchronized void saveOrderRequest(
            final long orderId, final long basketId, final int legIndex,
            final String symbol, final String side, final long price, final long qty, final long ts) {
        try (final PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO order_requests VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setLong  (1, orderId);
            ps.setLong  (2, basketId);
            ps.setInt   (3, legIndex);
            ps.setString(4, symbol);
            ps.setString(5, side);
            ps.setLong  (6, price);
            ps.setLong  (7, qty);
            ps.setLong  (8, ts);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[persistence] H2 saveOrderRequest error: " + e.getMessage());
        }
    }

    @Override
    public synchronized void saveOrderUpdate(
            final long orderId, final long basketId,
            final String status, final long fillPrice, final long fillQty, final long ts) {
        try (final PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO order_updates(order_id,basket_id,status,fill_price,fill_qty,ts) VALUES (?,?,?,?,?,?)")) {
            ps.setLong  (1, orderId);
            ps.setLong  (2, basketId);
            ps.setString(3, status);
            ps.setLong  (4, fillPrice);
            ps.setLong  (5, fillQty);
            ps.setLong  (6, ts);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[persistence] H2 saveOrderUpdate error: " + e.getMessage());
        }
    }

    @Override
    public synchronized List<Map<String, Object>> findRecentOrders(final int page, final int pageSize) {
        final String sql = """
            SELECT r.order_id, r.basket_id, r.leg_index, r.symbol, r.side,
                   r.price, r.qty, r.ts,
                   u.status, u.fill_price, u.fill_qty, u.ts AS fill_ts
            FROM order_requests r
            LEFT JOIN (
                SELECT order_id, status, fill_price, fill_qty, ts,
                       ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY id DESC) rn
                FROM order_updates
            ) u ON u.order_id = r.order_id AND u.rn = 1
            ORDER BY r.ts DESC
            LIMIT ? OFFSET ?
            """;
        final List<Map<String, Object>> rows = new ArrayList<>();
        try (final PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, pageSize);
            ps.setInt(2, page * pageSize);
            final ResultSet rs = ps.executeQuery();
            final ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                final Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnName(i).toLowerCase(), rs.getObject(i));
                }
                rows.add(row);
            }
        } catch (SQLException e) {
            System.err.println("[persistence] H2 findRecentOrders error: " + e.getMessage());
        }
        return rows;
    }

    @Override
    public synchronized void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
