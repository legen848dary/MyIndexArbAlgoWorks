package com.arb.gateway;

import com.arb.common.Channels;
import com.arb.common.aeron.AeronPublisher;
import com.arb.common.aeron.AeronSubscriber;
import com.arb.sbe.*;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import org.agrona.DirectBuffer;
import io.aeron.logbuffer.Header;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Vert.x verticle that bridges Aeron IPC streams to browser WebSocket clients.
 *
 * <h3>Inbound Aeron → WebSocket</h3>
 * Polls MARKET_DATA_STREAM (1001), FV_STREAM (1005), ORDER_UPDATE_STREAM (1003) every 10 ms.
 * Each received SBE fragment is serialised to JSON via {@link JsonMessages} and broadcast.
 *
 * <h3>Outbound WebSocket → Aeron</h3>
 * JSON commands received from browser are published to CONTROL_STREAM (1004) as
 * {@link SystemEvent} messages via {@link AeronControlPublisher}.
 *
 * <h3>HTTP endpoints</h3>
 * <ul>
 *   <li>{@code GET  /health}  — liveness probe, returns {@code {"status":"ok"}}</li>
 *   <li>{@code POST /api/control} — REST control (body: raw command string)</li>
 *   <li>{@code WS   /ws}      — bidirectional WebSocket stream</li>
 * </ul>
 */
public final class WebGatewayVerticle extends AbstractVerticle {

    private static final int PORT          = 8080;
    private static final int POLL_INTERVAL = 10; // ms

    // WebSocket client registry (thread-safe — Vert.x event bus can be multi-threaded)
    private final Set<ServerWebSocket> clients = new CopyOnWriteArraySet<>();

    // Aeron lifecycle
    private MediaDriver           mediaDriver;
    private Aeron                 aeron;
    private AeronSubscriber       marketDataSub;
    private AeronSubscriber       fvSub;
    private AeronSubscriber       orderUpdateSub;
    private AeronSubscriber       orderSub;
    private AeronControlPublisher controlPublisher;

    // SBE decoders (pre-allocated; used only from the Vert.x event-loop thread)
    private final MessageHeaderDecoder  hdrDecoder = new MessageHeaderDecoder();
    private final MarketDataTickDecoder mdDecoder  = new MarketDataTickDecoder();
    private final FvUpdateDecoder       fvDecoder  = new FvUpdateDecoder();
    private final OrderUpdateDecoder    ouDecoder  = new OrderUpdateDecoder();
    private final OrderRequestDecoder   orDecoder  = new OrderRequestDecoder();

    @Override
    public void start(final Promise<Void> startPromise) {
        // When arb.aeron.client=true, connect to an existing MediaDriver (Docker client mode).
        // Otherwise launch an embedded MediaDriver (standalone / dev mode).
        final boolean clientMode = Boolean.getBoolean("arb.aeron.client");
        final Aeron.Context ctx;
        if (clientMode) {
            ctx = new Aeron.Context().aeronDirectoryName("/dev/shm/aeron");
        } else {
            mediaDriver = MediaDriver.launchEmbedded();
            ctx = new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName());
        }
        aeron = Aeron.connect(ctx);

        // Subscribers — AeronSubscriber wraps a Subscription
        marketDataSub  = new AeronSubscriber(aeron.addSubscription(Channels.CHANNEL, Channels.MARKET_DATA_STREAM));
        fvSub          = new AeronSubscriber(aeron.addSubscription(Channels.CHANNEL, Channels.FV_STREAM));
        orderUpdateSub = new AeronSubscriber(aeron.addSubscription(Channels.CHANNEL, Channels.ORDER_UPDATE_STREAM));
        orderSub       = new AeronSubscriber(aeron.addSubscription(Channels.CHANNEL, Channels.ORDER_STREAM));

        // Control publisher — AeronPublisher wraps a Publication
        final AeronPublisher ctrlPublisher = new AeronPublisher(
            aeron.addPublication(Channels.CHANNEL, Channels.CONTROL_STREAM));
        controlPublisher = new AeronControlPublisher(ctrlPublisher);

        // HTTP server with WebSocket + REST
        final HttpServer server = vertx.createHttpServer();

        server.webSocketHandler(ws -> {
            if (!"/ws".equals(ws.path())) {
                ws.reject();
                return;
            }
            clients.add(ws);
            ws.textMessageHandler(this::handleClientCommand);
            ws.closeHandler(v -> clients.remove(ws));
            ws.exceptionHandler(e -> clients.remove(ws));
        });

        server.requestHandler(req -> {
            if ("GET".equals(req.method().name()) && "/health".equals(req.path())) {
                req.response().putHeader("Content-Type", "application/json")
                    .end("{\"status\":\"ok\"}");
            } else if ("POST".equals(req.method().name()) && "/api/control".equals(req.path())) {
                req.bodyHandler(body -> {
                    final String cmd = body.toString().trim();
                    controlPublisher.sendCommand(cmd);
                    req.response().setStatusCode(204).end();
                });
            } else {
                req.response().setStatusCode(404).end();
            }
        });

        server.listen(PORT, result -> {
            if (result.succeeded()) {
                // Poll Aeron streams every POLL_INTERVAL ms on the event-loop thread
                vertx.setPeriodic(POLL_INTERVAL, id -> pollAeron());
                startPromise.complete();
            } else {
                startPromise.fail(result.cause());
            }
        });
    }

    @Override
    public void stop() {
        if (aeron != null)       aeron.close();
        if (mediaDriver != null) mediaDriver.close();
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void pollAeron() {
        marketDataSub.poll(this::onFragment);
        fvSub.poll(this::onFragment);
        orderUpdateSub.poll(this::onFragment);
        orderSub.poll(this::onFragment);
    }

    private void onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header) {
        hdrDecoder.wrap(buffer, offset);
        final int templateId  = hdrDecoder.templateId();
        final int blockLength = hdrDecoder.blockLength();
        final int version     = hdrDecoder.version();
        final int msgOffset   = offset + MessageHeaderDecoder.ENCODED_LENGTH;

        final String json;
        switch (templateId) {
            case MarketDataTickDecoder.TEMPLATE_ID:
                mdDecoder.wrap(buffer, msgOffset, blockLength, version);
                json = JsonMessages.marketData(mdDecoder);
                break;
            case FvUpdateDecoder.TEMPLATE_ID:
                fvDecoder.wrap(buffer, msgOffset, blockLength, version);
                json = JsonMessages.fvUpdate(fvDecoder);
                break;
            case OrderUpdateDecoder.TEMPLATE_ID:
                ouDecoder.wrap(buffer, msgOffset, blockLength, version);
                json = JsonMessages.orderUpdate(ouDecoder);
                break;
            case OrderRequestDecoder.TEMPLATE_ID:
                orDecoder.wrap(buffer, msgOffset, blockLength, version);
                json = JsonMessages.orderRequest(orDecoder);
                break;
            default:
                return;
        }
        broadcast(json);
    }

    private void broadcast(final String json) {
        for (final ServerWebSocket ws : clients) {
            ws.writeTextMessage(json);
        }
    }

    private void handleClientCommand(final String msg) {
        // Forward raw command string to CONTROL_STREAM (truncate to SBE field max 128 chars)
        if (msg == null || msg.isBlank()) return;
        final String cmd = msg.length() > 128 ? msg.substring(0, 128) : msg;
        controlPublisher.sendCommand(cmd);
        // Echo simulation lifecycle commands back to all WebSocket clients as SIMULATION_STATUS
        if (cmd.startsWith("START_SIMULATION:")) {
            final String profile = cmd.substring("START_SIMULATION:".length()).trim();
            broadcast(JsonMessages.simulationStatus(profile, "STARTING", 0));
        } else if (cmd.equals("STOP_SIMULATION")) {
            broadcast(JsonMessages.simulationStatus("", "STOPPED", 0));
        } else if (cmd.startsWith("SET_PROFILE:")) {
            final String profile = cmd.substring("SET_PROFILE:".length()).trim();
            broadcast(JsonMessages.simulationStatus(profile, "PROFILE_SET", 0));
        }
    }
}
