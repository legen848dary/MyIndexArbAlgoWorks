package com.arb.marketdata;

import com.arb.common.Channels;
import com.arb.common.aeron.AeronPublisher;
import com.arb.marketdata.gateway.MarketDataGateway;
import com.arb.marketdata.handler.CsiFeedHandler;
import com.arb.marketdata.handler.HkexFeedHandler;
import com.arb.marketdata.handler.TaifexFeedHandler;
import com.arb.sbe.Exchange;
import com.arb.sbe.FvUpdateEncoder;
import com.arb.sbe.MessageHeaderEncoder;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Entry point for the arb-market-data service.
 * Launches the embedded Aeron MediaDriver (the single driver for the whole system),
 * simulates synthetic market ticks for HKEX / TAIFEX / CSI, publishes MarketDataTick
 * messages to MARKET_DATA_STREAM (1001) and FvUpdate messages to FV_STREAM (1005).
 */
public final class MarketDataMain {

    private static final String AERON_DIR = "/dev/shm/aeron";

    // Instrument base prices as raw doubles (PriceNormalizer converts to fixed-point scale 10^4)
    private static final double HSI_BASE   = 19_000.0;  // HSI Index
    private static final double MHI_BASE   = 19_000.0;  // Mini-HSI
    private static final double ETF50_BASE =    160.0;  // 0050.TW (TWD)
    private static final double CSI_BASE   =  3_800.0;  // CSI 300

    // Simple FV basis: futures ≈ spot * (1 + carry) — approx 2.50% HIBOR, 30 DTE
    private static final double CARRY_FACTOR = 1.0 + (0.025 * 30.0 / 365.0);

    private static final int FV_MSG_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + FvUpdateEncoder.BLOCK_LENGTH;

    public static void main(final String[] args) throws InterruptedException {
        // 1. Embedded MediaDriver — the single IPC driver for the whole system
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .aeronDirectoryName(AERON_DIR)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);
        final MediaDriver mediaDriver = MediaDriver.launch(driverCtx);
        System.out.println("[market-data] MediaDriver launched at " + AERON_DIR);

        // 2. Aeron client connected to the embedded driver
        final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(AERON_DIR));

        // 3. Publishers
        final AeronPublisher mdPublisher = new AeronPublisher(
            aeron.addPublication(Channels.CHANNEL, Channels.MARKET_DATA_STREAM));
        final AeronPublisher fvPublisher = new AeronPublisher(
            aeron.addPublication(Channels.CHANNEL, Channels.FV_STREAM));

        // 4. MarketDataGateway + exchange feed handlers
        final MarketDataGateway mdGateway = new MarketDataGateway(mdPublisher);
        final HkexFeedHandler   hkex      = new HkexFeedHandler(mdGateway);
        final TaifexFeedHandler taifex    = new TaifexFeedHandler(mdGateway);
        final CsiFeedHandler    csi       = new CsiFeedHandler(mdGateway);

        // Pre-allocated SBE flyweights for zero-GC FvUpdate publishing
        final UnsafeBuffer         fvTxBuf       = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        final MessageHeaderEncoder fvHdrEncoder  = new MessageHeaderEncoder();
        final FvUpdateEncoder      fvEncoder     = new FvUpdateEncoder();

        // 5. Shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(() -> {
            System.out.println("[market-data] Shutting down...");
            aeron.close();
            mediaDriver.close();
        }));

        // 6. Synthetic tick simulation loop — 100 ms cadence
        System.out.println("[market-data] Starting synthetic tick simulation (100 ms/tick)...");
        final ThreadLocalRandom rng = ThreadLocalRandom.current();
        double hsiPrice   = HSI_BASE;
        double mhiPrice   = MHI_BASE;
        double etf50Price = ETF50_BASE;
        double csiPrice   = CSI_BASE;
        int    tickCount  = 0;

        while (!Thread.currentThread().isInterrupted()) {
            // Random-walk ±0.1% per tick
            hsiPrice   = vary(hsiPrice,   rng);
            mhiPrice   = vary(mhiPrice,   rng);
            etf50Price = vary(etf50Price, rng);
            csiPrice   = vary(csiPrice,   rng);

            // Publish MarketDataTick messages via feed handlers
            hkex.onTick("HSI.HK",    hsiPrice);
            hkex.onTick("MHI.HK",    mhiPrice);
            taifex.onTick("0050.TW", etf50Price);
            csi.onTick("CSI300.CN",  csiPrice);

            // Publish FvUpdate every 5 ticks (FV changes slower than raw market data)
            if (++tickCount % 5 == 0) {
                publishFv(fvTxBuf, fvHdrEncoder, fvEncoder, fvPublisher,
                    "HSI.HK",    Exchange.HKEX,   (long) (hsiPrice   * 10_000.0));
                publishFv(fvTxBuf, fvHdrEncoder, fvEncoder, fvPublisher,
                    "0050.TW",   Exchange.TAIFEX, (long) (etf50Price * 10_000.0));
                publishFv(fvTxBuf, fvHdrEncoder, fvEncoder, fvPublisher,
                    "CSI300.CN", Exchange.CSI,    (long) (csiPrice   * 10_000.0));
            }

            Thread.sleep(100);
        }
    }

    /** Vary price by ±0.1% per tick (random walk). */
    private static double vary(final double price, final ThreadLocalRandom rng) {
        return Math.max(price * (1.0 + (rng.nextDouble() - 0.5) * 0.002), 1.0);
    }

    /** Encode and publish a simplified FvUpdate (FV = spot × carry factor). */
    private static void publishFv(
        final UnsafeBuffer fvTxBuf,
        final MessageHeaderEncoder hdrEncoder,
        final FvUpdateEncoder encoder,
        final AeronPublisher publisher,
        final String symbol,
        final Exchange exchange,
        final long spotScaled4)
    {
        final long fv        = (long) (spotScaled4 * CARRY_FACTOR);
        final long basis     = spotScaled4 - fv;
        final long basisBps  = basis * 10_000L / Math.max(spotScaled4, 1L);

        encoder.wrapAndApplyHeader(fvTxBuf, 0, hdrEncoder)
            .symbol(symbol)
            .exchange(exchange)
            .navPerUnit(0L)
            .futuresFv(fv)
            .basis(basis)
            .annualisedBasisBps(basisBps)
            .timestamp(System.nanoTime());

        publisher.publish(fvTxBuf, 0, FV_MSG_LENGTH);
    }
}
