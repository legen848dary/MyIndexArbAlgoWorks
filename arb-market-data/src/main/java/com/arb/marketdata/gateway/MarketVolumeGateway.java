package com.arb.marketdata.gateway;

import com.arb.common.aeron.AeronPublisher;
import com.arb.sbe.Exchange;
import com.arb.sbe.MarketVolumeTickEncoder;
import com.arb.sbe.MessageHeaderEncoder;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * Encodes a market-volume update (IEV, daily volume) as an SBE MarketVolumeTick
 * and publishes it to the Aeron MARKET_DATA_CHANNEL.
 * Single-threaded — all fields pre-allocated; zero heap allocation in publish path.
 */
public final class MarketVolumeGateway implements AutoCloseable {

    private static final int BUFFER_SIZE = 1024;
    private static final int MSG_LENGTH  =
        MessageHeaderEncoder.ENCODED_LENGTH + MarketVolumeTickEncoder.BLOCK_LENGTH;

    private final AeronPublisher          publisher;
    private final UnsafeBuffer            txBuffer       = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE));
    private final MessageHeaderEncoder    headerEncoder  = new MessageHeaderEncoder();
    private final MarketVolumeTickEncoder volumeEncoder  = new MarketVolumeTickEncoder();

    public MarketVolumeGateway(final AeronPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Encode and publish one volume tick. Zero-allocation hot path.
     *
     * @param symbol      instrument symbol (max 12 chars)
     * @param exchange    source exchange enum
     * @param iev         Indicative Exchange Volume (share count at IEP during auction)
     * @param dailyVolume cumulative daily traded volume
     * @param timestamp   epoch nanoseconds
     */
    public void publish(
        final String   symbol,
        final Exchange exchange,
        final long     iev,
        final long     dailyVolume,
        final long     timestamp)
    {
        volumeEncoder.wrapAndApplyHeader(txBuffer, 0, headerEncoder)
            .symbol(symbol)
            .exchange(exchange)
            .iev(iev)
            .dailyVolume(dailyVolume)
            .timestamp(timestamp);

        publisher.publish(txBuffer, 0, MSG_LENGTH);
    }

    @Override
    public void close() {
        publisher.close();
    }
}
