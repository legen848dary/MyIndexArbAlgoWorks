package com.arb.gambit.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Warm-path dividend present-value calculator.
 *
 * <h3>Responsibility</h3>
 * Runs on a periodic background thread (e.g. every minute or on reference-data update).
 * Computes the aggregate PV of all dividends going ex before futures expiry, using
 * continuous discounting, and publishes the result to an {@link AtomicLong} shared with
 * the hot-path {@link com.arb.gambit.realtime.FvEngine} via acquire-release ordering.
 *
 * <h3>Formula (continuous discounting)</h3>
 * <pre>
 *   PV(d_i) = grossAmount × sharesPerUnit × e^(−r × t_i)
 *
 *   Total dividend PV = Σ PV(d_i) for all records where ex-date < expiry
 * </pre>
 * All values are fixed-point 10^4.
 *
 * <h3>Threading: single-writer / single-reader acquire-release bridge</h3>
 * <pre>
 *   // warm thread writes (StoreStore fence only):
 *   dividendPv.setRelease(newPv);
 *
 *   // hot thread reads (LoadLoad fence only):
 *   long pv = dividendPv.getAcquire();
 * </pre>
 * This is cheaper than a full volatile (no StoreLoad fence) and correct for the
 * single-writer pattern. {@code LongAdder} cannot substitute — it has no {@code set()} and
 * {@code sum()} is not a point-in-time snapshot.
 */
public final class DividendCalendar {

    private final AtomicLong dividendPv;

    public DividendCalendar(final AtomicLong dividendPv) {
        this.dividendPv = dividendPv;
    }

    /**
     * Recompute aggregate dividend PV and publish via {@code setRelease()}.
     *
     * <p>All time arguments use days; continuous discounting approximation:
     * {@code e^(−r × t) ≈ 1 − r × t} for small {@code r × t} (reduces to simple discount
     * fraction, avoids {@code Math.exp()} on the warm path if high precision not required).
     * For higher precision, replace with {@code Math.exp(-rFrac * tFrac)}.
     *
     * @param records         array of dividend records for all basket constituents
     * @param sharesPerUnit   shares per ETF creation unit indexed to match symbols in records
     * @param riskFreeRateBps risk-free rate in basis points
     * @param todayEpochDays  today's epoch day ({@code LocalDate.now().toEpochDay()})
     * @param expiryEpochDays futures expiry epoch day
     */
    public void recalculate(
        final DividendRecord[] records,
        final long[]           sharesPerUnit,
        final int              riskFreeRateBps,
        final long             todayEpochDays,
        final long             expiryEpochDays)
    {
        final long daysToExpiry = expiryEpochDays - todayEpochDays;
        if (daysToExpiry <= 0) {
            dividendPv.setRelease(0L);
            return;
        }

        long totalPv = 0L;
        for (int i = 0; i < records.length; i++) {
            final DividendRecord rec = records[i];
            final long daysToEx = rec.exDateEpochDays - todayEpochDays;
            if (daysToEx <= 0 || daysToEx > daysToExpiry) continue;

            // Simple discount: PV = grossAmount × sharesPerUnit × (1 − r × t / (10000 × 365))
            // All values fixed-point 10^4
            final long grossPv = rec.grossAmountPerShareScaled4 * sharesPerUnit[i];
            final long discount = grossPv * riskFreeRateBps * daysToEx / (10_000L * 365L);
            totalPv += (grossPv - discount);
        }

        // Acquire-release write — StoreStore fence only, cheaper than full volatile
        dividendPv.setRelease(totalPv);
    }
}
