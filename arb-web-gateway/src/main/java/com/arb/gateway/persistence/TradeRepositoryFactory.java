package com.arb.gateway.persistence;

public final class TradeRepositoryFactory {

    private TradeRepositoryFactory() {}

    public static TradeRepository create() {
        final String type = System.getProperty("arb.persistence.type", "h2");
        return switch (type) {
            case "chronicle" -> new ChronicleMapTradeRepository();
            case "none"      -> new NoOpTradeRepository();
            default          -> new H2TradeRepository();
        };
    }
}
