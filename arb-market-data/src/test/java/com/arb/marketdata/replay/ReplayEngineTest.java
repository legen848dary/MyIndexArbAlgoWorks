package com.arb.marketdata.replay;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplayEngineTest {

    @Test
    void scenarioLoaderParsesJsonlLines() {
        final String line = "{\"type\":\"MD\",\"ts\":200,\"symbol\":\"HSI.HK\",\"exchange\":\"HKEX\",\"price\":190000000,\"qty\":500}";
        final ScenarioFrame f = ScenarioLoader.parseLine(line);
        assertEquals("MD",         f.type());
        assertEquals(200L,         f.tsMillis());
        assertEquals("HSI.HK",    f.symbol());
        assertEquals("HKEX",      f.exchange());
        assertEquals(190_000_000L, f.price());
        assertEquals(500L,         f.qty());
    }

    @Test
    void scenarioLoaderParsesFvLine() {
        final String line = "{\"type\":\"FV\",\"ts\":10000,\"symbol\":\"HSI.HK\",\"exchange\":\"HKEX\"," +
                            "\"futuresFv\":193200000,\"navPerUnit\":0,\"basis\":-3000000,\"annualisedBasisBps100\":6000}";
        final ScenarioFrame f = ScenarioLoader.parseLine(line);
        assertEquals("FV",         f.type());
        assertEquals(10_000L,      f.tsMillis());
        assertEquals(193_200_000L, f.futuresFv());
        assertEquals(6000L,        f.annualisedBasisBps100());
    }

    @Test
    void loadFromClasspathLoads150PlusFrames() throws IOException {
        final List<ScenarioFrame> frames = ScenarioLoader.loadFromClasspath(
                "scenarios/hkex-basis-arb-win.jsonl");
        assertTrue(frames.size() >= 150, "Expected ≥150 frames, got " + frames.size());
    }

    @Test
    void framesAreSortedByTimestamp() throws IOException {
        final List<ScenarioFrame> frames = ScenarioLoader.loadFromClasspath(
                "scenarios/hkex-basis-arb-win.jsonl");
        for (int i = 1; i < frames.size(); i++) {
            assertTrue(frames.get(i).tsMillis() >= frames.get(i - 1).tsMillis(),
                "Frames not sorted at index " + i);
        }
    }

    @Test
    void phaseB_hasFvFramesWithHighBasis() throws IOException {
        final List<ScenarioFrame> frames = ScenarioLoader.loadFromClasspath(
                "scenarios/hkex-basis-arb-win.jsonl");
        final long highBasisCount = frames.stream()
            .filter(f -> "FV".equals(f.type()) && f.annualisedBasisBps100() > 1000L)
            .count();
        assertTrue(highBasisCount >= 5,
            "Expected ≥5 FV frames with annualisedBasisBps100 > 1000, got " + highBasisCount);
    }

    @Test
    void scenarioDurationIsApprox30Seconds() throws IOException {
        final List<ScenarioFrame> frames = ScenarioLoader.loadFromClasspath(
                "scenarios/hkex-basis-arb-win.jsonl");
        final long durationMs = ReplayEngine.scenarioDurationMs(frames);
        assertTrue(durationMs >= 28_000 && durationMs <= 32_000,
            "Expected ~30s duration, got " + durationMs + "ms");
    }
}
