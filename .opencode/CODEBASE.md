# Codebase Memory — `myIndexArbAlgoWorks`

> **Load this first every session.** Comprehensive reference for the HFT index arbitrage system.

---

## Project Purpose

High-frequency **index arbitrage algorithmic trading system** (prototype) targeting Asian equity markets:
- **HKEX**: HSI/MHI futures, Single Stock Futures, CSOP A50 ETF
- **TAIFEX/TWSE**: TAIEX futures, TWSE 50 ETF (0050.TW), TSMC SSF
- **CSI**: CSI300 index (pair-arb reference)

Detects temporary mispricings between index futures/ETFs and their theoretical fair values, executes two-leg delta-neutral arb trades. Designed for **sub-10µs per-tick** with **zero-GC on the critical hot path**. **Phase 7 complete.**

---

## Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| Language | Java 21 | Multi-module Gradle |
| Build | Gradle 9.4.0 wrapper | Shadow JAR plugin 8.3.5 |
| IPC | Aeron 1.48.0 | Shared memory `/dev/shm`, 6 streams |
| Serialization | SBE 1.35.1 | 9 message types, zero-allocation flyweights |
| Collections | Agrona 2.2.1 | `Object2ObjectHashMap`, `Object2LongHashMap` |
| Fixed-point math | Decimal4j | Zero-GC `long` arithmetic |
| Analytics (warm) | OpenGamma Strata 2.12.56 | Yield curves, dividend discount |
| Analytics (warm/cold) | finmath-lib 6.0.20 | Black-Scholes, Monte Carlo |
| Web Gateway | Vert.x 4.5.7 | WebSocket bridge, REST API, port 8080 |
| Audit DB | H2 2.2.224 | Embedded, append-only |
| Tests (unit) | JUnit 5 5.10.2 | |
| Tests (BDD) | Cucumber 7.22.1 | 4 feature files |
| Frontend | React 18 + TypeScript 5.5 | Vite 5.4, Tailwind 3.4, Shadcn/UI, Recharts 2.12, Zustand 4.5 |
| Containerization | Docker + Compose 3.9 | 5 services, IPC host namespace |

---

## Module Map

```
myIndexArbAlgoWorks/
├── arb-common/          # Shared kernel: SBE schema, Aeron wrappers, ReferenceDataStore, latency metrics
├── arb-market-data/     # Feed handlers: synthetic feed simulation, replay engine, SBE gateways
├── arb-gambit/          # Fair value engine: hot-path NAV/FV + warm-path Strata/finmath analytics
├── arb-strategy/        # Strategy engine: ArbSequencer + 8 strategies + basket definitions
├── arb-execution/       # Execution & risk: RiskGateway + MockExchange + PositionBook + BasketSlicer
├── arb-web-gateway/     # Aeron→WebSocket bridge: Vert.x + H2 audit log + REST API
├── arb-dashboard/       # React 18 operator cockpit (separate Vite project)
├── config/              # strategies.properties (per-strategy enable/disable)
├── docs/                # strategies.md, replay-scenario.md, architecture diagrams
├── docker-compose.yml   # 5-service deployment
├── start-all.sh, stop-all.sh, start-all-rebuild.sh
└── README.md            # 630-line architecture doc
```

---

## Aeron IPC Stream Map (aeron:ipc)

| Stream | ID | Publisher → Subscriber | Messages |
|---|---|---|---|
| MARKET_DATA | 1001 | market-data → gambit, strategy, gateway | 1-MarketDataTick, 4-QuoteTick, 5-MarketVolumeTick, 6-ReferenceDataRecord |
| ORDER | 1002 | strategy (OrderSink) → execution (RiskGateway) | 2-OrderRequest |
| ORDER_UPDATE | 1003 | execution (MockExchange) → strategy, gateway | 8-OrderUpdate |
| CONTROL | 1004 | gateway (AeronControlPublisher) → strategy, market-data | 3-SystemEvent |
| FV | 1005 | gambit (FvEngine) → strategy, gateway | 7-FvUpdate |
| LATENCY | 1006 | strategy, execution → gateway | 9-LatencyStats |

---

## Compute Tiers

| Tier | Latency | GC Constraint | What runs |
|---|---|---|---|
| **Hot path** | <10µs/tick | Zero-GC (no `new`) | `NavCalculator`, `FuturesFvCalculator`, `FvEngine` dispatch, `ArbSequencer` fan-out, `OrderSink.submit()`, `BasisCalculator` |
| **Warm path** | ~30s periodic | Allocation OK | `DividendCalendar` (Strata), `VolSurfaceCalibrator` (finmath B-S), `SpreadVolEstimator` (Welford σ) |
| **Cold path** | Batch/offline | Allocation OK | `MonteCarloPositionSizer` (finmath MC VaR), full Strata Greeks |

Warm→Hot bridge: `AtomicLong.setRelease()` (writer) → `getAcquire()` (reader) — lock-free, zero-GC.

---

## 8 Strategy Implementations

| ID | Class | Type | Market | Enabled | Signal |
|---|---|---|---|---|---|
| A1 | `HkexBasisArb` | Index Futures Basis | HKEX | Yes | `annualisedBasisBps > 50 BPS` |
| A2 | `MhiHsiBasisArb` | Intra-product Cross | HKEX | Yes | Spread > 2 BPS |
| B1 | `TwseEtfArb` | ETF NAV | TAIFEX/TWSE | Yes | IEP vs NAV > 20 BPS |
| B2 | `CrossBorderEtfArb` | Cross-border ETF | HKEX/SGX | No | FX-adjusted basis > 30 BPS |
| C1 | `SsfBasisArb` | SSF Basis | TAIFEX | Yes | carry + 15 BPS |
| C2 | `SsfCalendarSpreadArb` | SSF Calendar | TAIFEX | No | Spread > 2σ |
| D1 | `HkCnIndexPairArb` | Pair Trading | HKEX/CSI | No | Z-score > 2.0 |
| E1 | `VolSkewBasisArb` | Vol-Adaptive | HKEX | Yes | adaptive threshold |

---

## SBE Message Schema (`arb-messages.xml`)

9 message types, `SCHEMA_ID=1`, `BYTE_ORDER=LITTLE_ENDIAN`.
- Prices: scale 10⁴ (e.g., HSI 19000.00 → `190_000_000L`)
- Basis BPS: scale 10² (e.g., 60 BPS → `6000L`)
- Constituent weights: scale 10⁶
- Implied volatility: scale 10⁷

---

## Data Flow (Primary Tick-to-Trade)

```
LiveArbSimulator → PriceNormalizer → FeedHandler → MarketDataGateway
  → Aeron MARKET_DATA_STREAM (1001)
    → [gambit] FvEngine → FV_STREAM (1005)
    → [strategy] ArbSequencer → MultiStrategy → Strategy.onFvUpdate()
      → if signal: OrderSink → ORDER_STREAM (1002)
        → [execution] RiskGateway (fat-finger, position limit checks)
          → MockExchange (async fill 5-10s)
            → ORDER_UPDATE_STREAM (1003)
              → [gateway] WebGatewayVerticle (SBE→JSON, H2 persist, WS broadcast)
                → [dashboard] useWebSocket → Zustand stores → React render
```

### Control Flow
```
Dashboard → WebSocket → gateway → AeronControlPublisher → CONTROL_STREAM (1004)
  → SimulationController (market-data) / ArbSequencer (strategy)
  Commands: START_SIMULATION, STOP_SIMULATION, SET_PROFILE, START_STRATEGY, STOP_STRATEGY, EMERGENCY_HALT
```

---

## Key Files by Module

### arb-common (12 files)
- `Channels.java` — Stream ID constants (1001-1006)
- `ReferenceDataStore.java` — Zero-GC Agrona map for instrument data
- `aeron/AeronPublisher.java`, `aeron/AeronSubscriber.java` — Zero-allocation IPC wrappers
- `metrics/LatencyPublisher.java`, `metrics/LatencyRecorder.java` — 7-bucket histogram
- `resources/sbe/arb-messages.xml` — Master SBE schema (9 message types)

### arb-market-data (27 files)
- `MarketDataMain.java` — Entry point, launches embedded MediaDriver
- `ReplayMain.java` — Replay engine entry point
- `handler/HkexFeedHandler.java`, `TaifexFeedHandler.java`, `CsiFeedHandler.java` — Feed simulators
- `gateway/MarketDataGateway.java`, `QuoteGateway.java`, `MarketVolumeGateway.java`, `ReferenceDataGateway.java` — SBE publishers
- `normalizer/PriceNormalizer.java` — double→fixed-point conversion
- `sim/LiveArbSimulator.java` — 300-tick cycle (STEADY→ARB_RAMP→ARB_WINDOW→CONVERGENCE)
- `sim/SimProfile.java` — Enum: HKEX_BASIS_ARB, TWSE_ETF_ARB, SSF_CALENDAR, HK_CN_PAIR
- `sim/SimulationController.java` — CONTROL_STREAM subscriber
- `replay/` — ReplayEngine, ScenarioLoader, ScenarioFrame

### arb-gambit (11 files)
- `realtime/FvEngine.java` — Hot-path: computes FV, publishes to FV_STREAM
- `realtime/NavCalculator.java` — Zero-GC ETF NAV
- `realtime/FuturesFvCalculator.java` — Zero-GC cost-of-carry FV
- `realtime/EtfDefinition.java` — Immutable ETF basket
- `model/DividendCalendar.java` — Warm-path: dividend PV via Strata
- `model/VolSurfaceCalibrator.java` — Warm-path: B-S IV calibration
- `model/SpreadVolEstimator.java` — Warm-path: Welford σ estimator
- `model/MonteCarloPositionSizer.java` — Cold-path: MC VaR

### arb-strategy (30 files)
- `StrategyMain.java` — Wires ArbSequencer + MultiStrategy
- `Strategy.java` — Interface: onMarketData, onFvUpdate, onQuote, onMarketVolume, onReferenceData, onTimer
- `StrategyRegistry.java` — Reads properties, instantiates strategies with AtomicLong bridges
- `OrderSink.java` — Zero-GC order submission callback
- `sequencer/ArbSequencer.java` — Single-threaded event loop, SBE dispatch via switch
- `calculator/IndexCalculator.java` — Zero-GC weighted-sum index
- `calculator/BasisCalculator.java` — Stateless basis calc
- `impl/` — 8 strategy implementations
- `basket/HSIConstituents.java` — 10 HSI constituents (Tencent, HSBC, etc.)
- `basket/TWSeConstituents.java` — 5 TWSE constituents (TSMC, Hon Hai, etc.)

### arb-execution (9 files)
- `ExecutionMain.java` — Poll loop: RiskGateway + MockExchange
- `RiskGateway.java` — 3 pre-trade checks: fat-finger qty, fat-finger price, position limit + seqNo gap detection
- `RiskConfig.java` — maxQty=10k, maxPriceDeviation=1000 BPS, maxNetPosition=1M
- `MockExchangeConnector.java` — Async fills (5-10s delay)
- `BasketSlicer.java` — Multi-leg basket decomposition
- `PositionBook.java` — Zero-GC Agrona Object2LongHashMap

### arb-web-gateway (11 files)
- `GatewayMain.java` — Launches Vert.x
- `WebGatewayVerticle.java` — HTTP/WS server port 8080, polls Aeron every 10ms
- `JsonMessages.java` — Manual JSON serializer (no Jackson)
- `AeronControlPublisher.java` — Publishes dashboard commands to CONTROL_STREAM
- `persistence/TradeRepository.java` — Interface
- `persistence/H2TradeRepository.java` — H2 tables: order_requests, order_updates
- `persistence/TradeRepositoryFactory.java` — h2/chronicle/none selector
- `persistence/ChronicleMapTradeRepository.java` — Stub for off-heap
- `persistence/NoOpTradeRepository.java` — Discard all

### arb-dashboard (7 TypeScript source files)
- `src/types/messages.ts` — 7 WebSocket message interfaces + ControlCommand
- `src/hooks/useWebSocket.ts` — Auto-reconnect WS hook
- `src/store/useStore.ts` — Main Zustand store (prices, P&L, orders, strategies, latency, dark mode)
- `src/store/useTradeStore.ts` — Trade store (2-leg arb with basket IDs)
- `src/lib/utils.ts` — formatPrice, formatTimestamp, cn

---

## Database Schema (H2, auto-created)

```sql
order_requests (order_id BIGINT PK, basket_id BIGINT, leg_index INT, symbol VARCHAR(16), side VARCHAR(4), price BIGINT, qty BIGINT, ts BIGINT)
order_updates (id IDENTITY PK, order_id BIGINT, basket_id BIGINT, status VARCHAR(16), fill_price BIGINT, fill_qty BIGINT, ts BIGINT)
```

---

## HTTP/WS Endpoints (port 8080)

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Liveness probe |
| POST | `/api/control` | Raw command string → CONTROL_STREAM |
| GET | `/api/trades?page=0&size=20` | Paginated audit log |
| WS | `/ws` | Bidirectional: commands in, SBE→JSON data out |

---

## Key Build/Run Commands

```bash
# Gradle
./gradlew build                    # Full build (generates SBE stubs first)
./gradlew test                     # All tests
./gradlew :arb-common:generateSbeStubs  # Regenerate SBE Java from XML
./gradlew :<module>:shadowJar      # Fat JAR for a module
./gradlew :arb-strategy:test --tests "ClassName.methodName"

# Frontend
cd arb-dashboard && npm run dev    # Vite dev on port 3000
cd arb-dashboard && npm run build  # Production build

# Docker
./start-all.sh                     # docker compose up -d (cached)
./start-all-rebuild.sh             # docker compose up --build -d
./stop-all.sh                      # docker compose down

# Replay
java -cp arb-market-data/build/libs/*-all.jar com.arb.marketdata.ReplayMain --scenario hkex-basis-arb-win --speed 2.0
```

---

## Docker Service Map

| Service | Port | Notes |
|---|---|---|
| dashboard | 3000 | nginx:alpine serving React dist |
| web-gateway | 8080 | Vert.x WS/REST bridge |
| strategy | — | IPC only, depends_on market-data healthy |
| execution | — | IPC only, depends_on market-data healthy |
| market-data | — | IPC only, has health check (`ls /dev/shm/aeron*`) |

All backend services share `ipc: host` + `/dev/shm` volume for Aeron IPC.

---

## Key Design Patterns

1. **Zero-GC hot path**: SBE flyweights pre-allocated `UnsafeBuffer` wrappers; no `new` on trading thread. Verified via 100k-iteration GC-count tests.
2. **Single-threaded dispatch**: `ArbSequencer` on one pinned thread, SBE dispatch via `switch(templateId)`. No lock contention.
3. **Pre-trade risk isolation**: `RiskGateway` is separate process from strategy, satisfying regulatory separation.
4. **Warm→hot bridge**: `AtomicLong.setRelease()`/`getAcquire()` — lock-free parameter passing.
5. **Dashboard decoupling**: WebSocket-only, zero back-pressure to trading path.
6. **Config-driven strategies**: `config/strategies.properties` toggles without rebuild.
7. **Replay-identical**: ReplayEngine replays JSONL scenarios; downstream services behave identically to live.

---

## Testing Structure (23 test files)

- **Unit tests** (JUnit 5): 8 strategy tests, 3 gambit, 4 execution, 1 market-data, 1 common (PingPong), 1 gateway
- **BDD tests** (Cucumber): 3 runners, 4 step defs, 4 feature files
- **Zero-GC validation**: 100k iterations with GC count assertion (CI would use `-XX:+UseEpsilonGC`)

---

## Agent Rules (from AGENTS.md)

- **NEVER run `git commit` or `git reset`** — human commits. Stage files only.
- **NEVER write to production** — no `ssh elthost` that modifies state. Read-only OK.
- Always pause: "Does this involve git commit, reset, or production write?" If yes, STOP.
- All local dev: compile, test, edit, write files, local Docker — ALLOWED.
