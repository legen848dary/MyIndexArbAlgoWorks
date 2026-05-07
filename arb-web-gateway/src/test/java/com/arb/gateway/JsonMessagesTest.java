package com.arb.gateway;

import com.arb.sbe.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class JsonMessagesTest {

    private final UnsafeBuffer         buf    = new UnsafeBuffer(ByteBuffer.allocate(512));
    private final MessageHeaderEncoder hdrEnc = new MessageHeaderEncoder();
    private final MessageHeaderDecoder hdrDec = new MessageHeaderDecoder();

    // ── MarketDataTick ────────────────────────────────────────────────────────

    @Test
    void marketData_json_containsSymbolAndPrice() {
        final MarketDataTickEncoder enc = new MarketDataTickEncoder();
        hdrEnc.wrap(buf, 0)
            .blockLength(MarketDataTickEncoder.BLOCK_LENGTH)
            .templateId(MarketDataTickEncoder.TEMPLATE_ID)
            .schemaId(MarketDataTickEncoder.SCHEMA_ID)
            .version(MarketDataTickEncoder.SCHEMA_VERSION);
        enc.wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol("HSI.HK")
            .exchange(Exchange.HKEX)
            .price(190_000_0000L)
            .qty(1_000L)
            .timestamp(1_700_000_000_000L);

        final MarketDataTickDecoder dec = new MarketDataTickDecoder();
        hdrDec.wrap(buf, 0);
        dec.wrap(buf, MessageHeaderDecoder.ENCODED_LENGTH, hdrDec.blockLength(), hdrDec.version());

        final String json = JsonMessages.marketData(dec);
        assertTrue(json.contains("\"type\":\"MARKET_DATA\""), "type field");
        assertTrue(json.contains("\"symbol\":\"HSI.HK\""), "symbol field");
        assertTrue(json.contains("\"price\":1900000000"), "price field");
        assertTrue(json.contains("\"exchange\":\"HKEX\""), "exchange field");
    }

    // ── FvUpdate ─────────────────────────────────────────────────────────────

    @Test
    void fvUpdate_json_containsBasisAndFv() {
        final FvUpdateEncoder enc = new FvUpdateEncoder();
        hdrEnc.wrap(buf, 0)
            .blockLength(FvUpdateEncoder.BLOCK_LENGTH)
            .templateId(FvUpdateEncoder.TEMPLATE_ID)
            .schemaId(FvUpdateEncoder.SCHEMA_ID)
            .version(FvUpdateEncoder.SCHEMA_VERSION);
        enc.wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH)
            .symbol("HSI.HK")
            .exchange(Exchange.HKEX)
            .navPerUnit(0L)
            .futuresFv(190_100_0000L)
            .basis(100_0000L)
            .annualisedBasisBps(32_017L)
            .timestamp(1_700_000_000_000L);

        final FvUpdateDecoder dec = new FvUpdateDecoder();
        hdrDec.wrap(buf, 0);
        dec.wrap(buf, MessageHeaderDecoder.ENCODED_LENGTH, hdrDec.blockLength(), hdrDec.version());

        final String json = JsonMessages.fvUpdate(dec);
        assertTrue(json.contains("\"type\":\"FV_UPDATE\""), "type field");
        assertTrue(json.contains("\"futuresFv\":1901000000"), "futuresFv field");
        assertTrue(json.contains("\"annualisedBasisBps100\":32017"), "bps field");
    }

    // ── OrderUpdate ──────────────────────────────────────────────────────────

    @Test
    void orderUpdate_json_containsStatusAndSide() {
        final OrderUpdateEncoder enc = new OrderUpdateEncoder();
        hdrEnc.wrap(buf, 0)
            .blockLength(OrderUpdateEncoder.BLOCK_LENGTH)
            .templateId(OrderUpdateEncoder.TEMPLATE_ID)
            .schemaId(OrderUpdateEncoder.SCHEMA_ID)
            .version(OrderUpdateEncoder.SCHEMA_VERSION);
        enc.wrap(buf, MessageHeaderEncoder.ENCODED_LENGTH)
            .orderId(42L)
            .symbol("MHI.HK")
            .side(Side.SELL)
            .fillPrice(380_000_0000L)
            .fillQty(5L)
            .status(OrderStatus.FILLED)
            .rejectCode((short) 0)
            .timestamp(1_700_000_000_000L);

        final OrderUpdateDecoder dec = new OrderUpdateDecoder();
        hdrDec.wrap(buf, 0);
        dec.wrap(buf, MessageHeaderDecoder.ENCODED_LENGTH, hdrDec.blockLength(), hdrDec.version());

        final String json = JsonMessages.orderUpdate(dec);
        assertTrue(json.contains("\"type\":\"ORDER_UPDATE\""), "type field");
        assertTrue(json.contains("\"orderId\":42"), "orderId field");
        assertTrue(json.contains("\"status\":\"FILLED\""), "status field");
        assertTrue(json.contains("\"side\":\"SELL\""), "side field");
    }

    // ── trimmed helper ────────────────────────────────────────────────────────

    @Test
    void trimmed_removesNullAndSpacePadding() {
        final byte[] padded = new byte[12];
        System.arraycopy("HSI.HK".getBytes(StandardCharsets.US_ASCII), 0, padded, 0, 6);
        assertEquals("HSI.HK", JsonMessages.trimmed(padded, 12));
    }
}
