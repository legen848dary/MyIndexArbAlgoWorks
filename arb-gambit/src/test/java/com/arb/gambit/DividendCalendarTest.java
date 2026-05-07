package com.arb.gambit;

import com.arb.gambit.model.DividendCalendar;
import com.arb.gambit.model.DividendRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DividendCalendar — warm-path → hot-path AtomicLong bridge tests.
 *
 * <h3>Verifies</h3>
 * <ol>
 *   <li>PV is computed correctly for dividends within the expiry window.</li>
 *   <li>Dividends past expiry are excluded.</li>
 *   <li>Expired dividends (ex-date in the past) are excluded.</li>
 *   <li>The {@code setRelease}/{@code getAcquire} bridge produces the written value
 *       in the same thread (single-threaded correctness; memory ordering is
 *       guaranteed by JMM in same-thread context).</li>
 * </ol>
 */
class DividendCalendarTest {

    private static final long TODAY_EPOCH  = LocalDate.of(2025, 1, 1).toEpochDay();
    private static final long EXPIRY_EPOCH = LocalDate.of(2025, 2, 28).toEpochDay(); // 58 days
    private static final int  RATE_BPS     = 250; // 2.5%

    // ── PV calculation ────────────────────────────────────────────────────────

    @Test
    void recalculate_singleDividendInWindow_pvIsPositive() {
        final AtomicLong bridge = new AtomicLong(0L);
        final DividendCalendar cal = new DividendCalendar(bridge);

        // Constituent goes ex 30 days from today — within 58-day expiry window
        final DividendRecord[] records = {
            new DividendRecord("0005.HK",
                TODAY_EPOCH + 30,
                50_000L)  // HK$5.00 gross per share at scale 10^4
        };
        final long[] sharesPerUnit = {100L};

        cal.recalculate(records, sharesPerUnit, RATE_BPS, TODAY_EPOCH, EXPIRY_EPOCH);

        final long pv = bridge.getAcquire();
        // grossPv = 50_000 × 100 = 5_000_000
        // discount = 5_000_000 × 250 × 30 / (10_000 × 365) = 37_500_000_000 / 3_650_000 ≈ 10_273
        // PV ≈ 5_000_000 - 10_273 = 4_989_726
        assertTrue(pv > 4_900_000L && pv < 5_000_000L,
            "PV should be close to 5_000_000 but slightly below due to discounting, got: " + pv);
    }

    @Test
    void recalculate_dividendAfterExpiry_excluded() {
        final AtomicLong bridge = new AtomicLong(0L);
        final DividendCalendar cal = new DividendCalendar(bridge);

        // Ex-date is after futures expiry — must be excluded
        final DividendRecord[] records = {
            new DividendRecord("0700.HK",
                EXPIRY_EPOCH + 1,
                50_000L)
        };
        final long[] sharesPerUnit = {100L};

        cal.recalculate(records, sharesPerUnit, RATE_BPS, TODAY_EPOCH, EXPIRY_EPOCH);

        assertEquals(0L, bridge.getAcquire(), "Dividend after expiry must not contribute to PV");
    }

    @Test
    void recalculate_dividendAlreadyPast_excluded() {
        final AtomicLong bridge = new AtomicLong(0L);
        final DividendCalendar cal = new DividendCalendar(bridge);

        // Ex-date was yesterday — already past, must be excluded
        final DividendRecord[] records = {
            new DividendRecord("0388.HK",
                TODAY_EPOCH - 1,
                50_000L)
        };
        final long[] sharesPerUnit = {100L};

        cal.recalculate(records, sharesPerUnit, RATE_BPS, TODAY_EPOCH, EXPIRY_EPOCH);

        assertEquals(0L, bridge.getAcquire(), "Past dividend must not contribute to PV");
    }

    @Test
    void recalculate_pastExpiry_bridgeSetToZero() {
        // When daysToExpiry <= 0, bridge must be set to 0 (contract expired)
        final AtomicLong bridge = new AtomicLong(999_999L);  // seed non-zero
        final DividendCalendar cal = new DividendCalendar(bridge);

        cal.recalculate(new DividendRecord[0], new long[0], RATE_BPS, EXPIRY_EPOCH + 1, EXPIRY_EPOCH);

        assertEquals(0L, bridge.getAcquire(), "Bridge must be reset to 0 after contract expiry");
    }

    /**
     * Verifies setRelease/getAcquire bridge contract in same thread.
     * In a multi-threaded context the JMM guarantees visibility via the acquire-release fence.
     */
    @Test
    void atomicLongBridge_setRelease_getAcquire_sameValue() {
        final AtomicLong bridge = new AtomicLong(0L);

        final long sentinel = 12_345_678L;
        bridge.setRelease(sentinel);

        assertEquals(sentinel, bridge.getAcquire(),
            "getAcquire() must observe the value written by setRelease() in same thread");
    }
}
