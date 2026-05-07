package com.arb.strategy.calculator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IndexCalculator} and {@link BasisCalculator}.
 *
 * <h3>Test 1 — Weighted-sum correctness (50 constituents)</h3>
 * Registers 50 stocks each with equal weight 1/50 = 0.02.
 * Stock {@code i} receives price {@code (i+1) * 10_000L}.
 * Expected index = (1/50) × 10_000 × Σ(1..50) = 200 × 1_275 = 255_000L.
 *
 * <h3>Test 2 — BasisCalculator arithmetic</h3>
 * Verifies basis = futurePrice − indexValue − costOfCarry.
 *
 * <h3>Test 3 — Zero-GC hot path</h3>
 * Runs 100,000 iterations of the hot path ({@link IndexCalculator#onPrice} +
 * {@link IndexCalculator#computeIndex}) after a warm-up phase and asserts
 * that the JVM GC collection count is unchanged.
 */
class IndexCalculatorTest {

    private static final int    CONSTITUENTS   = 50;
    private static final double WEIGHT         = 1.0 / CONSTITUENTS;  // 0.02
    private static final long   PRICE_SCALE    = 10_000L;

    private IndexCalculator indexCalc;
    private String[]        symbols;
    private long[]          prices;

    @BeforeEach
    void setUp() {
        indexCalc = new IndexCalculator(CONSTITUENTS);
        symbols   = new String[CONSTITUENTS];
        prices    = new long[CONSTITUENTS];

        for (int i = 0; i < CONSTITUENTS; i++) {
            symbols[i] = String.format("STOCK%02d", i);
            prices[i]  = (long)(i + 1) * PRICE_SCALE;  // 10000, 20000, …, 500000
            indexCalc.addConstituent(symbols[i], WEIGHT);
        }
    }

    @Test
    @DisplayName("50 constituents — weighted-sum index equals expected fair value")
    void testWeightedSumWithFiftyConstituents() {
        // Feed all 50 prices
        for (int i = 0; i < CONSTITUENTS; i++) {
            indexCalc.onPrice(symbols[i], prices[i]);
        }

        long index = indexCalc.computeIndex();

        // Expected: (1/50) * 10_000 * sum(1..50)
        // sum(1..50) = 50*51/2 = 1275
        // result = 200 * 1275 = 255_000
        long expected = 255_000L;
        assertEquals(expected, index,
            "Index fair value must equal the expected weighted sum of constituent prices");
    }

    @Test
    @DisplayName("Partial update — only updated symbols affect the index")
    void testPartialPriceUpdate() {
        // Set only 25 of 50 stocks; rest stay at 0
        for (int i = 0; i < 25; i++) {
            indexCalc.onPrice(symbols[i], prices[i]);
        }

        long index = indexCalc.computeIndex();

        // Expected: (1/50) * 10_000 * sum(1..25)
        // sum(1..25) = 25*26/2 = 325
        // result = 200 * 325 = 65_000
        assertEquals(65_000L, index,
            "Only updated symbols should contribute to the index");
    }

    @Test
    @DisplayName("Unknown symbol is silently ignored")
    void testUnknownSymbolIgnored() {
        for (int i = 0; i < CONSTITUENTS; i++) {
            indexCalc.onPrice(symbols[i], prices[i]);
        }
        long before = indexCalc.computeIndex();

        // Update with an unregistered symbol — must have no effect
        indexCalc.onPrice("UNKNOWN.XX", 999_999_999L);

        assertEquals(before, indexCalc.computeIndex(),
            "Unregistered symbol must not affect the index computation");
    }

    @Test
    @DisplayName("BasisCalculator: basis = futurePrice - indexValue - costOfCarry")
    void testBasisCalculation() {
        // HSI example: future at 20,100, index at 20,000, carry = 50 (all × 10^4 scale)
        long futurePrice  = 201_000_000L; // 20100.0000
        long indexValue   = 200_000_000L; // 20000.0000
        long costOfCarry  =     500_000L; //    50.0000

        BasisCalculator basisCalc = new BasisCalculator(costOfCarry);
        long basis = basisCalc.compute(futurePrice, indexValue);

        // basis = 201_000_000 - 200_000_000 - 500_000 = 500_000 (= 50.0000)
        assertEquals(500_000L, basis, "Basis must equal futurePrice - indexValue - costOfCarry");
        assertTrue(basis > 0, "Positive basis means futures premium — sell future, buy basket");
    }

    @Test
    @DisplayName("Zero-GC hot path — GC count unchanged after 100,000 iterations")
    void testZeroGcHotPath() {
        // Pre-feed all prices to get a valid index first
        for (int i = 0; i < CONSTITUENTS; i++) {
            indexCalc.onPrice(symbols[i], prices[i]);
        }

        // ── Warm up the JIT (important: eliminate JIT compilation allocations) ──
        for (int warmup = 0; warmup < 5_000; warmup++) {
            for (int i = 0; i < CONSTITUENTS; i++) {
                indexCalc.onPrice(symbols[i], prices[i]);
            }
            indexCalc.computeIndex();
        }

        // Force GC to settle before measuring
        System.gc();
        long gcBefore = totalGcCount();

        // ── Hot path: 100,000 full-index update cycles ──
        long result = 0;
        for (int iter = 0; iter < 100_000; iter++) {
            for (int i = 0; i < CONSTITUENTS; i++) {
                indexCalc.onPrice(symbols[i], prices[i]);
            }
            result = indexCalc.computeIndex();
        }

        long gcAfter = totalGcCount();

        // Ensure result is used so JIT cannot eliminate the loop
        assertEquals(255_000L, result, "Index value must remain correct during hot path");

        assertEquals(gcBefore, gcAfter,
            "GC must not trigger during the zero-allocation hot path. " +
            "GC count before=" + gcBefore + " after=" + gcAfter);
    }

    /** Returns the total number of GC collections across all collectors. */
    private static long totalGcCount() {
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        long total = 0;
        for (GarbageCollectorMXBean bean : beans) {
            long count = bean.getCollectionCount();
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }
}
