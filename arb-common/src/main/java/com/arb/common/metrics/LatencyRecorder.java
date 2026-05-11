package com.arb.common.metrics;
import java.util.Arrays;

/**
 * Zero-GC latency histogram recorder for hot-path measurement.
 * All methods are alloc-free. Only {@link #countsSnapshot()} clones (called off hot thread).
 *
 * Bucket boundaries (ns): &lt;1000 | &lt;5000 | &lt;10000 | &lt;50000 | &lt;100000 | &lt;500000 | else
 */
public final class LatencyRecorder {
    private static final long[] UPPER_NS = {1_000L, 5_000L, 10_000L, 50_000L, 100_000L, 500_000L};
    static final int BUCKET_COUNT = 7;

    private final long[] counts  = new long[BUCKET_COUNT];
    private long minNs   = Long.MAX_VALUE;
    private long maxNs   = 0L;
    private long totalNs = 0L;
    private long n       = 0L;

    /** Record a single latency sample. Zero-GC. */
    public void record(final long nanos) {
        if (nanos < 0L) return;
        n++;
        totalNs += nanos;
        if (nanos < minNs) minNs = nanos;
        if (nanos > maxNs) maxNs = nanos;
        for (int i = 0; i < UPPER_NS.length; i++) {
            if (nanos < UPPER_NS[i]) { counts[i]++; return; }
        }
        counts[BUCKET_COUNT - 1]++;
    }

    public long sampleCount() { return n; }
    public long minNs()       { return n == 0 ? 0L : minNs; }
    public long maxNs()       { return maxNs; }
    public long avgNs()       { return n == 0 ? 0L : totalNs / n; }
    public long[] counts()    { return counts; }

    /** Snapshot (allocates — call only from reporter thread, never from hot path). */
    public long[] countsSnapshot() { return counts.clone(); }

    /** Reset all accumulators. Call after publishing a snapshot. */
    public void reset() {
        Arrays.fill(counts, 0L);
        minNs = Long.MAX_VALUE; maxNs = 0L; totalNs = 0L; n = 0L;
    }
}
