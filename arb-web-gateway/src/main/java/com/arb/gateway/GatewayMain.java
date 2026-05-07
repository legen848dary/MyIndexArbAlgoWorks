package com.arb.gateway;

import io.vertx.core.Vertx;

/**
 * Entry point for the arb-web-gateway service.
 * Usage: java -cp ... com.arb.gateway.GatewayMain
 */
public final class GatewayMain {

    public static void main(final String[] args) {
        // Enable Aeron client mode when AERON_CLIENT_MODE env var is set to "true"
        if ("true".equalsIgnoreCase(System.getenv("AERON_CLIENT_MODE"))) {
            System.setProperty("arb.aeron.client", "true");
        }
        final Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new WebGatewayVerticle(), result -> {
            if (result.succeeded()) {
                System.out.println("[gateway] WebSocket bridge listening on ws://localhost:8080/ws");
            } else {
                System.err.println("[gateway] Failed to start: " + result.cause().getMessage());
                vertx.close();
            }
        });
    }
}
