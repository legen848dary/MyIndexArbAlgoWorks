package com.arb.marketdata.sim;

import com.arb.common.aeron.AeronPublisher;
import com.arb.marketdata.gateway.MarketDataGateway;
import com.arb.marketdata.handler.CsiFeedHandler;
import com.arb.marketdata.handler.HkexFeedHandler;
import com.arb.marketdata.handler.TaifexFeedHandler;
import com.arb.sbe.Exchange;
import com.arb.sbe.FvUpdateEncoder;
import com.arb.sbe.MessageHeaderEncoder;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates synthetic market data with deliberate arbitrage windows every ~13 seconds.
 *
 * <h3>Cycle (8s total per profile tick, 100ms tick rate = 80 ticks/cycle)</h3>
 * <ul>
 *   <li>STEADY (20 ticks / 2s): normal random walk, basis ≈ 0</li>
 *   <li>ARB_RAMP (20 ticks / 2s): basis linearly increases to targetBps</li>
 *   <li>ARB_WINDOW (25 ticks / 2.5s): basis held at targetBps — strategy fires here</li>
 *   <li>CONVERGENCE (15 ticks / 1.5s): basis linearly returns to 0</li>
 * </ul>
 *
 * <p>The annualisedBasisBps field (scale 10²) reaches {@code targetBps100 = 6000} (60.00 BPS)
 * during ARB_WINDOW, far above the strategy threshold of 1000 (10.00 BPS).
 */
public final class LiveArbSimulator implements Runnable {

    // Cycle structure in 100ms ticks — total 8 seconds
    private static final int STEADY_TICKS      = 20;
    private static final int RAMP_TICKS        = 20;
    private static final int WINDOW_TICKS      = 25;
    private static final int CONVERGENCE_TICKS = 15;
    private static final int CYCLE_TICKS       = STEADY_TICKS + RAMP_TICKS + WINDOW_TICKS + CONVERGENCE_TICKS;

    // Target basis during ARB_WINDOW (60 BPS in scale 10²)
    private static final long TARGET_BPS100 = 6_000L;

    // Carry factor: 2.5% HIBOR × 30/365
    private static final double CARRY_FACTOR = 1.0 + (0.025 * 30.0 / 365.0);

    private final MarketDataGateway  mdGateway;
    private final HkexFeedHandler    hkex;
    private final TaifexFeedHandler  taifex;
    private final CsiFeedHandler     csi;
    private final AeronPublisher     fvPublisher;

    // SBE flyweights (pre-allocated, zero-GC)
    private final UnsafeBuffer         fvTxBuf      = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    private final MessageHeaderEncoder fvHdrEncoder = new MessageHeaderEncoder();
    private final FvUpdateEncoder      fvEncoder    = new FvUpdateEncoder();

    // Running price state
    private double hsiPrice    = 19_000.0;
    private double mhiPrice    = 19_000.0;
    private double etf50Price  =    160.0;
    private double csiPrice    =  3_800.0;
    private double tsmc0Price  =    950.0;
    private double tencent0P   =    350.0;
    private double hsbc0P      =     62.0;

    private volatile SimProfile activeProfile;
    private volatile boolean    running = false;

    public LiveArbSimulator(final SimProfile initialProfile,
                            final MarketDataGateway mdGateway,
                            final HkexFeedHandler hkex,
                            final TaifexFeedHandler taifex,
                            final CsiFeedHandler csi,
                            final AeronPublisher fvPublisher) {
        this.activeProfile = initialProfile;
        this.mdGateway     = mdGateway;
        this.hkex          = hkex;
        this.taifex        = taifex;
        this.csi           = csi;
        this.fvPublisher   = fvPublisher;
    }

    public void setProfile(final SimProfile p) {
        this.activeProfile = p;
        System.out.printf("[SIM] Profile changed to %s%n", p.name());
    }

    public SimProfile getActiveProfile() {
        return activeProfile;
    }

    @Override
    public void run() {
        running = true;
        System.out.printf("[SIM] LiveArbSimulator started with profile %s%n", activeProfile.name());
        final ThreadLocalRandom rng = ThreadLocalRandom.current();
        int cycleTick = 0;

        while (running && !Thread.currentThread().isInterrupted()) {
            cycleTick = (cycleTick + 1) % CYCLE_TICKS;

            // Determine current phase and the artificial basis offset
            final long basisBps100;
            final String phase;
            if (cycleTick < STEADY_TICKS) {
                basisBps100 = 0L;
                phase = "STEADY";
            } else if (cycleTick < STEADY_TICKS + RAMP_TICKS) {
                final int rampTick = cycleTick - STEADY_TICKS;
                basisBps100 = TARGET_BPS100 * rampTick / RAMP_TICKS;
                phase = "ARB_RAMP";
            } else if (cycleTick < STEADY_TICKS + RAMP_TICKS + WINDOW_TICKS) {
                basisBps100 = TARGET_BPS100;
                phase = "ARB_WINDOW";
                if ((cycleTick - STEADY_TICKS - RAMP_TICKS) == 0) {
                    System.out.printf("[SIM] *** ARB WINDOW OPEN *** profile=%s basisBps=%.2f (>10.00 threshold) — strategies should fire!%n",
                        activeProfile.name(), TARGET_BPS100 / 100.0);
                }
            } else {
                final int convTick = cycleTick - STEADY_TICKS - RAMP_TICKS - WINDOW_TICKS;
                basisBps100 = TARGET_BPS100 * (CONVERGENCE_TICKS - convTick) / CONVERGENCE_TICKS;
                phase = "CONVERGENCE";
                if (convTick == 0) {
                    System.out.printf("[SIM] *** ARB WINDOW CLOSED *** profile=%s — basis converging back to 0%n",
                        activeProfile.name());
                }
            }

            // Random-walk all prices ±0.05% per tick
            hsiPrice   = vary(hsiPrice,   0.0005, rng);
            mhiPrice   = vary(mhiPrice,   0.0005, rng);
            etf50Price = vary(etf50Price, 0.0005, rng);
            csiPrice   = vary(csiPrice,   0.0005, rng);
            tsmc0Price = vary(tsmc0Price, 0.0005, rng);
            tencent0P  = vary(tencent0P,  0.0005, rng);
            hsbc0P     = vary(hsbc0P,     0.0005, rng);

            // Publish market data ticks
            hkex.onTick("HSI.HK",    hsiPrice);
            hkex.onTick("MHI.HK",    mhiPrice);
            taifex.onTick("0050.TW", etf50Price);
            csi.onTick("CSI300.CN",  csiPrice);

            // Publish constituent ticks
            hkex.onTick("0700.HK", tencent0P);
            hkex.onTick("0005.HK", hsbc0P);
            hkex.onTick("0941.HK", vary(58.0,  0.0005, rng));
            hkex.onTick("0388.HK", vary(220.0, 0.0005, rng));
            hkex.onTick("1299.HK", vary(63.0,  0.0005, rng));
            hkex.onTick("2318.HK", vary(41.0,  0.0005, rng));
            hkex.onTick("0939.HK", vary(5.5,   0.0005, rng));
            hkex.onTick("1398.HK", vary(4.2,   0.0005, rng));
            hkex.onTick("0883.HK", vary(12.5,  0.0005, rng));
            hkex.onTick("1113.HK", vary(45.0,  0.0005, rng));
            taifex.onTick("2330.TW", tsmc0Price);
            taifex.onTick("2317.TW", vary(160.0, 0.0005, rng));
            taifex.onTick("2454.TW", vary(350.0, 0.0005, rng));

            // Publish FvUpdate every 2 ticks
            if (cycleTick % 2 == 0) {
                publishFvWithBasis("HSI.HK",    Exchange.HKEX,   (long)(hsiPrice   * 10_000.0), basisBps100, 0L);
                publishFvWithBasis("0050.TW",   Exchange.TAIFEX, (long)(etf50Price * 10_000.0), basisBps100, (long)(etf50Price * 10_000.0));
                publishFvWithBasis("CSI300.CN", Exchange.CSI,    (long)(csiPrice   * 10_000.0), basisBps100 / 2L, 0L);
            }

            try {
                Thread.sleep(100);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("[SIM] LiveArbSimulator stopped.");
    }

    public void stop() {
        running = false;
    }

    /**
     * Publishes an FvUpdate with an artificial basis offset applied during arb windows.
     *
     * <p>The {@code extraBps100} parameter injects the arb opportunity:
     * a positive value means futures are trading at a PREMIUM above FV by that many BPS.
     *
     * @param symbol        instrument symbol
     * @param exchange      source exchange
     * @param spotScaled4   spot price scaled by 10^4
     * @param extraBps100   artificial extra basis in BPS × 100 (0 = fair, 6000 = 60 BPS premium)
     * @param navScaled4    NAV per unit (for ETFs; 0 if futures)
     */
    private void publishFvWithBasis(
        final String symbol,
        final Exchange exchange,
        final long spotScaled4,
        final long extraBps100,
        final long navScaled4)
    {
        final long futuresFv = (long)(spotScaled4 * CARRY_FACTOR);

        // basis (scale 10^4) = extraBps100 × spot / (10_000 × 100)
        final long basis = extraBps100 * spotScaled4 / (10_000L * 100L);

        // annualisedBasisBps100 = extraBps100 directly (scale 10²)
        final long annualisedBasisBps100 = extraBps100;

        final long effectiveNav = navScaled4 > 0 ? navScaled4 : 0L;

        final int msgLen = (int) fvEncoder.wrapAndApplyHeader(fvTxBuf, 0, fvHdrEncoder)
            .symbol(symbol)
            .exchange(exchange)
            .navPerUnit(effectiveNav)
            .futuresFv(futuresFv)
            .basis(basis)
            .annualisedBasisBps(annualisedBasisBps100)
            .timestamp(System.nanoTime())
            .encodedLength() + MessageHeaderEncoder.ENCODED_LENGTH;

        fvPublisher.publish(fvTxBuf, 0, msgLen);
    }

    /** Random walk ±pct per tick, floor at 10% of initial value. */
    private static double vary(final double price, final double pct, final ThreadLocalRandom rng) {
        return Math.max(price * 0.1, price * (1.0 + (rng.nextDouble() - 0.5) * 2.0 * pct));
    }
}
