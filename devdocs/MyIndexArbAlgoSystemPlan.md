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

**Goal:** Implement the full strategy catalogue — 8 strategies spanning index arb, ETF arb,
Single-Stock Futures (SSF), cross-border pair trading, and vol-informed arb.
Each strategy is a plug-in implementing `Strategy`; the `ArbSequencer` dispatches all market events.
**Timebox:** 10 Hours

---

### Strategy Catalogue

#### Group A — Index Futures Arb (pure cost-of-carry, linear arb)

**A1. `HkexBasisArb`** — HSI Futures vs Synthetic Basket
*Signal source: `FvUpdate` (FV_CHANNEL stream 1005)*

The original index-arb strategy. Reads `annualisedBasisBps` from `FvUpdate`.
- **Entry:** basis > `entryThresholdBps` → sell futures / buy basket (or reverse).
- **Exit:** basis < `exitThresholdBps` (mean reversion target).
- **Key inputs:** `FvUpdate.futuresFv`, `FvUpdate.annualisedBasisBps`, `ReferenceDataStore` (lot sizes).

**A2. `MhiHsiBasisArb`** — Mini-HSI (MHI) vs Full HSI Contract
*Intraday cross-contract spread on the same underlying.*

Mini-HSI (MHI) and full HSI are legally fungible at 1:5 ratio. Price divergence > 1 tick
(rare but occurs at open/close) creates a near-riskless spread.
- **Entry:** `(hsiPrice - 5 × mhiPrice) / hsiPrice × 10_000` > 2 BPS.
- **Both legs on HKEX** → no FX risk, same clearing. Typically sub-5µs window.

---

#### Group B — ETF Creation/Redemption Arb

**B1. `TwseEtfArb`** — YUANTA 50 ETF (0050.TW) vs TAIEX Basket
*Signal source: `NavCalculator` (hot path) + `FvUpdate`*

Classic ETF arb: if ETF market price diverges from intraday NAV beyond the creation/redemption
round-trip cost (≈ 20 BPS for Taiwan), trade the spread and redeem/create.
- **Long ETF / Short basket** when ETF trades at discount to NAV.
- **Short ETF / Long basket** when ETF trades at premium.
- Uses `EtfDefinition` pre-loaded from `ReferenceDataStore`.

**B2. `CrossBorderEtfArb`** — CSOP A50 ETF (2822.HK) vs SGX FTSE China A50 Futures
*Cross-currency: HKD ETF vs USD futures — requires FX rate bridge.*

CSOP A50 ETF tracks the FTSE China A50 index. SGX A50 futures (USD-denominated) are the
liquid offshore hedge. Basis between ETF NAV (CNH-adjusted) and futures fair value creates
arb opportunities around:
- MSCI rebalancing / passive fund flows.
- Shanghai-HK Stock Connect quota events.
- **FX bridge:** `usdCnhRate` and `hkdUsdRate` read from warm-path `AtomicLong` fields (set
  by a periodic FX rate feed, same `setRelease/getAcquire` pattern).

---

#### Group C — Single Stock Futures (SSF)

**C1. `SsfBasisArb`** — TSMC SSF vs TSMC Spot
*Classic SSF carry arb: SSF price vs spot + cost-of-carry.*

TSMC has SSFs listed on both TWSE and HKEX (H-share equivalent). Entry when:
`(ssfPrice - spotPrice) / spotPrice × 10_000 > riskFreeRateBps × daysToExpiry / 365 + 15 BPS buffer`

**C2. `SsfCalendarSpreadArb`** — TSMC SSF Near-Month vs Far-Month
*Pure convergence trade: near/far SSF spread mean-reverts to carry differential at expiry.*

Entry signal: observed spread deviates > `2σ` from theoretical carry-adjusted spread.
- `σ` computed by warm-path `SpreadVolEstimator` (rolling 20-day std dev of daily spread changes).
- Written to `AtomicLong spreadSigmaBps.setRelease()` → hot path reads via `getAcquire()`.
- Zero-GC entry condition: `abs(observedSpread - theoreticalSpread) > 2 × spreadSigma`.

---

#### Group D — Cross-Market Pair Trading (cointegration-based)

**D1. `HkCnIndexPairArb`** — HSI Futures vs CSI300 Futures
*Beta-adjusted mean reversion. Both indices driven by China macro.*

Uses a rolling hedge ratio `β` (updated warm-path, ~5-min intervals).
- **Hot path reads:** `betaScaled4.getAcquire()`, `zScoreThreshBps.getAcquire()`.
- **Entry:** `zScore = (spread - spreadMean) / spreadSigma > entryThresh` → short the rich leg, long the cheap.
- **Spread:** `HSI_futures_price - β × CSI300_futures_price` (both in CNH equivalent).
- **FX adjustment:** CNH/HKD rate from warm-path atomic.
- **Risk:** regime-break risk (correlation collapse). Hard stop: `|spread| > 3σ`.

---

#### Group E — Vol-Informed Arb (Black-Scholes + IV surface)

**E1. `VolSkewBasisArb`** — Basis Arb with IV-Adjusted Entry Threshold

The classic cost-of-carry basis does not account for the market's forward volatility expectations.
When `impliedVolBps` (from `VolSurfaceCalibrator`) diverges significantly from `realisedVolBps`
(rolling 20-day, computed warm-path), it signals the market is pricing in extraordinary risk —
widen the arb entry threshold to avoid being caught by a gap move.

**Mechanism (all values are warm-path `AtomicLong` bridges):**
- `impliedVolBps.getAcquire()` — current ATM IV (from `VolSurfaceCalibrator`).
- `realisedVolBps.getAcquire()` — rolling realised vol.
- `adaptiveThreshBps = baseThreshBps + max(0, impliedVolBps - realisedVolBps) / SCALE`
- Strategy only enters when `annualisedBasisBps > adaptiveThreshBps`.

This is the bridge between the warm-path analytics (`arb-gambit`) and the hot-path strategy
engine. Black-Scholes is **never invoked on the hot path** — only the pre-computed `impliedVolBps`
long value is read.

---

#### Monte Carlo Position Sizing (cold-path, feeds all strategies)

**`MonteCarloPositionSizer`** (cold path, `com.arb.gambit.model`)

Uses finmath-lib Monte Carlo (`EulerSchemeFromProcessModel`) to simulate 10,000 paths of the
spot index, computing `95%/99% VaR` for a given basket position size. Runs on a scheduled
executor (e.g., every 5 minutes or on demand before market open).

Results published to `AtomicLong` fields:
- `maxLotsBps95.setRelease(...)` — max basket lots before breaching 95% VaR limit.
- All hot-path strategies read `maxLots.getAcquire()` before sizing any order.

This ensures position limits are dynamically calibrated to realized market vol rather than
static config — without any GC pressure on the hot path.

---

### Configuration

Each strategy is independently enabled/disabled via `config/strategies.properties`:
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

The `ArbSequencer` reads this at startup and only wires enabled strategies into the dispatch loop.

---

### Tasks

1.  **Phase 3 infrastructure:**
    *   `StrategyRegistry`: loads and enables strategies from `strategies.properties`.
    *   `SpreadVolEstimator`: warm-path rolling σ for spread strategies (C2, D1).
    *   `MonteCarloPositionSizer`: cold-path VaR sizing; publishes `maxLotsBps95`.
    *   Update `ArbSequencer` to dispatch `FvUpdate` (templateId=7) to strategies.

2.  **Implement strategies (all in `arb-strategy`, `com.arb.strategy.impl`):**
    *   Group A: `HkexBasisArb`, `MhiHsiBasisArb`
    *   Group B: `TwseEtfArb`, `CrossBorderEtfArb`
    *   Group C: `SsfBasisArb`, `SsfCalendarSpreadArb`
    *   Group D: `HkCnIndexPairArb`
    *   Group E: `VolSkewBasisArb`

3.  **Unit tests (`arb-strategy`):**
    *   Each strategy: signal-fires correctly at/above threshold; no signal below threshold.
    *   `HkCnIndexPairArb`: Z-score entry/exit correctness.
    *   `VolSkewBasisArb`: adaptive threshold widens with IV > realised vol.
    *   `MonteCarloPositionSizer`: VaR result is a positive integer (sanity check).

4.  **BDD Feature (`arb-strategy`):**
    *   `strategies.feature`: 8 scenarios (one per strategy), each verifying the
        entry signal fires under synthetic `FvUpdate` + `MarketDataTick` inputs.

**Definition of Done:**
*   [ ] All 8 strategies compile and are dispatchable via `ArbSequencer`.
*   [ ] Each strategy has at least 2 unit tests (signal fires / signal suppressed).
*   [ ] `VolSkewBasisArb` reads only pre-computed `AtomicLong` values on hot path (no B-S invocation).
*   [ ] `MonteCarloPositionSizer` runs without error and writes to `AtomicLong`.
*   [ ] `strategies.feature` BDD (8 scenarios) passes.
*   [ ] All prior tests still pass (25 tests from Phases 0–2b).

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

