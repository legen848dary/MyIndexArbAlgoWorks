package com.arb.gateway;

import com.arb.common.aeron.AeronPublisher;
import com.arb.sbe.*;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * Publishes control commands to {@code CONTROL_STREAM} (1004) as {@code SystemEvent} SBE messages.
 * Commands from the frontend are serialised into the 128-char {@code message} field.
 *
 * <p>Example commands: {@code EMERGENCY_HALT}, {@code START_STRATEGY:HkexBasisArb}
 */
public final class AeronControlPublisher {

    private static final int BUFFER_SIZE = 256;

    private final AeronPublisher       publisher;
    private final UnsafeBuffer         txBuffer     = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final SystemEventEncoder   eventEncoder  = new SystemEventEncoder();

    public AeronControlPublisher(final AeronPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Publish a control command as an INFO {@link SystemEvent}.
     *
     * @param command command text (max 128 chars; e.g. "EMERGENCY_HALT" or "START_STRATEGY:HkexBasisArb")
     */
    public void sendCommand(final String command) {
        eventEncoder.wrapAndApplyHeader(txBuffer, 0, headerEncoder)
            .eventType(EventType.INFO)
            .timestamp(System.nanoTime())
            .message(command);
        final int msgLen = MessageHeaderEncoder.ENCODED_LENGTH + SystemEventEncoder.BLOCK_LENGTH;
        publisher.publish(txBuffer, 0, msgLen);
    }
}
