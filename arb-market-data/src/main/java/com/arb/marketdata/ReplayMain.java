package com.arb.marketdata;

import com.arb.common.Channels;
import com.arb.common.aeron.AeronPublisher;
import com.arb.marketdata.replay.ReplayEngine;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;

/**
 * Entry point for replay mode.
 * Usage: java -cp app.jar com.arb.marketdata.ReplayMain [--scenario &lt;name&gt;] [--speed &lt;N&gt;]
 *
 * Defaults: scenario=hkex-basis-arb-win, speed=1.0
 */
public final class ReplayMain {

    private static final String AERON_DIR = "/dev/shm/aeron";

    public static void main(final String[] args) throws Exception {
        String scenario = "hkex-basis-arb-win";
        double speed    = 1.0;

        for (int i = 0; i < args.length - 1; i++) {
            if ("--scenario".equals(args[i])) scenario = args[i + 1];
            if ("--speed".equals(args[i]))    speed    = Double.parseDouble(args[i + 1]);
        }

        System.out.printf("[replay] Scenario: %s  Speed: %.1fx%n", scenario, speed);

        // 1. Embedded MediaDriver
        final MediaDriver.Context driverCtx = new MediaDriver.Context()
            .aeronDirectoryName(AERON_DIR)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);
        final MediaDriver driver = MediaDriver.launch(driverCtx);

        // 2. Aeron client
        final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(AERON_DIR));

        // 3. Publishers
        final AeronPublisher mdPub = new AeronPublisher(
            aeron.addPublication(Channels.CHANNEL, Channels.MARKET_DATA_STREAM));
        final AeronPublisher fvPub = new AeronPublisher(
            aeron.addPublication(Channels.CHANNEL, Channels.FV_STREAM));

        // 4. Shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(() -> {
            aeron.close();
            driver.close();
        }));

        // 5. Run replay
        final ReplayEngine engine = new ReplayEngine(mdPub, fvPub, speed);
        engine.replayClasspath("scenarios/" + scenario + ".jsonl");

        // 6. Keep alive briefly after replay so downstream services can process final messages
        Thread.sleep(5_000);
        System.exit(0);
    }
}
