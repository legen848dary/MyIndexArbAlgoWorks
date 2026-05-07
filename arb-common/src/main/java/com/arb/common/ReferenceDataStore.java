package com.arb.common;

import org.agrona.collections.Object2ObjectHashMap;

/**
 * Zero-GC read path store for static reference data, keyed by instrument symbol.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li>Populated at startup on the feed thread (allocation-safe warm path).</li>
 *   <li>{@link #get(String)} is safe to call from the hot-path thread after population completes
 *       — Agrona {@code Object2ObjectHashMap} uses open-addressing (no {@code Entry} allocs)
 *       and the key lookup performs zero heap allocation provided the {@code symbol} String
 *       is a pre-existing reference (not freshly constructed from SBE bytes).</li>
 *   <li>NOT thread-safe for concurrent writes. Population must finish before hot path starts.</li>
 * </ul>
 */
public final class ReferenceDataStore {

    /** Immutable value object — pre-allocated at startup, referenced (not copied) on lookup. */
    public static final class RefDataEntry {
        public final long   lotSize;            // minimum lot size (share count)
        public final long   tickSize;           // min price increment, fixed-point 10^4
        public final long   constituentWeight;  // index weight, fixed-point 10^6
        public final byte[] currency;           // 3-byte ISO 4217 code (e.g. "HKD")

        public RefDataEntry(
            final long   lotSize,
            final long   tickSize,
            final long   constituentWeight,
            final byte[] currency)
        {
            this.lotSize           = lotSize;
            this.tickSize          = tickSize;
            this.constituentWeight = constituentWeight;
            this.currency          = currency.clone();
        }
    }

    // Open-addressing map: no Entry objects, zero-GC get()
    private final Object2ObjectHashMap<String, RefDataEntry> store =
        new Object2ObjectHashMap<>(128, 0.6f);

    /**
     * Store a reference data entry. Called at startup — allocation is acceptable.
     *
     * @param symbol            instrument symbol (max 12 chars)
     * @param lotSize           minimum lot size
     * @param tickSize          minimum price increment (fixed-point 10^4)
     * @param constituentWeight index constituent weight (fixed-point 10^6)
     * @param currency          3-byte ISO 4217 currency code
     */
    public void onRecord(
        final String symbol,
        final long   lotSize,
        final long   tickSize,
        final long   constituentWeight,
        final byte[] currency)
    {
        store.put(symbol, new RefDataEntry(lotSize, tickSize, constituentWeight, currency));
    }

    /**
     * Zero-GC lookup. Returns {@code null} if the symbol is not found.
     * The caller must NOT store a reference to the returned entry across calls that
     * could trigger a {@link #onRecord} on another thread.
     *
     * @param symbol pre-existing String reference (must NOT be freshly allocated on hot path)
     * @return the {@link RefDataEntry} or {@code null}
     */
    public RefDataEntry get(final String symbol) {
        return store.get(symbol);
    }

    /** Number of entries currently stored. */
    public int size() {
        return store.size();
    }
}
