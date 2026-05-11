package com.arb.strategy;

import com.arb.gambit.model.MonteCarloPositionSizer;
import com.arb.gambit.model.SpreadVolEstimator;
import com.arb.strategy.impl.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads {@code config/strategies.properties} and instantiates enabled strategies with
 * default prototype parameters.
 *
 * <h3>Warm/cold path AtomicLong bridges</h3>
 * Shared bridges are created here and injected into both the analytics components
 * ({@link SpreadVolEstimator}, {@link MonteCarloPositionSizer}) and the strategies.
 * The hot-path strategies read via {@code getAcquire()}; warm/cold paths write via
 * {@code setRelease()}.
 */
public final class StrategyRegistry {

    // ── Shared AtomicLong bridges (warm/cold → hot path) ─────────────────────
    public final AtomicLong maxLotsHkex         = new AtomicLong(10L);
    public final AtomicLong maxLotsTwse         = new AtomicLong(200L); // needs 200+ for meaningful constituent lot quantities
    public final AtomicLong maxLotsSsf          = new AtomicLong(50L);
    public final AtomicLong dividendPv          = new AtomicLong(0L);
    public final AtomicLong impliedVolBps       = new AtomicLong(2_000_000L); // 20% IV
    public final AtomicLong realisedVolBps      = new AtomicLong(1_500_000L); // 15% RV
    public final AtomicLong spreadSigmaBps100   = new AtomicLong(500L);       // 5 BPS σ
    public final AtomicLong betaScaled4         = new AtomicLong(8_000L);     // β = 0.8
    public final AtomicLong spreadMeanScaled4   = new AtomicLong(0L);
    public final AtomicLong spreadSigmaHkCn     = new AtomicLong(10_000L);    // 1.0 index pts
    public final AtomicLong fxRateHkdCnh100     = new AtomicLong(91_000L);    // 0.91 CNH/HKD × 100_000

    // ── Analytics (warm/cold path) ────────────────────────────────────────────
    public final SpreadVolEstimator      spreadVolEstimator;
    public final MonteCarloPositionSizer mcSizer;

    private final List<Strategy> enabled = new ArrayList<>();

    public StrategyRegistry(final String propertiesPath) {
        final Properties props = loadProperties(propertiesPath);

        spreadVolEstimator = new SpreadVolEstimator(spreadSigmaBps100, 20);
        mcSizer = new MonteCarloPositionSizer(maxLotsHkex, 5_000_000L, 0.20);

        if (isEnabled(props, "HkexBasisArb"))
            enabled.add(new HkexBasisArb(5_000L, 1_000L, 10L, maxLotsHkex));

        if (isEnabled(props, "MhiHsiBasisArb"))
            enabled.add(new MhiHsiBasisArb(200L));

        if (isEnabled(props, "TwseEtfArb"))
            enabled.add(new TwseEtfArb("0050.TW", 2_000L, maxLotsTwse));

        if (isEnabled(props, "CrossBorderEtfArb"))
            enabled.add(new CrossBorderEtfArb("2822.HK", 3_000L, fxRateHkdCnh100));

        if (isEnabled(props, "SsfBasisArb"))
            enabled.add(new SsfBasisArb("TSMC-SSF-TW", "2330.TW", 250, 30, dividendPv, 1_500L, maxLotsSsf));

        if (isEnabled(props, "SsfCalendarSpreadArb"))
            enabled.add(new SsfCalendarSpreadArb("TSMC-SSF-NEAR", "TSMC-SSF-FAR", 250, 30, 90, spreadSigmaBps100, 2L));

        if (isEnabled(props, "HkCnIndexPairArb"))
            enabled.add(new HkCnIndexPairArb("HSI.HK", "CSI300.CN", betaScaled4,
                spreadMeanScaled4, spreadSigmaHkCn, 20_000L, 30_000L));

        if (isEnabled(props, "VolSkewBasisArb"))
            enabled.add(new VolSkewBasisArb(5_000L, 10L, impliedVolBps, realisedVolBps, maxLotsHkex));
    }

    public List<Strategy> enabledStrategies() {
        return List.copyOf(enabled);
    }

    private static boolean isEnabled(final Properties props, final String name) {
        return Boolean.parseBoolean(props.getProperty("strategy." + name + ".enabled", "false"));
    }

    private static Properties loadProperties(final String path) {
        final Properties props = new Properties();
        try (final InputStream is = StrategyRegistry.class.getClassLoader().getResourceAsStream(path)) {
            if (is != null) props.load(is);
        } catch (final IOException e) {
            // Silently fall back to all-disabled defaults if config file is missing
        }
        return props;
    }
}
