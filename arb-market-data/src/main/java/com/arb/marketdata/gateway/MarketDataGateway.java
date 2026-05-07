package com.arb.marketdata.gateway;

import com.arb.common.aeron.AeronPublisher;
import com.arb.sbe.Exchange;
import com.arb.sbe.MarketDataTickEncoder;
import com.arb.sbe.MessageHeaderEncoder;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * Encodes a normalized tick as an SBE MarketDataTick and publishes it
 * to the Aeron MARKET_DATA_CHANNEL.
 *
 * This class is NOT thread-safe — it is owned by a single feed-handler thread.
 * All fields are pre-allocated; zero heap allocation in the publish path.
 */
public class MarketDataGateway implements AutoCloseable {

    private static final int BUFFER_SIZE = 256;

    private final AeronPublisher       publisher;
    private final UnsafeBuffer         txBuffer      = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MarketDataTickEncoder tickEncoder  = new MarketDataTickEncoder();

    private static final int MSG_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + MarketDataTickEncoder.BLOCK_LENGTH;

    public MarketDataGateway(final AeronPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Encode and publish one normalized tick.  Zero-allocation hot path.
     *
     * @param symbol    instrument symbol (max 12 chars)
     * @param exchange  source exchange enum
     * @param price     fixed-point price (normalized by PriceNormalizer)
     * @param qty       quantity
     * @param timestamp epoch nanoseconds
     */
    public void publish(
        final String symbol,
        final Exchange exchange,
        final long price,
        final long qty,
        final long timestamp)
    {
        tickEncoder.wrapAndApplyHeader(txBuffer, 0, headerEncoder)
            .symbol(symbol)
            .exchange(exchange)
            .price(price)
            .qty(qty)
            .timestamp(timestamp);

        publisher.publish(txBuffer, 0, MSG_LENGTH);
    }

    @Override
    public void close() {
        publisher.close();
    }
}
