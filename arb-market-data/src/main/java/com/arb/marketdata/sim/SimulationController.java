package com.arb.marketdata.sim;

import com.arb.common.Channels;
import com.arb.common.aeron.AeronSubscriber;
import com.arb.sbe.MessageHeaderDecoder;
import com.arb.sbe.SystemEventDecoder;
import io.aeron.Aeron;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;

import java.nio.charset.StandardCharsets;

/**
 * Subscribes to CONTROL_STREAM (1004) and controls the {@link LiveArbSimulator}.
 * Commands arrive as {@code SystemEvent} SBE messages with the command in the {@code message} field.
 *
 * <p>Supported commands:
 * <ul>
 *   <li>{@code START_SIMULATION} — start with default profile</li>
 *   <li>{@code START_SIMULATION:HKEX_BASIS_ARB} — start with named profile</li>
 *   <li>{@code STOP_SIMULATION} — stop simulation</li>
 *   <li>{@code SET_PROFILE:TWSE_ETF_ARB} — change profile (restarts cycle)</li>
 * </ul>
 */
public final class SimulationController implements AutoCloseable {

    private static final int MSG_LEN = 128;

    private final AeronSubscriber  controlSub;
    private final LiveArbSimulator simulator;

    // SBE flyweights
    private final MessageHeaderDecoder hdrDecoder = new MessageHeaderDecoder();
    private final SystemEventDecoder   evtDecoder = new SystemEventDecoder();
    private final byte[]               msgBuf     = new byte[MSG_LEN];

    private Thread simThread = null;

    public SimulationController(final Aeron aeron, final LiveArbSimulator simulator) {
        this.simulator  = simulator;
        this.controlSub = new AeronSubscriber(
            aeron.addSubscription(Channels.CHANNEL, Channels.CONTROL_STREAM));
        System.out.println("[sim-ctrl] Subscribed to CONTROL_STREAM (1004)");
    }

    /** Start the simulation using the simulator's current active profile. Call this instead
     *  of manually starting the sim thread so the controller can track and stop it later. */
    public void autoStart() {
        startSimulation(simulator.getActiveProfile());
    }

    /** Poll for control commands (call from the main loop). */
    public void poll() {
        controlSub.poll(this::onFragment);
    }

    private void onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header) {
        hdrDecoder.wrap(buffer, offset);
        if (hdrDecoder.templateId() != SystemEventDecoder.TEMPLATE_ID) return;

        evtDecoder.wrap(buffer,
            offset + MessageHeaderDecoder.ENCODED_LENGTH,
            hdrDecoder.blockLength(),
            hdrDecoder.version());

        evtDecoder.getMessage(msgBuf, 0);
        int msgEnd = MSG_LEN;
        while (msgEnd > 0 && (msgBuf[msgEnd - 1] == 0 || msgBuf[msgEnd - 1] == ' ')) msgEnd--;
        final String cmd = new String(msgBuf, 0, msgEnd, StandardCharsets.US_ASCII);

        System.out.printf("[sim-ctrl] Received command: %s%n", cmd);
        handleCommand(cmd);
    }

    private void handleCommand(final String cmd) {
        if (cmd.startsWith("START_SIMULATION")) {
            final SimProfile profile = parseProfile(cmd, "START_SIMULATION:", SimProfile.HKEX_BASIS_ARB);
            startSimulation(profile);
        } else if (cmd.equals("STOP_SIMULATION")) {
            stopSimulation();
        } else if (cmd.startsWith("SET_PROFILE:")) {
            final SimProfile profile = parseProfile(cmd, "SET_PROFILE:", SimProfile.HKEX_BASIS_ARB);
            simulator.setProfile(profile);
        }
    }

    private SimProfile parseProfile(final String cmd, final String prefix, final SimProfile defaultProfile) {
        if (cmd.length() <= prefix.length()) return defaultProfile;
        final String profileName = cmd.substring(prefix.length()).trim();
        try {
            return SimProfile.valueOf(profileName);
        } catch (final IllegalArgumentException e) {
            System.out.printf("[sim-ctrl] Unknown profile '%s', using %s%n", profileName, defaultProfile.name());
            return defaultProfile;
        }
    }

    private void startSimulation(final SimProfile profile) {
        if (simThread != null && simThread.isAlive()) {
            System.out.println("[sim-ctrl] Simulation already running. Stopping first...");
            stopSimulation();
        }
        simulator.setProfile(profile);
        simThread = Thread.ofPlatform()
            .name("sim-thread")
            .start(simulator);
        System.out.printf("[sim-ctrl] Simulation STARTED with profile %s%n", profile.name());
    }

    private void stopSimulation() {
        simulator.stop();
        if (simThread != null) {
            simThread.interrupt();
            try {
                simThread.join(300); // wait up to 300ms for the sim thread to exit cleanly
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            simThread = null;
        }
        System.out.println("[sim-ctrl] Simulation STOPPED");
    }

    public boolean isRunning() {
        return simThread != null && simThread.isAlive();
    }

    @Override
    public void close() {
        stopSimulation();
        controlSub.close();
    }
}
