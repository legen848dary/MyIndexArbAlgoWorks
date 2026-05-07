package com.arb.marketdata.gateway;

import com.arb.common.aeron.AeronPublisher;
import com.arb.sbe.Exchange;
import com.arb.sbe.MessageHeaderEncoder;
import com.arb.sbe.ReferenceDataRecordEncoder;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * Encodes a static reference data record as an SBE ReferenceDataRecord and publishes it
 * to the Aeron MARKET_DATA_CHANNEL.
 * Called at startup only (cold path) — allocation profile does not matter,
 * but the publish() itself is zero-GC for consistency.
 */
public final class ReferenceDataGateway implements AutoCloseable {

    private static final int BUFFER_SIZE = 1024;
    private static final int MSG_LENGTH  =
        MessageHeaderEncoder.ENCODED_LENGTH + ReferenceDataRecordEncoder.BLOCK_LENGTH;

    private final AeronPublisher              publisher;
    private final UnsafeBuffer                txBuffer      = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE));
    private final MessageHeaderEncoder        headerEncoder = new MessageHeaderEncoder();
    private final ReferenceDataRecordEncoder  refEncoder    = new ReferenceDataRecordEncoder();

    public ReferenceDataGateway(final AeronPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Encode and publish one reference data record.
     *
     * @param symbol            instrument symbol (max 12 chars)
     * @param exchange          source exchange enum
     * @param lotSize           minimum lot size (share count)
     * @param tickSize          minimum price increment, fixed-point 10^4
     * @param currency          3-char ISO 4217 currency code (e.g. "HKD")
     * @param constituentWeight index constituent weight, fixed-point 10^6
     */
    public void publish(
        final String   symbol,
        final Exchange exchange,
        final long     lotSize,
        final long     tickSize,
        final String   currency,
        final long     constituentWeight)
    {
        refEncoder.wrapAndApplyHeader(txBuffer, 0, headerEncoder)
            .symbol(symbol)
            .exchange(exchange)
            .lotSize(lotSize)
            .tickSize(tickSize)
            .currency(currency)
            .constituentWeight(constituentWeight);

        publisher.publish(txBuffer, 0, MSG_LENGTH);
    }

    @Override
    public void close() {
        publisher.close();
    }
}
