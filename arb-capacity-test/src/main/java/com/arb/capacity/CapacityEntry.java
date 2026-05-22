package com.arb.capacity;

public final class CapacityEntry {

    public static void main(final String[] args) throws Exception {
        String phase = "A";

        // Scan for --phase flag, pass remaining args to the target main
        String[] remaining = args;
        for (int i = 0; i < args.length; i++) {
            if ("--phase".equals(args[i]) && i + 1 < args.length) {
                phase = args[++i];
                remaining = new String[args.length - 2];
                int j = 0;
                for (int k = 0; k < args.length; k++) {
                    if (k == i - 1 || k == i) continue;
                    remaining[j++] = args[k];
                }
                break;
            }
        }

        switch (phase.toUpperCase()) {
            case "A" -> CapacityMain.main(remaining);
            case "B" -> LoadGeneratorMain.main(remaining);
            default -> {
                System.err.println("Usage: --phase A|B [options]");
                System.err.println("  Phase A: standalone embedded benchmark");
                System.err.println("  Phase B: live-system load generator");
                System.exit(1);
            }
        }
    }
}
