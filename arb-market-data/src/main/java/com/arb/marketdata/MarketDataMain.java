package com.arb.marketdata;

import com.arb.common.Channels;
import com.arb.common.aeron.AeronPublisher;
import com.arb.marketdata.gateway.MarketDataGateway;
import com.arb.marketdata.handler.CsiFeedHandler;
import com.arb.marketdata.handler.HkexFeedHandler;
import com.arb.marketdata.handler.TaifexFeedHandler;
import com.arb.marketdata.sim.LiveArbSimulator;
import com.arb.marketdata.sim.SimProfile;
import com.arb.marketdata.sim.SimulationController;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;

/**
 * Entry point for the arb-market-data service.
 *
 * <p>Launches the embedded Aeron MediaDriver, initialises the {@link LiveArbSimulator}
 * with the default profile ({@link SimProfile#HKEX_BASIS_ARB}), and starts the simulation
 * automatically. The {@link SimulationController} subscribes to CONTROL_STREAM (1004) for
 * runtime profile changes and start/stop commands from the dashboard.
 *
 * <h3>Arb cycle (per profile, 13 s)</h3>
 * STEADY (5s) → ARB_RAMP (3s) → ARB_WINDOW (3s, ~60 BPS basis) → CONVERGENCE (2s)
 */
public final class MarketDataMain {

    private static final String AERON_DIR = "/dev/shm/aeron";

    public static void main(final String[] args) throws InterruptedException {
        // 1. Embedded MediaDriver
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .aeronDirectoryName(AERON_DIR)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);
        final MediaDriver mediaDriver = MediaDriver.launch(driverCtx);
        System.out.println("[market-data] MediaDriver launched at " + AERON_DIR);

        // 2. Aeron client
        final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(AERON_DIR));

        // 3. Publishers
        final AeronPublisher mdPublisher = new AeronPublisher(
            aeron.addPublication(Channels.CHANNEL, Channels.MARKET_DATA_STREAM));
        final AeronPublisher fvPublisher = new AeronPublisher(
            aeron.addPublication(Channels.CHANNEL, Channels.FV_STREAM));

        // 4. Market data gateway + feed handlers
        final MarketDataGateway mdGateway = new MarketDataGateway(mdPublisher);
        final HkexFeedHandler   hkex      = new HkexFeedHandler(mdGateway);
        final TaifexFeedHandler taifex    = new TaifexFeedHandler(mdGateway);
        final CsiFeedHandler    csi       = new CsiFeedHandler(mdGateway);

        // 5. Simulation engine (auto-starts with HKEX_BASIS_ARB)
        final LiveArbSimulator simulator = new LiveArbSimulator(
            SimProfile.HKEX_BASIS_ARB, mdGateway, hkex, taifex, csi, fvPublisher);

        // 6. Simulation controller subscribes to CONTROL_STREAM
        final SimulationController controller = new SimulationController(aeron, simulator);

        // 7. Shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(() -> {
            System.out.println("[market-data] Shutting down...");
            controller.close();
            aeron.close();
            mediaDriver.close();
        }));

        // 8. Auto-start simulation with default profile
        System.out.println("[market-data] Auto-starting simulation with profile HKEX_BASIS_ARB...");
        Thread.ofPlatform().name("sim-thread").start(simulator);

        // 9. Main loop: poll control commands every 50ms
        System.out.println("[market-data] Control loop running — waiting for commands on CONTROL_STREAM...");
        while (!Thread.currentThread().isInterrupted()) {
            controller.poll();
            Thread.sleep(50);
        }
    }
}
