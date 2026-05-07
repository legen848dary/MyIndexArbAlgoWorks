package com.arb.gambit;

import com.arb.gambit.realtime.FuturesFvCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FuturesFvCalculator — cost-of-carry fair value correctness tests.
 *
 * <p>Reference numbers (all at scale 10^4):
 * <pre>
 *   spotIndex        = 190_000_000   (HSI 19000.00 at scale 10^4)
 *   riskFreeRateBps  = 250           (HIBOR 2.50%)
 *   daysToExpiry     = 30
 *   dividendPv       = 1_500_000     (150 index points at scale 10^4)
 *
 *   financingCost = 190_000_000 × 250 × 30 / (10_000 × 365)
 *                 = 1_425_000_000_000 / 3_650_000
 *                 = 390_410 (integer division)
 *
 *   FV = 190_000_000 + 390_410 - 1_500_000 = 188_890_410
 * </pre>
 */
class FuturesFvCalculatorTest {

    // ── computeFv ────────────────────────────────────────────────────────────

    @Test
    void computeFv_hsiReferenceCase_matchesExpected() {
        final long fv = FuturesFvCalculator.computeFv(
            190_000_000L,   // spot
            250,            // riskFreeRateBps (2.50%)
            30,             // daysToExpiry
            1_500_000L      // dividendPv
        );
        assertEquals(188_890_410L, fv,
            "FV must equal 188_890_410 (exact integer arithmetic)");
    }

    @Test
    void computeFv_zeroDividend_onlyFinancingCost() {
        // financingCost = 190_000_000 × 250 × 30 / 3_650_000 = 390_410
        final long fv = FuturesFvCalculator.computeFv(190_000_000L, 250, 30, 0L);
        assertEquals(190_000_000L + 390_410L, fv);
    }

    @Test
    void computeFv_zeroRate_onlyDividendDeducted() {
        final long fv = FuturesFvCalculator.computeFv(190_000_000L, 0, 30, 1_500_000L);
        assertEquals(190_000_000L - 1_500_000L, fv);
    }

    // ── annualisedBasisBps ────────────────────────────────────────────────────

    @Test
    void annualisedBasisBps_zerobasis_returnsZero() {
        // futureMktPrice == fv → basis = 0
        final long bps = FuturesFvCalculator.annualisedBasisBps(
            188_890_410L, 188_890_410L, 190_000_000L, 30);
        assertEquals(0L, bps);
    }

    @Test
    void annualisedBasisBps_positiveBasis_returnsPositive() {
        // futures trading 500_000 above FV (~50 index pts premium)
        final long bps = FuturesFvCalculator.annualisedBasisBps(
            189_390_410L, 188_890_410L, 190_000_000L, 30);
        // Annualised fraction = 500_000 / 190_000_000 = 0.0026315...
        // × (365/30) × 10_000 × 100 = 32017.54 → truncated to 32017
        // (= 320.17 BPS annualised at scale 10^2)
        assertEquals(32_017L, bps);
    }

    @Test
    void annualisedBasisBps_degenerateDays_returnsZero() {
        final long bps = FuturesFvCalculator.annualisedBasisBps(
            190_000_000L, 189_000_000L, 190_000_000L, 0);
        assertEquals(0L, bps, "degenerate daysToExpiry=0 must return 0 not divide-by-zero");
    }
}
