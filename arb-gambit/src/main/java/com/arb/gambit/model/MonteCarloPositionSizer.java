package com.arb.gambit.model;

import net.finmath.exception.CalculationException;
import net.finmath.montecarlo.BrownianMotion;
import net.finmath.montecarlo.BrownianMotionFromMersenneRandomNumbers;
import net.finmath.montecarlo.assetderivativevaluation.AssetModelMonteCarloSimulationModel;
import net.finmath.montecarlo.assetderivativevaluation.MonteCarloBlackScholesModel;
import net.finmath.montecarlo.assetderivativevaluation.products.EuropeanOption;
import net.finmath.time.TimeDiscretizationFromArray;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Cold-path Monte Carlo position sizer.
 *
 * <h3>Role</h3>
 * Runs a finmath-lib Monte Carlo simulation to compute 95% VaR for a basket position
 * of given size. Translates the VaR estimate into a maximum lot count that keeps the
 * position within a configured risk budget, then publishes via {@link AtomicLong#setRelease}.
 *
 * <h3>Threading</h3>
 * Run on a scheduled cold-path executor (e.g., every 5 minutes or before market open).
 * NEVER invoke from the hot-path thread — MC simulation allocates {@code RandomVariable[]} paths.
 *
 * <h3>Hot-path read</h3>
 * Hot-path strategies read via {@code maxLots.getAcquire()} — LoadLoad fence only, no GC.
 *
 * <h3>Prototype simplification</h3>
 * Uses a Black-Scholes GBM model (log-normal index paths). A full implementation would
 * use a stochastic vol model (Heston) and historical correlation matrix for basket VaR.
 */
public final class MonteCarloPositionSizer {

    private static final int MC_PATHS       = 10_000;
    private static final int MC_STEPS       = 50;
    private static final double CONF_LEVEL  = 0.95;

    private final AtomicLong maxLots;
    private final long       riskBudgetScaled4; // max tolerable loss per lot, fixed-point 10^4
    private final double     impliedVol;        // annualised σ (e.g. 0.20 for 20%)

    public MonteCarloPositionSizer(
        final AtomicLong maxLots,
        final long       riskBudgetScaled4,
        final double     impliedVol)
    {
        this.maxLots           = maxLots;
        this.riskBudgetScaled4 = riskBudgetScaled4;
        this.impliedVol        = impliedVol;
    }

    /**
     * Run Monte Carlo VaR and publish maximum lot size via {@code setRelease()}.
     *
     * @param spotScaled4      current spot index level, fixed-point 10^4
     * @param riskFreeRate     annualised risk-free rate as fraction (e.g. 0.025)
     * @param timeToExpiryYr   time horizon in years (e.g. 30/365 ≈ 0.0822)
     */
    public void calibrate(
        final long   spotScaled4,
        final double riskFreeRate,
        final double timeToExpiryYr)
    {
        try {
            final double spot = (double) spotScaled4 / 10_000.0;
            final TimeDiscretizationFromArray timeDis =
                new TimeDiscretizationFromArray(0.0, MC_STEPS, timeToExpiryYr / MC_STEPS);

            final BrownianMotion bm =
                new BrownianMotionFromMersenneRandomNumbers(timeDis, 1, MC_PATHS, 42);

            final AssetModelMonteCarloSimulationModel model =
                new MonteCarloBlackScholesModel(spot, riskFreeRate, impliedVol, bm);

            // Use an ATM put as VaR proxy: expected loss per unit = put premium
            final EuropeanOption put = new EuropeanOption(timeToExpiryYr, spot);
            final double varPerLot   = put.getValue(model) * 10_000.0; // back to scale 10^4

            final long lots = varPerLot > 0
                ? riskBudgetScaled4 / (long) varPerLot
                : 1L;

            maxLots.setRelease(Math.max(1L, lots));
        } catch (final CalculationException e) {
            // On MC failure, fall back to conservative 1 lot — do not crash the cold path
            maxLots.setRelease(1L);
        }
    }
}
