# Copilot Instructions

## Project Overview

This is a **prototype Index Arbitrage Algorithmic Trading System** targeting HK (HKEX), China (CSI), and Taiwan (TAIFEX) markets. The system is designed for ultra-low latency and zero-GC operation in the hot path. The project is currently at **Phase 7 complete** with all core arbitrage strategies, execution, web gateway, Docker deployment, and replay engine implemented.

## Role Context

Act as a **Principal Full-Stack Architect** specializing in HFT systems. When asked to extend functionality, refer to `devdocs/MyIndexArbAlgoSystemPlan.md` and `README.md` for scope boundaries. Do not add features outside the documented plan.

## Tech Stack

| Layer | Technology | Notes |
|-------|-----------|-------|
| **Backend** | Java 21 (Zero-GC), Aeron IPC, SBE | Multi-module Gradle project |
| **Frontend** | React 18, TypeScript, Vite, Tailwind CSS, Shadcn/UI, Recharts, Zustand | Separate Vite project at `arb-dashboard/` |
| **Key Libraries** | Decimal4j (1.0.3), Agrona (2.2.1), OpenGamma Strata (2.12.56), finmath-lib (6.0.20) | See [README: Library Choices & GC Audit](README.md#library-choices--gc-audit) for GC profiles |
| **Testing** | JUnit 5, Cucumber 7.x | BDD for integration, unit tests for hot-path GC validation |
| **Ops** | Docker, Docker Compose, Java 21, Node.js 20+ | `ipc: host` for Aeron shared-memory IPC |

## Build & Test Commands

### Backend (Gradle)

```bash
# Full build (generates SBE stubs, compiles all modules)
./gradlew build

# Run all tests across all modules
./gradlew test

# Single module (e.g., arb-gambit)
./gradlew :arb-gambit:build
./gradlew :arb-gambit:test

# Single test class
./gradlew :arb-common:test --tests "com.arb.PingPongTest"

# Single test method
./gradlew :arb-strategy:test --tests "com.arb.strategy.SequencerTest.shouldDispatchMarketDataToStrategy"

# Run with specific JVM args (e.g., for Aeron/Agrona Unsafe access)
./gradlew test --info  # verbose output

# Rebuild SBE stubs after schema changes
./gradlew :arb-common:generateSbeStubs
```

### Frontend (NPM)

```bash
cd arb-dashboard

# Development server (runs on port 3000 with HMR; proxies /api to http://localhost:8080)
npm run dev

# Production build
npm run build

# Run production build locally
npm run preview
```

### Docker & Deployment

```bash
# Build + start all services (includes backend JARs + frontend + web gateway)
./start-all.sh
# Dashboards:   http://localhost:3000
# WebSocket:    ws://localhost:8080/ws

# Tear down
./stop-all.sh
docker compose down

# Run individual services (for debugging)
./gradlew :arb-market-data:run
./gradlew :arb-gambit:run
./gradlew :arb-strategy:run
```

### Replay & Backtest

```bash
# Build shadow JARs (single JARs with all dependencies)
./gradlew :arb-market-data:shadowJar :arb-strategy:shadowJar :arb-execution:shadowJar :arb-web-gateway:shadowJar

# Run replay scenario (starts with embedded Aeron MediaDriver)
java -cp arb-market-data/build/libs/arb-market-data-*-all.jar \
  com.arb.marketdata.ReplayMain --scenario hkex-basis-arb-win --speed 2.0

# In separate terminals, start other services:
java -cp arb-strategy/build/libs/arb-strategy-*-all.jar com.arb.strategy.StrategyMain
java -cp arb-execution/build/libs/arb-execution-*-all.jar com.arb.execution.ExecutionMain
java -jar arb-web-gateway/build/libs/arb-web-gateway-*-all.jar

# Open dashboard: http://localhost:3000 → watch P&L panel climb
```

## Architecture Overview

### System Topology

Aeron IPC (Shared Memory `/dev/shm`):
- **Stream 1001 (MARKET_DATA):** MarketDataTick, QuoteTick, MarketVolumeTick, ReferenceDataRecord
- **Stream 1002 (ORDER):** OrderRequest (from strategies to execution)
- **Stream 1003 (ORDER_UPDATE):** OrderUpdate with orderId, fill status, avg price
- **Stream 1004 (CONTROL):** SystemEvent, emergency halt commands
- **Stream 1005 (FV):** FvUpdate (navPerUnit, futuresFv, basis, annualisedBasisBps)

**Message flow:**
```
arb-market-data → MARKET_DATA (1001) → arb-gambit (FvEngine), arb-strategy (ArbSequencer)
arb-gambit → FV_STREAM (1005) → arb-strategy, arb-web-gateway
arb-strategy → ORDER_STREAM (1002) → arb-execution (RiskGateway)
arb-execution → ORDER_UPDATE_STREAM (1003) → arb-strategy, arb-web-gateway
arb-web-gateway → WebSocket → arb-dashboard (React)
```

### Module Responsibilities

| Module | Role | Key Classes |
|--------|------|-------------|
| `arb-common` | SBE schemas, Aeron wrappers, ReferenceDataStore | `AeronPublisher`, `AeronSubscriber`, `ReferenceDataStore`, `Channels` |
| `arb-market-data` | Feed simulation + normalization | `HkexFeedHandler`, `TaifexFeedHandler`, `CsiFeedHandler`, `MarketDataGateway` |
| `arb-gambit` | Two-layer pricing engine (hot path zero-GC + warm path) | `FvEngine`, `NavCalculator`, `FuturesFvCalculator`, `DividendCalendar`, `VolSurfaceCalibrator` |
| `arb-strategy` | ArbSequencer + 8 strategy implementations | `ArbSequencer`, `HkexBasisArb`, `MhiHsiBasisArb`, `TwseEtfArb`, `CrossBorderEtfArb`, `SsfBasisArb`, `SsfCalendarSpreadArb`, `HkCnIndexPairArb`, `VolSkewBasisArb` |
| `arb-execution` | Pre-trade risk, mock exchange, basket slicing | `RiskGateway`, `MockExchangeConnector`, `BasketSlicer`, `PositionBook` |
| `arb-web-gateway` | Aeron → WebSocket bridge (Vert.x 4.5.7) | `WebGatewayVerticle`, `JsonMessages`, `AeronControlPublisher` (port 8080) |
| `arb-dashboard` | React operator cockpit | `LiveMonitor`, `OrderBook`, `ControlPanel`, `SystemHealth`, `PnlPanel` (port 3000) |

### Hot Path vs Warm Path

| Layer | Timing | GC | Key Responsibility | Rule |
|-------|--------|----|----|------|
| **Hot path** (per-tick) | < 10µs | Zero-GC mandatory | `NavCalculator`, `FuturesFvCalculator`, `FvEngine` dispatch | No `new` on hot thread. Pre-allocate flyweights. Use `long` arithmetic only. |
| **Warm path** (~30s periodic) | 10ms–1s | Allocation OK | `DividendCalendar` (Strata), `VolSurfaceCalibrator` (finmath-lib B-S), `SpreadVolEstimator` | Results written via `AtomicLong.setRelease()` for hot-path lock-free read via `getAcquire()`. |
| **Cold path** (batch) | No budget | Allocation OK | `MonteCarloPositionSizer` (finmath-lib MC), full Greeks | Run offline; results feed to strategy config. |

**Warm→Hot Bridge:** Use `AtomicLong` with acquire-release ordering, NOT `LongAdder` (no `set(value)` method; designed for multi-writer counters only).

## Key Conventions & Patterns

### Zero-GC Hot Path (Non-Negotiable)

The hot path (per market-data tick, ~10µs) must never trigger heap allocation:

1. **No `new` in hot methods** (`onMarketData`, `onFvUpdate`, `onTimer` on hot thread). Pre-allocate all objects in constructor using `static final` or constructor initialization.
2. **Prices & index values:** Normalized to `long` with scale 10⁴ at ingestion. Never use `double` on hot path. Example: HSI 19000.00 → `190_000_000L`.
3. **Symbol lookups:** Use `agrona.collections.Object2ObjectHashMap`, not `HashMap`. Pre-intern all symbol Strings at startup via `Symbol.intern()` (avoid `new String(bytes)` on hot path).
4. **SBE flyweights:** All SBE encoders/decoders are pre-allocated `UnsafeBuffer` wrappers wrapping a `DirectBuffer`. Never allocate on decode; reuse via flyweight pattern.
5. **Validation:** Prove zero-GC compliance via JFR profiling or Epsilon GC runs. Include 100k-iteration GC-validation tests (see `*ZeroGcTest` in each module).

### SBE Message Schema

All messages use `SCHEMA_ID=1`, `BYTE_ORDER=LITTLE_ENDIAN`. Schema file: `arb-common/src/main/resources/sbe/arb-messages.xml`.

| ID | Message | Fields | Published by | Fixed-point scale |
|----|---------|--------|---|---|
| 1 | `MarketDataTick` | symbol(12), exchange, price, qty, timestamp | arb-market-data | price: 10⁴ |
| 2 | `OrderRequest` | symbol, side, price, qty, orderId, orderType | arb-strategy | price: 10⁴ |
| 3 | `SystemEvent` | eventType, message | any | — |
| 4 | `QuoteTick` | symbol(12), exchange, iep, bidPrice, askPrice, timestamp | arb-market-data | 10⁴ for prices |
| 5 | `MarketVolumeTick` | symbol(12), exchange, iev, dailyVolume, timestamp | arb-market-data | — |
| 6 | `ReferenceDataRecord` | symbol(12), exchange, lotSize, tickSize, currency(3), constituentWeight | arb-market-data | constituentWeight: 10⁶ |
| 7 | `FvUpdate` | symbol(12), exchange, navPerUnit, futuresFv, basis, annualisedBasisBps, timestamp | arb-gambit | 10⁴ for prices, 10² for BPS |
| 8 | `OrderUpdate` | orderId, execStatus, fillQty, avgFillPrice, timestamp | arb-execution | 10⁴ for prices |

### Strategy Interface & Dispatch

All strategies implement `com.arb.strategy.Strategy`:

```java
interface Strategy {
    void onMarketData(MarketDataTickDecoder tick, OrderSink orders);
    void onFvUpdate(FvUpdateDecoder fv, OrderSink orders);
    default void onQuote(QuoteTickDecoder tick, OrderSink orders) {}
    default void onMarketVolume(MarketVolumeTickDecoder tick, OrderSink orders) {}
    default void onReferenceData(ReferenceDataRecordDecoder record) {}
}
```

**Dispatch mechanism:** `ArbSequencer` dispatches SBE messages by `templateId` via a pre-compiled switch table (no reflection, no polymorphic overhead beyond one call site). Register strategies in `StrategyRegistry`.

**Current implementations (8 strategies):** 
- Index Futures: `HkexBasisArb`, `MhiHsiBasisArb`
- ETF Arb: `TwseEtfArb`, `CrossBorderEtfArb`
- SSF: `SsfBasisArb`, `SsfCalendarSpreadArb`
- Pair: `HkCnIndexPairArb`
- Vol-informed: `VolSkewBasisArb`

**Configuration:** Enable/disable per-strategy in `config/strategies.properties`.

### Library & GC Profiles

| Library | Version | Hot? | Profile | Best Use |
|---------|---------|------|---------|----------|
| Aeron | 1.48.0 | ✅ Yes | Zero-GC ring buffer | IPC backbone — no alloc on send/recv |
| Agrona | 2.2.1 | ✅ Yes | Zero-GC (`Object2ObjectHashMap`, `UnsafeBuffer`) | Collections & buffers; use for hot path lookups |
| Decimal4j | 1.0.3 | ✅ Yes | All-primitive (`long → long` API) | Fixed-point price arithmetic (prices, basis, BPS) |
| Strata | 2.12.56 | ❌ Warm/cold | Allocates (`ImmutableBean`, boxed Double) | Yield curves, dividend discount (warm path only) |
| finmath-lib | 6.0.20 | ⚠️ Analytic only | Near-zero-GC `AnalyticFormulas` (warm), MC allocates (cold) | B-S IV calibration (warm), VaR (cold). Never on hot path |

**Strata B-S Trap:** `BlackScholesFormulaRepository.price()` boxes `Double` (2 heap allocs/call). Use **finmath-lib `AnalyticFormulas`** for warm-path B-S instead.

### Pre-Trade Risk Rules

Implemented in `RiskGateway`:
- **Fat finger:** Reject if `qty > MaxLimit` or price deviates > 5% from last tick.
- **Max position:** Hard stop on gross exposure per symbol.

### Frontend Architecture

- **Component library:** Shadcn/UI only — do NOT introduce Material-UI, Chakra, or other UI libraries.
- **Styling:** Tailwind CSS utility classes only — no custom CSS files.
- **Charts:** Recharts for real-time ticking (P&L area chart, basis spread line chart, latency histogram).
- **State management:** Zustand for global market/order state.
- **Data feeds:** WebSocket from `arb-web-gateway` (port 8080) or REST endpoints.

**Views (Phase 5+):**
- `LiveMonitor`: FV vs Market Price chart + spread metrics
- `OrderBook`: Streaming order fills + position P&L
- `ControlPanel`: Per-strategy enable/disable toggles + Emergency Halt button
- `SystemHealth`: Latency histogram + event log
- `PnlPanel` (Phase 7): Cumulative P&L area chart (green for wins, red for losses)

### Docker & IPC Tuning

- **Each module runs as a separate container** (one JVM per service).
- **Aeron IPC over `/dev/shm`** requires `ipc: host` or shared volume for `/dev/shm` in `docker-compose.yml`.
- **Do NOT use TCP Aeron channels** between containers in the prototype.
- `start-all.sh` builds all shadow JARs and starts all services via `docker compose up --build`.

### Testing

- **Unit tests:** JUnit 5. All modules run with `./gradlew test`.
- **GC validation:** Every module includes a "ZeroGcTest" that runs hot-path logic 100k times and asserts zero allocations.
- **BDD/Integration:** Cucumber 7.x feature files in `src/test/resources/features/`.
  - `market_data.feature`: Ingestion → normalization → SBE publish.
  - `end_to_end.feature`: Basis widening → 51 orders (1 future + 50 constituent stocks).
  - Strategy-specific scenarios: basis arb signal, cross-border ETF arb, etc.
- **Key test:** `PingPongTest` in `arb-common` sends 10,000 Aeron IPC messages; asserts < 10µs loopback latency.

### Naming & Coding Style

- **Packages:** `com.arb.{module}` (e.g., `com.arb.strategy`, `com.arb.gambit`).
- **Channel/Stream IDs:** Use constants in `Channels` class (e.g., `MARKET_DATA_STREAM = 1001`).
- **Symbol strings:** Fixed 12-byte fields in SBE; pre-interned at startup.
- **Comments:** Only clarify non-obvious logic (no javadoc for getters/setters).
- **Error handling:** Checked exceptions only for network/IO; unchecked for programming errors.

### Commits

- Include `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>` trailer.
- Phase-tagged commit messages where applicable (e.g., "Phase 7: Add P&L panel to dashboard").
- Squash related micro-commits into logical units.

## Common Development Tasks

### Add a New SBE Message Type

1. Edit `arb-common/src/main/resources/sbe/arb-messages.xml` (add new `<message>` block).
2. Run `./gradlew :arb-common:generateSbeStubs` → generates Java encoder/decoder.
3. Add new channel stream ID to `Channels` class if needed.
4. Create publisher/subscriber in relevant module.
5. Add integration test (e.g., Cucumber).

### Add a New Strategy

1. Create class in `arb-strategy/src/main/java/com/arb/strategy/` implementing `Strategy`.
2. Implement required interface methods (at minimum: `onMarketData`, `onFvUpdate`).
3. **Ensure zero-GC:** Pre-allocate all objects in constructor; no `new` in callbacks.
4. Register in `StrategyRegistry.register()`.
5. Add entry to `config/strategies.properties`.
6. Write unit tests + add Cucumber scenario if needed.
7. Validate zero-GC compliance with 100k-iteration test.

### Profile for GC

```bash
# Run with JFR (Java Flight Recorder) enabled
./gradlew test -Dcom.sun.management.jmxremote.port=9010 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false \
  -XX:StartFlightRecording=filename=recording.jfr,duration=30s

# Or use Epsilon GC (fails immediately if any allocation occurs)
./gradlew test -XX:+UnlockExperimentalVMOptions -XX:-UseG1GC -XX:+UseEpsilonGC
```

### Update Dashboard Component

1. Edit `.tsx` files in `arb-dashboard/src/`.
2. Import Shadcn/UI components from `@/components/ui/`.
3. Style with Tailwind classes only.
4. Use Zustand store for global state (e.g., `useOrderStore()`).
5. WebSocket data flows from `arb-web-gateway` → store → component re-render.
6. Run `npm run dev` for HMR.
7. Build with `npm run build` (outputs to `dist/`).

## References

- **System Plan:** `devdocs/MyIndexArbAlgoSystemPlan.md`
- **Strategy Guide:** `docs/strategies.md` (jargon glossary + payoff diagrams)
- **Replay Scenario:** `docs/replay-scenario.md`
- **Full README:** `README.md` (architecture diagrams, library rationale, phase delivery history)
