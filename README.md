# Index Arb Algo — Prototype HFT System (HK / Taiwan / China)

A prototype **Index Arbitrage Algorithmic Trading System** targeting Asian equity futures markets:
**HKEX** (HSI, MHI, SSF), **TAIFEX** (TAIEX futures, TWSE ETFs, TSMC SSF), and **CSI** (CSI300, A50 ETF).

Built as a demonstration of HFT-grade architecture in Java 21, with zero-GC hot paths, Aeron
IPC, SBE serialization, and a React operator dashboard.

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Aeron IPC (Shared Memory /dev/shm)                      │
│                                                                                  │
│  MARKET_DATA_STREAM (1001) ──────────────────────────────────┐                  │
│  FV_STREAM          (1005) ────────────────────┐             │                  │
│  ORDER_STREAM       (1002) ──────────────┐     │             │                  │
│  ORDER_UPDATE_STREAM(1003) ─────────┐    │     │             │                  │
│  CONTROL_STREAM     (1004) ──────┐  │    │     │             │                  │
└──────────────────────────────────┼──┼────┼─────┼─────────────┼──────────────────┘
                                   │  │    │     │             │
   ┌───────────────────────────────┘  │    │     │             │
   │  arb-web-gateway                 │    │     │             │
   │  (Vert.x WebSocket bridge) ──────┘    │     │             │
   │                                       │     │             │
   │  arb-execution ───────────────────────┘     │             │
   │  (Risk + MockExchange)                      │             │
   │                                             │             │
   │  arb-strategy ──────────────────────────────┘             │
   │  (ArbSequencer + 8 Strategies)                            │
   │                                                           │
   │  arb-gambit ──────────────────────────────────────────────┘
   │  (FvEngine hot path + DividendCalendar warm path)
   │
   └──── arb-market-data ──────────► MARKET_DATA_STREAM (1001)
         (Feed Handlers + Gateways)

   arb-common: SBE schemas, Aeron helpers, ReferenceDataStore
   arb-dashboard: React / Vite / Tailwind / Recharts
```

### Module Responsibilities

| Module | Role | Key Classes |
|---|---|---|
| `arb-common` | SBE schemas, Aeron wrappers, reference data | `AeronPublisher`, `AeronSubscriber`, `ReferenceDataStore`, `Channels` |
| `arb-market-data` | Feed simulation, normalization, SBE publish | `HkexFeedHandler`, `QuoteGateway`, `MarketVolumeGateway`, `ReferenceDataGateway` |
| `arb-gambit` | NAV/FV hot path + warm-path analytics | `NavCalculator`, `FuturesFvCalculator`, `FvEngine`, `DividendCalendar`, `VolSurfaceCalibrator` |
| `arb-strategy` | Strategy interface, ArbSequencer, 8 strategies | `Strategy`, `ArbSequencer`, `HkexBasisArb`, `HkCnIndexPairArb`, … |
| `arb-execution` | Pre-trade risk, mock exchange, basket slicing | `RiskGateway`, `MockExchangeConnector`, `BasketSlicer`, `PositionBook` |
| `arb-web-gateway` | Aeron → WebSocket bridge (Vert.x) | `AeronBridgeVerticle` |
| `arb-dashboard` | React operator cockpit | LiveMonitor, OrderBook, ControlPanel |

---

## Tech Stack & Rationale

### Backend: Java 21 (Zero-GC)

| Choice | Rationale |
|---|---|
| **Java 21** | Virtual Threads for warm/cold paths; record classes for value objects; sealed interfaces for SBE message hierarchy |
| **Zero-GC hot path** | Stop-the-world GC pauses of even 1 ms destroy P&L on sub-millisecond arb windows. All hot-path code uses pre-allocated objects, primitive `long` arithmetic, and `UnsafeBuffer` flyweights. |
| **Aeron IPC** | Sub-microsecond latency over `/dev/shm` shared memory. Lock-free SPSC ring buffer. No serialization overhead between processes on the same host. Preferred over Kafka (millisecond-range), ZeroMQ (TCP overhead), or Disruptor (single-process only). |
| **SBE (Simple Binary Encoding)** | Schema-first wire format. Generates flyweight encoders/decoders that wrap a `DirectBuffer` — zero heap allocation. Field-level access without full deserialization. 5–10× more CPU-efficient than Protobuf for fixed-schema financial messages. |

### Frontend: React + TypeScript + Tailwind + Shadcn/UI + Recharts

| Choice | Rationale |
|---|---|
| **Vite + React 18** | Fast HMR for rapid UI development; concurrent rendering for real-time ticking charts |
| **Tailwind CSS** | Utility-first — no CSS-in-JS runtime overhead; dark/light mode via `dark:` prefix |
| **Shadcn/UI** | Accessible, unstyled component primitives; no bundle bloat from full component libraries |
| **Recharts** | SVG-based, React-native charting; suitable for 1-second refresh rate live charts |

### Ops: Docker Compose + IPC tuning

Aeron IPC uses `/dev/shm` (POSIX shared memory). All Java service containers share the IPC
namespace via `ipc: host` or `volumes: - /dev/shm:/dev/shm`.

---

## Key Architectural Decisions

### 1. Zero-GC Hot Path (Non-Negotiable)

The hot path (per market-data tick) must never trigger heap allocation. Mechanisms:
- All SBE encoders/decoders are pre-allocated flyweights wrapping `UnsafeBuffer`.
- `NavCalculator` and `FuturesFvCalculator` use pure `long` arithmetic — no `double`, no boxing.
- `ReferenceDataStore` uses `Agrona Object2ObjectHashMap` — open-addressing, no `Map.Entry` allocation on `get()`.
- Pre-intern all symbol `String` keys at startup (never `new String(bytes)` on hot path).
- `EtfDefinition.prices[]` is pre-allocated by the caller; `NavCalculator` writes nothing.

### 2. AtomicLong Warm→Hot Bridge (Not LongAdder)

Warm-path computations (dividend PV, implied vol, hedge ratio β) are expensive and run
periodically. Results are passed to the hot path via `AtomicLong` using acquire-release ordering:

```java
// Warm thread (single writer):
dividendPv.setRelease(newValue);   // StoreStore fence only — cheaper than volatile write

// Hot thread (single reader):
long pv = dividendPv.getAcquire(); // LoadLoad fence only — cheaper than volatile read
```

**Why not `LongAdder`?** `LongAdder` is designed for high-contention multi-writer counters.
It has no `set(value)` method, and `sum()` is not a point-in-time snapshot.
`AtomicLong.setRelease/getAcquire` is the correct single-writer → single-reader pattern.

### 3. Two-Layer Pricing Engine (arb-gambit)

| Layer | Timing | GC | What runs here |
|---|---|---|---|
| **Hot path** (per-tick) | < 10µs | Zero-GC mandatory | `NavCalculator`, `FuturesFvCalculator`, `FvEngine` |
| **Warm path** (~30s periodic) | 10ms–1s | Allocation OK | `DividendCalendar` (Strata curves), `VolSurfaceCalibrator` (finmath-lib B-S), `SpreadVolEstimator` |
| **Cold path** (batch) | No budget | Allocation OK | `MonteCarloPositionSizer` (finmath-lib MC), full Greeks (Strata) |

Black-Scholes and Monte Carlo are **never** invoked per-tick. They run on a recalibration
schedule and publish results into `AtomicLong` fields that the hot path reads lock-free.

### 4. Strategy Dispatch via ArbSequencer

`ArbSequencer` is a single-threaded event loop subscribing to all Aeron channels.
It dispatches SBE messages by `templateId` to all enabled strategies via a pre-compiled
switch table — no reflection, no virtual dispatch overhead beyond a single polymorphic call site.

```
templateId=1 (MarketDataTick)    → strategy.onMarketData()
templateId=4 (QuoteTick)         → strategy.onQuote()
templateId=5 (MarketVolumeTick)  → strategy.onMarketVolume()
templateId=6 (ReferenceDataRecord) → strategy.onReferenceData()
templateId=7 (FvUpdate)          → strategy.onFvUpdate()   [Phase 3]
```

---

## Library Choices & GC Audit

| Library | Version | Hot path? | GC profile | Use |
|---|---|---|---|---|
| **Aeron** | 1.48.0 | ✅ Yes | Zero-GC (ring buffer, no alloc) | IPC messaging backbone |
| **Agrona** | 2.2.1 | ✅ Yes | Zero-GC (`Object2ObjectHashMap.get()`, `UnsafeBuffer`) | Collections, buffers |
| **SBE Tool** | (Aeron bundled) | ✅ Yes | Zero-GC flyweights | Wire encoding/decoding |
| **Decimal4j** | 1.0.3 | ✅ Yes | True zero-GC (`DecimalArithmetic` API: `long→long`) | Fixed-point price arithmetic |
| **OpenGamma Strata** | 2.12.56 | ❌ Warm/cold only | Allocates — `ImmutableBean`, `getCDF(Double x)` boxing | Yield curves, dividend discount, ETD product models |
| **finmath-lib** | 6.0.20 | ⚠️ Analytic only | Near-zero-GC for `AnalyticFormulas` (all-primitive); MC path allocates `RandomVariable[]` | Analytic B-S IV calibration (warm), Monte Carlo VaR (cold) |
| **Cucumber** | 7.x | ❌ Test only | N/A | BDD acceptance tests |
| **JUnit 5** | 5.10.2 | ❌ Test only | N/A | Unit tests |

> **Strata B-S boxing trap:** `BlackScholesFormulaRepository.price()` calls `NORMAL.getCDF(Double x)`
> where `T = Double` (boxed) → 2 heap allocations per call. Use **finmath-lib `AnalyticFormulas`**
> for any B-S computation on warm path, or inline the Abramowitz & Stegun N(x) polynomial for
> truly zero-GC options pricing if ever needed on hot path.

---

## SBE Message Catalogue

All messages use `SCHEMA_ID=1`, `BYTE_ORDER=LITTLE_ENDIAN`.

| ID | Name | BLOCK_LENGTH | Key Fields | Channel |
|---|---|---|---|---|
| 1 | `MarketDataTick` | 37 | symbol(12), exchange, price, qty, timestamp | MARKET_DATA (1001) |
| 2 | `OrderRequest` | — | symbol, side, price, qty, type | ORDER (1002) |
| 3 | `SystemEvent` | — | eventType, message | CONTROL (1004) |
| 4 | `QuoteTick` | 45 | symbol(12), exchange, iep, bidPrice, askPrice, timestamp | MARKET_DATA (1001) |
| 5 | `MarketVolumeTick` | 37 | symbol(12), exchange, iev, dailyVolume, timestamp | MARKET_DATA (1001) |
| 6 | `ReferenceDataRecord` | 40 | symbol(12), exchange, lotSize, tickSize, currency(3), constituentWeight | MARKET_DATA (1001) |
| 7 | `FvUpdate` | 53 | symbol(12), exchange, navPerUnit, futuresFv, basis, annualisedBasisBps, timestamp | FV (1005) |

**Fixed-point scales:**
- Prices, index values, NAV: scale 10⁴ (e.g., HSI 19000.00 → `190_000_000L`)
- `constituentWeight`: scale 10⁶
- `annualisedBasisBps`: scale 10² (e.g., 320.17 BPS → `32_017L`)
- `impliedVolBps`: scale 10⁷ (e.g., 20.00% IV → `2_000_000L`)

---

## Aeron Channel Map

```
Channel: "aeron:ipc"
Stream  1001 — MARKET_DATA_STREAM   Publishers: arb-market-data
                                    Subscribers: arb-gambit (FvEngine), arb-strategy (ArbSequencer)
Stream  1002 — ORDER_STREAM         Publishers: arb-strategy (strategies via OrderSink)
                                    Subscribers: arb-execution (RiskGateway)
Stream  1003 — ORDER_UPDATE_STREAM  Publishers: arb-execution (MockExchangeConnector)
                                    Subscribers: arb-strategy, arb-web-gateway
Stream  1004 — CONTROL_STREAM       Publishers: arb-web-gateway (GUI commands)
                                    Subscribers: arb-strategy (emergency halt)
Stream  1005 — FV_STREAM            Publishers: arb-gambit (FvEngine)
                                    Subscribers: arb-strategy, arb-web-gateway (dashboard charts)
```

---

## Strategy Catalogue

*(Updated at end of each phase.)*

| ID | Strategy | Group | Markets | Signal | Status |
|---|---|---|---|---|---|
| A1 | `HkexBasisArb` | Index Futures Arb | HKEX | `annualisedBasisBps` from `FvUpdate` | ✅ Phase 3 |
| A2 | `MhiHsiBasisArb` | Index Futures Arb | HKEX | MHI/HSI cross-contract spread | ✅ Phase 3 |
| B1 | `TwseEtfArb` | ETF Arb | TAIFEX / TWSE | ETF market price vs `NavCalculator` | ✅ Phase 3 |
| B2 | `CrossBorderEtfArb` | ETF Arb | HKEX / SGX | CSOP A50 ETF vs SGX A50 futures | ✅ Phase 3 |
| C1 | `SsfBasisArb` | SSF Arb | TAIFEX / TWSE | TSMC SSF vs TSMC spot carry | ✅ Phase 3 |
| C2 | `SsfCalendarSpreadArb` | SSF Arb | TAIFEX | Near vs far SSF calendar spread | ✅ Phase 3 |
| D1 | `HkCnIndexPairArb` | Pair Trading | HKEX / CSI | HSI vs CSI300 β-adjusted Z-score | ✅ Phase 3 |
| E1 | `VolSkewBasisArb` | Vol-Informed Arb | HKEX | IV-adaptive basis entry threshold | ✅ Phase 3 |

### Strategy Configuration

Strategies are independently enabled via `config/strategies.properties`:

```properties
strategy.HkexBasisArb.enabled=true
strategy.MhiHsiBasisArb.enabled=false
strategy.TwseEtfArb.enabled=true
strategy.CrossBorderEtfArb.enabled=false
strategy.SsfBasisArb.enabled=true
strategy.SsfCalendarSpreadArb.enabled=false
strategy.HkCnIndexPairArb.enabled=false
strategy.VolSkewBasisArb.enabled=true
```

### How to Use a Strategy

Each strategy implements `com.arb.strategy.Strategy`:

```java
public interface Strategy {
    void onMarketData(MarketDataTickDecoder tick, OrderSink orders);
    void onFvUpdate(FvUpdateDecoder fv, OrderSink orders);          // Phase 3+
    default void onQuote(QuoteTickDecoder tick, OrderSink orders) {}
    default void onMarketVolume(MarketVolumeTickDecoder tick, OrderSink orders) {}
    default void onReferenceData(ReferenceDataRecordDecoder record) {}
}
```

`OrderSink` is the zero-GC write interface to `ORDER_STREAM (1002)`.
Strategies must **never** allocate on the hot path — all objects must be pre-allocated in the
constructor and reused via flyweight patterns.

---

## Phase Delivery History

| Phase | Commit | What was delivered | Tests |
|---|---|---|---|
| **0** | `b112bd8` | Gradle multi-module scaffold, SBE schema (messages 1–3), `AeronPublisher/Subscriber`, `PingPongTest` (10k IPC messages) | 1 ✅ |
| **1** | `4304a7e` | `HkexFeedHandler`, `TaifexFeedHandler`, `CsiFeedHandler`; price normalization to `long`; `MarketDataGateway`; Cucumber BDD (3 scenarios) | 4 ✅ |
| **2** | `8817cc8` | `Strategy` interface, `IndexCalculator` (50-constituent zero-GC), `BasisCalculator`, `ArbSequencer` event loop, `OrderSink`; 5 unit tests incl. zero-GC 100k iteration | 9 ✅ |
| **2a** | `bacf362` | SBE messages 4–7 (`QuoteTick`, `MarketVolumeTick`, `ReferenceDataRecord`, `FvUpdate`); `ReferenceDataStore` (Agrona `Object2ObjectHashMap`); 7 new gateways/handlers; `FV_STREAM=1005`; extended Strategy dispatch; BDD (3 new scenarios) | 15 ✅ |
| **2b** | `1d7a01e` | `arb-gambit` module: `EtfDefinition`, `NavCalculator`, `FuturesFvCalculator`, `FvEngine`; warm path: `DividendCalendar`, `VolSurfaceCalibrator`; Strata + finmath-lib + Decimal4j wired; 14 new tests | 25 ✅ |
| **3** | *(pending)* | 8 strategies (`HkexBasisArb`, `MhiHsiBasisArb`, `TwseEtfArb`, `CrossBorderEtfArb`, `SsfBasisArb`, `SsfCalendarSpreadArb`, `HkCnIndexPairArb`, `VolSkewBasisArb`); `StrategyRegistry`; `SpreadVolEstimator` (Welford rolling σ); `MonteCarloPositionSizer` (finmath-lib cold-path VaR → max lots); 16 unit tests + 8 BDD scenarios | 41 ✅ |
| **4** | TBD | `arb-execution`: `RiskGateway` (fat-finger qty/price + position limits), `MockExchangeConnector` (10–50 μs simulated fill), `BasketSlicer`, `PositionBook`; SBE msg-8 `OrderUpdate` + `orderId` on `OrderRequest`; 9 unit tests + 5 BDD scenarios | 55 ✅ |
| **5** | *(planned)* | `arb-web-gateway` (Vert.x), React dashboard, live charts, dark mode | — |
| **6** | *(planned)* | Dockerfiles, `docker-compose.yml`, `ipc: host`, `start-all.sh` | — |
| **7** | *(planned)* | `ReplayEngine`, backtest loop, screen recording deliverable | — |

---

## Getting Started

### Prerequisites

- Java 21 (e.g., [Microsoft Build of OpenJDK](https://learn.microsoft.com/en-us/java/openjdk/download))
- Gradle 9.x (wrapper included — use `./gradlew`)
- Docker Desktop (for Phase 6+)
- Node.js 20+ (for `arb-dashboard`, Phase 5+)

### Build

```bash
# Build all modules and generate SBE stubs
./gradlew build

# Generate SBE stubs only (required after schema changes)
./gradlew :arb-common:generateSbeStubs
```

### Run Tests

```bash
# All modules
./gradlew test

# Single module
./gradlew :arb-gambit:test --rerun
./gradlew :arb-market-data:test
./gradlew :arb-strategy:test
```

### Run Individual Services (Phase 3+)

```bash
# Market data simulator
./gradlew :arb-market-data:run

# FV engine
./gradlew :arb-gambit:run

# Strategy engine
./gradlew :arb-strategy:run
```

---

## Project Conventions

| Convention | Rule |
|---|---|
| **Scaling** | All prices/index values: fixed-point scale 10⁴. BPS results: scale 10². Vol: scale 10⁷. |
| **Hot path objects** | Pre-allocate in constructor or `static final`. Never use `new` in `onMarketData/onFvUpdate`. |
| **AtomicLong bridge** | Warm writes: `setRelease()`. Hot reads: `getAcquire()`. Never `LongAdder`. |
| **SBE symbol fields** | Always 12 bytes. Use `getSymbol(byte[], 0)` into pre-allocated `byte[]` — never `.symbol()` (String) on hot path. |
| **Agrona maps** | Use `Object2ObjectHashMap` for symbol → record. Pre-intern all key Strings at startup. |
| **B-S / Monte Carlo** | finmath-lib for analytic B-S (warm path). Strata for yield curves (warm/cold only). Monte Carlo (cold path) only via `MonteCarloPositionSizer`. |
| **Testing** | Cucumber BDD for integration scenarios; JUnit 5 for unit tests. Verify zero-GC with 100k iteration tests. |
| **Commits** | Phase-tagged messages. Include `Co-authored-by: Copilot` trailer. |

---

## Targeted Markets & Instruments

| Market | Exchange | Instruments |
|---|---|---|
| Hong Kong | HKEX | HSI Index Futures, Mini-HSI (MHI), Single Stock Futures (SSF), CSOP A50 ETF (2822.HK) |
| Taiwan | TAIFEX / TWSE | TAIEX Futures, TWSE 50 ETF (0050.TW), TSMC SSF (near + far month) |
| China (offshore) | SGX / CSI | SGX FTSE China A50 Futures (USD), CSI300 Index Futures (CNH, offshore proxy) |

---

*This README is updated at the end of each phase. Strategy table entries transition from 🔲 to ✅ upon implementation.*
