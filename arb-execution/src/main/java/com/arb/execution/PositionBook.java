package com.arb.execution;

import org.agrona.collections.Object2LongHashMap;

/**
 * Tracks net position per symbol for pre-trade position-limit checks.
 *
 * <p>Uses Agrona {@link Object2LongHashMap} (open-addressing, primitive long values — no boxing)
 * for zero-GC hot-path reads.
 *
 * <p>NOT thread-safe — call from the single execution thread only.
 */
public final class PositionBook {

    private static final long MISSING = Long.MIN_VALUE;

    private final Object2LongHashMap<String> positions;

    public PositionBook(final int initialCapacity) {
        positions = new Object2LongHashMap<>(initialCapacity, 0.65f, MISSING);
    }

    /**
     * Returns the current net position for the symbol (0 if unknown).
     */
    public long getPosition(final String symbol) {
        final long v = positions.getValue(symbol);
        return v == MISSING ? 0L : v;
    }

    /**
     * Applies a position delta (positive = long, negative = short).
     */
    public void applyDelta(final String symbol, final long delta) {
        final long current = getPosition(symbol);
        positions.put(symbol, current + delta);
    }

    /**
     * Returns true if applying {@code delta} would keep the absolute net position
     * within {@code maxNetPositionLots}.
     */
    public boolean isWithinLimit(final String symbol, final long delta, final long maxNetPositionLots) {
        final long proposed = getPosition(symbol) + delta;
        return Math.abs(proposed) <= maxNetPositionLots;
    }
}
