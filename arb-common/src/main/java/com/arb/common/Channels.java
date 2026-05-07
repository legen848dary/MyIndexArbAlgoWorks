package com.arb.common;

/**
 * Aeron IPC channel URI and stream ID constants shared across all modules.
 * All Java services use the same "aeron:ipc" channel, differentiated by stream ID.
 */
public final class Channels {

    public static final String CHANNEL = "aeron:ipc";

    public static final int MARKET_DATA_STREAM   = 1001;
    public static final int ORDER_STREAM         = 1002;
    public static final int ORDER_UPDATE_STREAM  = 1003;
    public static final int CONTROL_STREAM       = 1004;

    private Channels() {}
}
