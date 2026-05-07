package com.arb.marketdata.replay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses a newline-delimited JSON scenario file into {@link ScenarioFrame} objects.
 * Uses hand-rolled parsing (no JSON library dependency) for minimal footprint.
 */
public final class ScenarioLoader {

    private ScenarioLoader() {}

    /**
     * Load frames from a classpath resource (e.g., "scenarios/hkex-basis-arb-win.jsonl").
     */
    public static List<ScenarioFrame> loadFromClasspath(final String resourcePath) throws IOException {
        try (final InputStream is = ScenarioLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) throw new IOException("Scenario not found: " + resourcePath);
            return parse(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)));
        }
    }

    /**
     * Load frames from an absolute file path.
     */
    public static List<ScenarioFrame> loadFromFile(final String path) throws IOException {
        try (final BufferedReader br = new BufferedReader(new FileReader(path, StandardCharsets.UTF_8))) {
            return parse(br);
        }
    }

    private static List<ScenarioFrame> parse(final BufferedReader reader) throws IOException {
        final List<ScenarioFrame> frames = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            frames.add(parseLine(line));
        }
        frames.sort(Comparator.comparingLong(ScenarioFrame::tsMillis));
        return frames;
    }

    /** Minimal JSON field extractor — handles simple flat objects only. */
    static ScenarioFrame parseLine(final String json) {
        final String type     = str(json, "type");
        final long   ts       = lng(json, "ts");
        final String symbol   = str(json, "symbol");
        final String exchange = str(json, "exchange");

        if ("MD".equals(type)) {
            return ScenarioFrame.md(ts, symbol, exchange, lng(json, "price"), lng(json, "qty"));
        } else {
            return ScenarioFrame.fv(ts, symbol, exchange,
                lng(json, "futuresFv"),
                lng(json, "navPerUnit"),
                lng(json, "basis"),
                lng(json, "annualisedBasisBps100"));
        }
    }

    private static String str(final String json, final String key) {
        final String search = "\"" + key + "\":";
        final int start = json.indexOf(search);
        if (start < 0) return "";
        int from = start + search.length();
        // Skip optional whitespace and opening quote
        while (from < json.length() && json.charAt(from) == ' ') from++;
        if (from < json.length() && json.charAt(from) == '"') from++;
        final int end = json.indexOf('"', from);
        return end < 0 ? "" : json.substring(from, end);
    }

    private static long lng(final String json, final String key) {
        final String search = "\"" + key + "\":";
        final int start = json.indexOf(search);
        if (start < 0) return 0L;
        int from = start + search.length();
        // Skip optional whitespace after colon
        while (from < json.length() && json.charAt(from) == ' ') from++;
        int end = from;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        final String val = json.substring(from, end).trim();
        return val.isEmpty() ? 0L : Long.parseLong(val);
    }
}
