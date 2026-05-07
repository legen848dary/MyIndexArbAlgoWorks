# INSTRUCTIONS FOR THE CODING AGENT (COPY & PASTE THIS FIRST)

**Role:** You are a Principal Full-Stack Architect specialized in High-Frequency Trading (HFT) systems.
**Context:** We are building a prototype **Index Arbitrage Algorithmic Trading System** targeting HK, China, and Taiwan markets.
**Tech Stack:**
*   **Backend:** Java 21 (Zero-GC), Aeron, SBE.
*   **Frontend:** React, TypeScript, Tailwind CSS (Shadcn/UI), Recharts.
*   **Ops:** Docker, Docker Compose (with IPC tuning).
    **Constraint:** You must follow this plan rigorously. Do not "hallucinate" features outside the scope.
    **Process:**
1.  Read the entire "MyIndexArbAlgoSystemPlan.md" below.
2.  **Wait for my command** to start Phase 0.
3.  When you finish a phase, output: "I have completed Phase [X]. Tests Passed: [Y]. Ready for Phase [X+1]?"

---

# Index Arb Algo System - Implementation Plan

## System Architecture Overview
*   **Core Messaging:** Aeron IPC (Inter-Process Communication) over Shared Memory.
*   **Serialization:** SBE (Simple Binary Encoding).
*   **Topology (Microservices):**
    *   `arb-market-data`: Ingests/Normalizes feeds.
    *   `arb-strategy`: The Core Logic (Sequencer + Strategies).
    *   `arb-execution`: Risk & Exchange Connectivity.
    *   `arb-web-gateway`: **[NEW]** Bridges Aeron streams to WebSockets for the GUI.
    *   `arb-dashboard`: **[NEW]** React-based Operator UI.
*   **Deployment:** Docker Compose with `ipc: host` or shared `/dev/shm`.

---

## Phase 0: The Spinal Cord (Scaffolding & Aeron)

**Goal:** Setup the project structure, build system, and the Aeron IPC messaging backbone.
**Timebox:** 2 Hours

### Tasks
1.  **Project Init:**
    *   Create this repo a Gradle multi-module project.
    *   Modules: `arb-common`, `arb-market-data`, `arb-strategy`, `arb-execution`, `arb-web-gateway`.
    *   Create a separate directory `arb-dashboard` for the frontend (Vite + React).
2.  **SBE Schema Definition (`arb-common`):**
    *   Define `MessageHeader.xml`.
    *   Define `MarketDataTick.xml` (Fields: `symbol`, `exchange`, `price`, `qty`, `timestamp`).
    *   Define `OrderRequest.xml` (Fields: `symbol`, `side`, `price`, `qty`, `type`).
    *   Define `SystemEvent.xml` (For GUI alerts/logs).
    *   Generate Java stubs using `SbeTool`.
3.  **Aeron Infrastructure (`arb-common`):**
    *   Implement `AeronPublisher` (Generic wrapper for `Publication`).
    *   Implement `AeronSubscriber` (Generic wrapper for `Subscription` with `FragmentHandler`).
    *   **Test:** `PingPongTest` sending 10,000 messages via IPC.

**Definition of Done:**
*   [ ] Gradle build succeeds.
*   [ ] SBE classes generated.
*   [ ] IPC Test passes.

---

## Phase 1: The Eyes (Market Data Ingestion)

**Goal:** Simulate feed handlers for Asian markets and normalize data.
**Timebox:** 3 Hours

### Tasks
1.  **Feed Handler Interface (`arb-market-data`):**
    *   `FeedHandler` interface: `onTick(String symbol, double price)`.
2.  **Market Simulators:**
    *   `HkexFeedHandler` (HKD), `TaifexFeedHandler` (TWD), `CsiFeedHandler` (CNY).
3.  **Normalization:**
    *   Convert prices to `long` (fixed point).
    *   Publish to `MARKET_DATA_CHANNEL`.
4.  **BDD Test:**
    *   `market_data.feature`: Verify ingestion to SBE conversion.

**Definition of Done:**
*   [ ] Handlers ingest and publish normalized SBE ticks.
*   [ ] Cucumber test passes.

---

## Phase 2: The Brain (Core Strategy Engine)

**Goal:** Implement the deterministic Sequencer and Plug-and-Play Strategy framework.
**Timebox:** 4 Hours

### Tasks
1.  **The Strategy Interface (`arb-strategy`):**
    *   `Strategy` interface: `onMarketData`, `onTimer`.
2.  **Fair Value (FV) Calculator:**
    *   Implement Zero-GC `IndexCalculator` and `BasisCalculator`.
    *   Use `agrona.collections.Int2ObjectHashMap`.
3.  **The Sequencer:**
    *   `ArbSequencer`: Single-threaded event loop reading from `MARKET_DATA_CHANNEL`.
    *   Dispatches to Strategy -> Writes to `ORDER_CHANNEL`.

**Definition of Done:**
*   [ ] Strategy logic is unit tested and verified Zero-GC.

---

## Phase 2a: The Senses (Extended Market Data & Reference Data)

**Goal:** Enrich the data model with the full set of typed market-data messages used in Asian
equity/futures markets and load static reference data that drives index composition and risk sizing.

**Rationale:** `MarketDataTick` (Phase 1) covers last-trade price only.
Index-arb strategies additionally require:
| Message | Key Fields | Market Context |
|---|---|---|
| `QuoteTick` | IEP, bid, ask | HKEX pre-open/close auction Indicative Equilibrium Price |
| `MarketVolumeTick` | IEV, dailyVolume | HKEX real-time Index Estimated Value + session volume |
| `ReferenceDataRecord` | lotSize, tickSize, currency, constituentWeight | Drives IndexCalculator composition; BasketSlicer lot-sizing |

**Timebox:** 3 Hours

### Tasks

1.  **SBE Schema Extension (`arb-common`):**
    *   Add `Currency3` named type (`char`, length=3).
    *   Add `QuoteTick` (id=4): `symbol`, `exchange`, `iep`, `bidPrice`, `askPrice`, `timestamp`.
        *   `iep` = Indicative Equilibrium Price (fixed-point, scale 10^4). 0 = not in auction.
    *   Add `MarketVolumeTick` (id=5): `symbol`, `exchange`, `iev`, `dailyVolume`, `timestamp`.
        *   `iev` = Index Estimated Value (fixed-point, scale 10^4).
    *   Add `ReferenceDataRecord` (id=6): `symbol`, `exchange`, `lotSize`, `tickSize`, `currency`, `constituentWeight`.
        *   `tickSize` and `constituentWeight` are fixed-point (scale 10^4 and 10^6 respectively).
    *   Regenerate SBE stubs.

2.  **New Gateways (`arb-market-data`):**
    *   `QuoteGateway`: encodes+publishes `QuoteTick` to `MARKET_DATA_CHANNEL`.
    *   `MarketVolumeGateway`: encodes+publishes `MarketVolumeTick` to `MARKET_DATA_CHANNEL`.
    *   `ReferenceDataGateway`: encodes+publishes `ReferenceDataRecord` to `MARKET_DATA_CHANNEL`.
    *   Mock feed handlers per exchange: `HkexQuoteHandler`, `TaifexQuoteHandler`, `CsiQuoteHandler`.

3.  **Reference Data Store (`arb-common`):**
    *   `ReferenceDataRecord` (value object): lotSize, tickSize, currency, constituentWeight.
    *   `ReferenceDataStore`: `Object2ObjectHashMap<String, ReferenceDataRecord>` (Agrona).
        *   `onRecord(ReferenceDataRecordDecoder)` — zero-GC update path.
        *   `get(symbol)` — returns the pre-allocated record or null.
    *   `IndexCalculator` updated: `addConstituentFromRefData(ReferenceDataStore)` bulk-loads weights.

4.  **Sequencer + Strategy Updates (`arb-strategy`):**
    *   Extend `Strategy` interface:
        *   `onQuote(QuoteTickDecoder tick, OrderSink orders)`
        *   `onMarketVolume(MarketVolumeTickDecoder tick, OrderSink orders)`
        *   `onReferenceData(ReferenceDataRecordDecoder record)` — no OrderSink; read-only.
    *   Update `ArbSequencer` fragment handler to dispatch all 3 new template IDs.

5.  **BDD Tests (`arb-market-data`):**
    *   `extended_market_data.feature`:
        *   Scenario: HKEX IEP (QuoteTick) during pre-open auction is published and decoded.
        *   Scenario: HKEX IEV (MarketVolumeTick) for HSI index is published and decoded.
        *   Scenario: ReferenceDataRecord for Tencent (0700.HK) is published and ReferenceDataStore is populated.

**Definition of Done:**
*   [ ] All 3 new SBE messages compile and round-trip through Aeron IPC.
*   [ ] `ReferenceDataStore` is populated from decoded `ReferenceDataRecord` messages.
*   [ ] `IndexCalculator` constituent weights can be bulk-loaded from `ReferenceDataStore`.
*   [ ] Cucumber BDD tests (3 scenarios) pass.

---

## Phase 2b: The Gambit (NAV & Fair Value Engine)

**Module:** `arb-gambit` (new Gradle module: `com.arb.gambit`)

**Goal:** A two-layer pricing engine:
1. **Hot path** (per-tick, zero-GC) — hand-rolled NAV and Futures FV using integer arithmetic only.
2. **Warm path** (periodic, allocation-safe) — OpenGamma Strata for yield curves + dividend PV; finmath-lib for analytic B-S/IV; Monte Carlo for VaR scenarios.

Publishes a normalised `FvUpdate` SBE message to `FV_CHANNEL`, consumed by strategies (Phase 3) and the GUI (Phase 5).

**Architecture Decision — Why Two Layers?**

| Layer | Latency | GC | What runs here |
|---|---|---|---|
| **Hot path** (per-tick) | < 10µs | Zero-GC mandatory | NAV, cost-of-carry FV, annualised basis BPS |
| **Warm path** (periodic recalibration, ~30s) | 10ms–1s | Allocation OK | Dividend PV curves (Strata), IV surface (finmath-lib B-S) |
| **Cold path** (batch, offline) | No budget | Allocation OK | Monte Carlo VaR (finmath-lib MC), full book Greeks (Strata) |

Black-Scholes and Monte Carlo are **never** invoked per-tick. They run on a recalibration schedule
and publish results into `AtomicLong` fields that the hot path reads lock-free. The 4 current
strategies are **linear arb** — B-S/MC are only needed for:
*   Dividend PV curve calibration (feeds `FuturesFvCalculator`).
*   Options-based hedge sizing (future phases, e.g. HKEX CBBC/warrant arb).
*   Risk reporting / VaR scenarios (offline).

**Warm/Cold Path Bridge — `AtomicLong` with acquire/release ordering (NOT `LongAdder`):**
```
Warm thread writes:  dividendPv.setRelease(newValue)   // StoreStore fence only
Hot  thread reads:   long pv = dividendPv.getAcquire()  // LoadLoad fence only
```
`LongAdder` is wrong here: it is for high-contention multi-writer counters; it has no `set(value)`,
and `sum()` is not a point-in-time snapshot. `AtomicLong.setRelease/getAcquire` is the correct
single-writer → single-reader lock-free pattern and is cheaper than full `volatile` write/read.

**Library Selection (based on GC audit of source code):**

| Library | Maven coordinates | Use in this system | GC profile |
|---|---|---|---|
| **OpenGamma Strata** | `com.opengamma.strata:strata-pricer:2.12.56` + `strata-product:2.12.56` | Yield curve bootstrapping, dividend discount curves, ETD product models (HSI/TAIEX/CSI300 futures definitions) | Warm/cold only — `NormalDistribution.getCDF(Double x)` auto-boxes; `ImmutableBean` allocs throughout |
| **finmath-lib** | `net.finmath:finmath-lib:6.0.20` | Analytic B-S (`AnalyticFormulas.blackScholesGeneralizedOptionValue` — primitive in/out, near-zero-GC); Monte Carlo VaR (cold path) | Analytic path: near-zero-GC ✅; MC path: allocates `RandomVariable[]` — cold only |
| **Decimal4j** | `org.decimal4j:decimal4j:1.0.3` | `DecimalArithmetic` API for hot-path fixed-point price arithmetic (long → long, zero GC) | True zero-GC ✅ via `DecimalArithmetic` |
| ~~JQuantLib~~ | ❌ Not on Maven Central, dead since 2016 | Do not use | — |
| ~~commons-math3~~ | ❌ Last release 2016, no security patches | Do not use directly | — |

> **Strata B-S boxing trap:** `BlackScholesFormulaRepository.price()` calls `NORMAL.getCDF(Double x)`
> where `T = Double` (boxed) — 2 heap allocations per call. Use **finmath-lib** `AnalyticFormulas`
> for any B-S computation, or inline the Abramowitz & Stegun polynomial N(x) approximation
> (zero-alloc, accurate to 1e-7) for truly zero-GC options pricing if ever needed on hot path.

**Rationale for hand-rolling the hot path:**
Real index-arb requires these signals on every tick:

| Signal | Formula | Used by |
|---|---|---|
| **ETF NAV** | `Σ(holding_i × price_i) / sharesOutstanding` | `TwseEtfArb`, `CrossBorderEtfArb` |
| **Futures FV** | `Spot + financingCost − dividendPv` | `HkexBasisArb`, `SsfBasisArb` |
| **Annualised Basis BPS** | `(FutureMkt − FV) × 10_000 × 365 / (Spot × daysToExpiry)` | All strategies: entry/exit threshold |

> `financingCost = spotIndex × riskFreeRateBps × daysToExpiry / (10_000 × 365)` — pure `long` arithmetic.
> `dividendPv` pre-computed by warm-path `DividendCalendar`; passed to hot path via `AtomicLong.getAcquire()`.

**Timebox:** 4 Hours

### Tasks

1.  **New SBE Message (`arb-common`):**
    *   `FvUpdate` (id=7): `symbol`, `exchange`, `navPerUnit`, `futuresFv`, `basis`, `annualisedBasisBps`, `timestamp`.
        *   All price fields: fixed-point scale 10^4. `annualisedBasisBps`: fixed-point scale 10^2 (bps × 100).
    *   Add `FV_STREAM = 1005` to `Channels.java`.
    *   Regenerate SBE stubs.

2.  **Hot-Path Layer (`arb-gambit`, `com.arb.gambit.realtime`):**
    *   **`EtfDefinition`**: Immutable basket — `String[]` symbols, `long[]` sharesPerCreationUnit, `long cashComponentPerUnit` (fixed-point), `long sharesOutstanding`.
    *   **`NavCalculator`**: `computeNav(EtfDefinition, long[] prices) → long`. Caller pre-allocates `prices[]`; zero-GC.
    *   **`FuturesFvCalculator`**: `computeFv(long spotIndex, int riskFreeRateBps, int daysToExpiry, long dividendPv) → long`. Pure `long` arithmetic — no `double` in hot path.
        *   `annualisedBasisBps(long futureMktPrice, long fv, long spotIndex, int daysToExpiry) → long`.
    *   **`FvEngine`**: Subscribes to `MARKET_DATA_CHANNEL`. On each relevant tick recomputes and publishes `FvUpdate` to `FV_CHANNEL` via pre-allocated SBE encoder + `UnsafeBuffer`.
        *   Reads `dividendPv` via `AtomicLong.getAcquire()` (no fence overhead, lock-free).

3.  **Warm-Path Layer (`arb-gambit`, `com.arb.gambit.model`):**
    *   **`DividendRecord`**: `symbol`, `exDateEpochDays` (int), `grossAmountPerShare` (long, fixed-point).
    *   **`DividendCalendar`**: Loaded from static config. Uses **Strata** `strata-pricer` discount curves to compute `PV(dividends)`; writes result via `AtomicLong.setRelease()`.
    *   **`VolSurfaceCalibrator`** *(stub)*: Uses **finmath-lib** `AnalyticFormulas` (near-zero-GC, no boxing) to compute ATM implied vol. Writes result via `AtomicLong impliedVolBps.setRelease()`.

4.  **`arb-gambit/build.gradle`:**
    *   `implementation 'com.opengamma.strata:strata-pricer:2.12.56'` (warm path — yield curves)
    *   `implementation 'com.opengamma.strata:strata-product:2.12.56'` (ETD instrument models)
    *   `implementation 'net.finmath:finmath-lib:6.0.20'` (analytic B-S + Monte Carlo)
    *   `implementation 'org.decimal4j:decimal4j:1.0.3'` (hot-path fixed-point arithmetic)
    *   `implementation project(':arb-common')` + Aeron client.

5.  **Unit Tests (`arb-gambit`):**
    *   `NavCalculatorTest`: 3-constituent ETF mock, verify NAV; zero-GC: 100,000 hot-path iterations, GC count unchanged.
    *   `FuturesFvCalculatorTest`: HSI FV with 250 bps carry + dividend haircut; verify annualised basis BPS.
    *   `DividendCalendarTest`: verify `PV(dividends)` via Strata discount factor; assert `AtomicLong.getAcquire()` reads correct value after `setRelease()`.

6.  **Update `settings.gradle`:** (already done) `include 'arb-gambit'`.

**Definition of Done:**
*   [ ] `FvUpdate` SBE message round-trips through Aeron `FV_CHANNEL`.
*   [ ] NAV correct for 3-constituent ETF mock; zero-GC 100k iterations confirmed.
*   [ ] Futures FV correct for carry + Strata-computed dividend PV haircut.
*   [ ] `AtomicLong.setRelease/getAcquire` bridge verified in `DividendCalendarTest`.
*   [ ] `VolSurfaceCalibrator` stub compiles and returns placeholder ATM vol via finmath-lib.
*   [ ] All unit tests pass.

---

## Phase 3: The Alpha (Strategy Implementations)
**Timebox:** 6 Hours

### Tasks
1.  **Strategy A:** `HkexBasisArb` (HSI Future vs Basket).
2.  **Strategy B:** `TwseEtfArb` (ETF vs Basket - Creation/Redemption).
3.  **Strategy C:** `SsfBasisArb` (TSMC SSF vs Stock).
4.  **Strategy D:** `CrossBorderEtfArb` (HK vs CN ETF).
    *   *Configuration:* Allow enabling/disabling strategies via a config file.

**Definition of Done:**
*   [ ] All 4 strategies implemented and unit tested.

---

## Phase 4: The Hands (Execution & Risk)

**Goal:** Order routing and Pre-Trade Risk checks.
**Timebox:** 3 Hours

### Tasks
1.  **Pre-Trade Risk:**
    *   Fat Finger & Position Limits.
2.  **Execution Gateway:**
    *   Mock `ExchangeConnector`.
    *   Simulate 10-50us jitter/latency.
3.  **Legging Logic:**
    *   `BasketSlicer`: Break basket orders into individual messages.

**Definition of Done:**
*   [ ] Risk layer blocks invalid orders.
*   [ ] Fills are simulated and returned to `ORDER_UPDATE_CHANNEL`.

---

## Phase 5: The Face (The Cockpit GUI)

**Goal:** A modern, real-time dashboard to monitor P&L, Open Orders, and Latency.
**Timebox:** 5 Hours

### Tasks
1.  **The Bridge (`arb-web-gateway`):**
    *   **Tech:** Java Vert.x.
    *   **Role:** Subscribes to Aeron `MARKET_DATA` and `ORDER_UPDATE` channels.
    *   **Output:** Pushes JSON updates to Frontend via **WebSocket**.
    *   **Input:** Receives REST/WS commands from GUI (e.g., "Start Strategy", "Emergency Halt") and writes to Aeron `CONTROL_CHANNEL`.
2.  **The Dashboard (`arb-dashboard`):**
    *   **Tech:** React, Vite, Tailwind CSS.
    *   **Theme:** Use `shadcn/ui` for professional components (Cards, Tables). Implement **Dark/Light Mode toggle**.
    *   **Views:**
        *   **Live Monitor:** Real-time chart of Index Fair Value vs Market Price (Recharts).
        *   **Order Book:** Streaming list of active/filled orders.
        *   **Control Panel:** Switches to enable/disable specific strategies (HK, TW, CN).
        *   **System Health:** Latency histograms (backend reported).

**Definition of Done:**
*   [ ] Bridge forwards Aeron messages to Browser.
*   [ ] GUI displays live ticking charts.
*   [ ] Dark/Light mode works.
*   [ ] "Emergency Halt" button successfully stops the backend strategies.

---

## Phase 6: The Vessel (Dockerization & Deployment)

**Goal:** Containerize all components and orchestrate with Docker Compose.
**Timebox:** 3 Hours

### Tasks
1.  **Dockerfiles:**
    *   **Java Services:** Multi-stage build (Gradle build -> OpenJDK 21 Runtime).
    *   **GUI:** Node build -> Nginx Alpine image serving static files.
2.  **Docker Compose (`docker-compose.yml`):**
    *   **Services:** `market-data`, `strategy`, `execution`, `web-gateway`, `dashboard`.
    *   **Critical Network Config:**
        *   Since Aeron uses Shared Memory, all Java containers **MUST** share the IPC volume.
        *   Use `volumes: - /dev/shm:/dev/shm` OR `ipc: host`.
        *   Ensure `web-gateway` exposes port `8080`.
        *   Ensure `dashboard` exposes port `3000` (or `80`).
3.  **Startup Script:**
    *   `start-all.sh`: Starts the Media Driver (if standalone) first, then the services.

**Definition of Done:**
*   [ ] `docker-compose up` spins up the entire stack.
*   [ ] Frontend is accessible at `localhost:3000`.
*   [ ] Backend components communicate successfully via shared memory.

---

## Phase 7: Verification (The Backtest Loop)

**Goal:** Prove the system works by replaying history and visualizing it.
**Timebox:** 2 Hours

### Tasks
1.  **Data Recorder:**
    *   Record a "winning" scenario to a log file.
2.  **Replay & Viz:**
    *   Run the `ReplayEngine`.
    *   Open the GUI.
    *   Verify that the Chart draws the historic arbitrage opportunity and the "Orders" panel fills up with "Profit".
3.  **Final Deliverable:**
    *   A screen recording of the GUI showing the "Replay" resulting in positive P&L.

