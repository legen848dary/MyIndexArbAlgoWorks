package com.arb.gambit;

import com.arb.gambit.realtime.EtfDefinition;
import com.arb.gambit.realtime.NavCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NavCalculator — zero-GC hot-path correctness tests.
 *
 * <p>Expected NAV calculation (all values at scale 10^4):
 * <pre>
 *   sharesPerUnit  = [10, 20, 5]
 *   prices         = [200_0000, 100_0000, 400_0000]  (HK$200, HK$100, HK$400)
 *   cashComponent  = 0
 *   sharesOutstand = 1000
 *
 *   sum = 10×2_000_000 + 20×1_000_000 + 5×4_000_000
 *       = 20_000_000  + 20_000_000   + 20_000_000 = 60_000_000
 *   NAV = 60_000_000 / 1000 = 60_000  (= HK$6.00 at scale 10^4)
 * </pre>
 */
class NavCalculatorTest {

    private static final long EXPECTED_NAV = 60_000L;

    @Test
    void computeNav_basicBasket_correctResult() {
        final EtfDefinition def = new EtfDefinition(
            new String[]{"0005.HK", "0700.HK", "0388.HK"},
            new long[]{10L, 20L, 5L},
            0L,
            1_000L
        );
        final long[] prices = {2_000_000L, 1_000_000L, 4_000_000L};

        final long nav = NavCalculator.computeNav(def, prices);

        assertEquals(EXPECTED_NAV, nav, "NAV must equal 60_000 (HK$6.00 at scale 10^4)");
    }

    @Test
    void computeNav_withCashComponent_correctResult() {
        // Adding HK$1.00 cash component per unit = 10_000 at scale 10^4
        final EtfDefinition def = new EtfDefinition(
            new String[]{"0005.HK", "0700.HK", "0388.HK"},
            new long[]{10L, 20L, 5L},
            10_000L,  // HK$1.00 per unit
            1_000L
        );
        final long[] prices = {2_000_000L, 1_000_000L, 4_000_000L};

        // Expected: (60_000_000 + 10_000) / 1000 = 60_010
        final long nav = NavCalculator.computeNav(def, prices);
        assertEquals(60_010L, nav);
    }

    /**
     * Zero-GC verification: run 100,000 iterations; if GC fires significantly
     * (observable via JVM logs in CI), this indicates a regression.
     * In prototype mode we assert correctness only; a full HFT build would gate on
     * JVM GC pause metrics from {@code -Xlog:gc:}.
     */
    @Test
    void computeNav_hundredThousandIterations_noExceptions() {
        final EtfDefinition def = new EtfDefinition(
            new String[]{"0005.HK", "0700.HK", "0388.HK"},
            new long[]{10L, 20L, 5L},
            0L,
            1_000L
        );
        final long[] prices = {2_000_000L, 1_000_000L, 4_000_000L};

        long result = 0;
        for (int i = 0; i < 100_000; i++) {
            result = NavCalculator.computeNav(def, prices);
        }
        assertEquals(EXPECTED_NAV, result);
    }
}
