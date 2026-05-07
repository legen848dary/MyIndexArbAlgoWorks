# HSI Basis Arb — Winning Scenario Walkthrough

## Scenario: `hkex-basis-arb-win`

Duration: ~30 seconds | Frames: ~180 | Cadence: 200ms/frame

### What Happens

| Phase | Time | Event |
|---|---|---|
| A — Setup | 0–10s | Normal carry: HSI ~19000, futures FV = 19038.9, annualised basis ≈ −20 BPS. No signal. |
| B — Opportunity | 10–24s | Futures trade rich: futures price → 19320, annualised basis = **+6000 BPS100 (60 BPS)**. `HkexBasisArb` fires **SELL** orders. `MockExchangeConnector` fills at `futuresFv`. |
| C — Reversion | 24–30s | Basis reverts to carry. Strategy goes quiet. |

### How to Run Replay

**Standalone (requires running strategy + execution services):**
```bash
# Terminal 1 — start strategy service (connects to replay's MediaDriver)
java -cp arb-strategy/build/libs/arb-strategy-*-all.jar com.arb.strategy.StrategyMain

# Terminal 2 — start execution service
java -cp arb-execution/build/libs/arb-execution-*-all.jar com.arb.execution.ExecutionMain

# Terminal 3 — start web-gateway
java -jar arb-web-gateway/build/libs/arb-web-gateway-*-all.jar

# Terminal 4 — start replay (this starts the MediaDriver)
java -cp arb-market-data/build/libs/arb-market-data-*-all.jar com.arb.marketdata.ReplayMain \
  --scenario hkex-basis-arb-win --speed 2.0
```

**Via Docker Compose (replay mode):**
```bash
docker compose run --rm market-data \
  java -cp app.jar com.arb.marketdata.ReplayMain --scenario hkex-basis-arb-win --speed 2.0
```

Then open http://localhost:3000 to see:
- **LiveMonitor**: FV line shoots up to 19320, Market line stays ~19020 → visible divergence
- **OrderBook**: SELL orders fill as MockExchange processes them (10–50 µs latency)
- **P&L Panel**: Cumulative P&L climbs as SELL fills arrive
- **SystemHealth**: Event log shows strategy activity

### Scenario File Format

`arb-market-data/src/main/resources/scenarios/hkex-basis-arb-win.jsonl`

Each line is a JSON object:

**MarketDataTick frame:**
```json
{"type":"MD","ts":0,"symbol":"HSI.HK","exchange":"HKEX","price":190000000,"qty":500}
```

**FvUpdate frame:**
```json
{"type":"FV","ts":0,"symbol":"HSI.HK","exchange":"HKEX","futuresFv":190389000,"navPerUnit":0,"basis":-389000,"annualisedBasisBps100":-20}
```

- `price` and `futuresFv` are fixed-point ×10⁴ (e.g., 190_000_000 = 19000.00)
- `annualisedBasisBps100` is fixed-point ×10² (e.g., 6000 = 60 BPS; strategy threshold is 1000 = 10 BPS)
- `ts` is relative milliseconds from scenario start

### CLI Arguments

| Argument | Default | Description |
|---|---|---|
| `--scenario <name>` | `hkex-basis-arb-win` | Loads `scenarios/<name>.jsonl` from classpath |
| `--speed <N>` | `1.0` | Replay speed multiplier (2.0 = 2× faster, 0.5 = half-speed) |
