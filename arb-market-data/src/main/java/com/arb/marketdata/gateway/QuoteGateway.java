package com.arb.marketdata.gateway;

import com.arb.common.aeron.AeronPublisher;
import com.arb.sbe.Exchange;
import com.arb.sbe.MessageHeaderEncoder;
import com.arb.sbe.QuoteTickEncoder;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * Encodes a quote (IEP, bid, ask) as an SBE QuoteTick and publishes it
 * to the Aeron MARKET_DATA_CHANNEL.
 * Single-threaded — all fields pre-allocated; zero heap allocation in publish path.
 */
public final class QuoteGateway implements AutoCloseable {

    private static final int BUFFER_SIZE = 1024;
    private static final int MSG_LENGTH  =
        MessageHeaderEncoder.ENCODED_LENGTH + QuoteTickEncoder.BLOCK_LENGTH;

    private final AeronPublisher      publisher;
    private final UnsafeBuffer        txBuffer      = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final QuoteTickEncoder    quoteEncoder  = new QuoteTickEncoder();

    public QuoteGateway(final AeronPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Encode and publish one quote tick. Zero-allocation hot path.
     *
     * @param symbol    instrument symbol (max 12 chars)
     * @param exchange  source exchange enum
     * @param iep       Indicative Equilibrium Price, fixed-point 10^4
     * @param bidPrice  best bid price, fixed-point 10^4
     * @param askPrice  best ask price, fixed-point 10^4
     * @param timestamp epoch nanoseconds
     */
    public void publish(
        final String   symbol,
        final Exchange exchange,
        final long     iep,
        final long     bidPrice,
        final long     askPrice,
        final long     timestamp)
    {
        quoteEncoder.wrapAndApplyHeader(txBuffer, 0, headerEncoder)
            .symbol(symbol)
            .exchange(exchange)
            .iep(iep)
            .bidPrice(bidPrice)
            .askPrice(askPrice)
            .timestamp(timestamp);

        publisher.publish(txBuffer, 0, MSG_LENGTH);
    }

    @Override
    public void close() {
        publisher.close();
    }
}
