package com.arb.strategy.calculator;

import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Object2IntHashMap;

/**
 * Zero-GC weighted-sum index calculator.
 *
 * <p>Constituents are registered once at startup (warm path).
 * The hot path ({@link #onPrice} + {@link #computeIndex}) performs only array reads/writes
 * and a single hash-map lookup — no heap allocation.
 *
 * <p>All prices and the returned index value use fixed-point scale 10^4
 * (matching {@code PriceNormalizer.SCALE}).
 * Weights are stored at scale 10^6 so that the division in {@link #computeIndex}
 * eliminates the weight scale without introducing floating-point arithmetic.
 *
 * <p>{@link Int2ObjectHashMap} (Agrona) is used as the backing store for constituent metadata,
 * satisfying the zero-GC collections requirement.
 */
public final class IndexCalculator {

    /** Scale applied to constituent weights internally (10^6). */
    static final long WEIGHT_SCALE = 1_000_000L;

    /** Metadata for a single index constituent. Pre-allocated at registration time. */
    public static final class ConstituentData {
        public final String symbol;
        public final int    id;
        public final long   weight; // WEIGHT_SCALE units

        ConstituentData(final String symbol, final int id, final long weight) {
            this.symbol = symbol;
            this.id     = id;
            this.weight = weight;
        }
    }

    /** Agrona map: constituent int-id → ConstituentData. */
    private final Int2ObjectHashMap<ConstituentData> constituents;

    /** Fast symbol-string → constituent-id lookup (zero-alloc getValue). */
    private final Object2IntHashMap<String> symbolToId;

    /** Pre-allocated parallel arrays — hot-path price storage. */
    private final long[] weights; // indexed by constituent id
    private final long[] prices;  // indexed by constituent id

    private int size = 0;

    /**
     * @param capacity maximum number of index constituents (pre-allocates all backing arrays)
     */
    public IndexCalculator(final int capacity) {
        constituents = new Int2ObjectHashMap<>(capacity * 2, 0.6f);
        symbolToId   = new Object2IntHashMap<>(capacity * 2, 0.6f, -1);
        weights      = new long[capacity];
        prices       = new long[capacity];
    }

    /**
     * Register a constituent. Called once per symbol during warm-up — NOT on the hot path.
     *
     * @param symbol instrument symbol (must match symbols published by feed handlers)
     * @param weight fractional portfolio weight, e.g. 0.05 for 5%
     */
    public void addConstituent(final String symbol, final double weight) {
        final int  id = size++;
        final long w  = Math.round(weight * WEIGHT_SCALE);
        constituents.put(id, new ConstituentData(symbol, id, w));
        symbolToId.put(symbol, id);
        weights[id] = w;
    }

    /**
     * Update the latest price for a symbol. Zero-allocation hot path.
     *
     * @param symbol instrument symbol
     * @param price  fixed-point price (scale 10^4)
     */
    public void onPrice(final String symbol, final long price) {
        final int id = symbolToId.getValue(symbol);
        if (id >= 0) {
            prices[id] = price;
        }
    }

    /**
     * Compute the current weighted-sum index value. Zero-allocation hot path.
     *
     * @return index value in fixed-point scale 10^4
     */
    public long computeIndex() {
        long sum = 0L;
        for (int i = 0; i < size; i++) {
            sum += prices[i] * weights[i];
        }
        return sum / WEIGHT_SCALE;
    }

    /** @return read-only view of constituent metadata (for diagnostics, not hot path) */
    public Int2ObjectHashMap<ConstituentData> getConstituents() {
        return constituents;
    }

    public int size() {
        return size;
    }
}
