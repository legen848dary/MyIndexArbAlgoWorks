# Index Arb Algo — HFT System Architecture

This system implements index arbitrage across three Asian equity markets —
HKEX (HSI, MHI, SSF), TAIFEX (TAIEX futures, TWSE ETFs, TSMC SSF), and
CSI (CSI300, A50 ETF) — with an architecture designed for sub-10µs per-tick
processing and zero garbage-collection pauses on the critical path. The
system is built in Java 21, uses Aeron IPC over shared memory for
inter-process messaging, SBE flyweights for zero-allocation serialisation,
and a React 18 operator dashboard for real-time monitoring.

What makes it architecturally interesting is the strict layering of
computation into hot, warm, and cold tiers — a discipline that lets
expensive analytics (dividend discounting, implied-vol calibration, Monte
Carlo VaR) coexist in the same process space as sub-microsecond pricing
without ever touching the garbage collector on the trading thread. Every
technology choice in the stack has been made with this constraint as the
primary filter: if it allocates on the hot path, it does not belong there.

---

## System Topology

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                       Aeron IPC  (Shared Memory /dev/shm)                    │
│                                                                               │
│  MARKET_DATA_STREAM  (1001) ──────────────────────────────────────┐          │
│  FV_STREAM           (1005) ──────────────────────┐               │          │
│  ORDER_STREAM        (1002) ────────────────┐     │               │          │
│  ORDER_UPDATE_STREAM (1003) ──────────┐     │     │               │          │
│  CONTROL_STREAM      (1004) ───────┐  │     │     │               │          │
│  LATENCY_STREAM      (1006) ────┐  │  │     │     │               │          │
└────────────────────────────────┼──┼──┼─────┼─────┼───────────────┼──────────┘
                                 │  │  │     │     │               │
   ┌─────────────────────────────┘  │  │     │     │               │
   │  arb-web-gateway               │  │     │     │               │
   │  (Vert.x WebSocket bridge) ────┘  │     │     │               │
   │                                   │     │     │               │
   │  arb-execution ───────────────────┘     │     │               │
   │  (RiskGateway + MockExchange)           │     │               │
   │                                         │     │               │
   │  arb-strategy ──────────────────────────┘     │               │
   │  (ArbSequencer + 8 strategies)                │               │
   │                                               │               │
   │  arb-gambit ──────────────────────────────────┘               │
   │  (FvEngine hot path + warm-path analytics)                    │
   │                                                               │
   └──── arb-market-data ───────────────────────────────────────────
         (Feed handlers — publishes to MARKET_DATA_STREAM 1001)

   arb-common    SBE schemas, Aeron wrappers, ReferenceDataStore
   arb-dashboard React 18 / Vite / Tailwind / Recharts cockpit
```

### Module Responsibilities

| Module | Role | Why it exists as a separate concern |
|---|---|---|
| `arb-common` | SBE schemas, Aeron wrappers, `ReferenceDataStore` | Shared kernel — all inter-process contracts live here; schema changes regenerate flyweights for all modules from one source |
| `arb-market-data` | Feed simulation, price normalisation, SBE publish | Isolates feed-handler complexity from pricing; swappable for a live exchange connector without touching downstream modules |
| `arb-gambit` | NAV/FV hot path + warm-path analytics | Separating pricing from strategy prevents strategy code from accidentally pulling expensive analytics onto the hot thread |
| `arb-strategy` | `Strategy` interface, `ArbSequencer`, 8 strategies | Single-threaded event loop eliminates inter-strategy lock contention; all strategies share one CPU cache footprint |
| `arb-execution` | Pre-trade risk, `MockExchangeConnector`, basket slicing | Risk checks must be auditable in isolation; separation enforces the compliance boundary between strategy logic and controls |
| `arb-web-gateway` | Aeron → WebSocket bridge (Vert.x 4.5.7) | Translates the zero-copy binary bus to JSON for the dashboard without blocking any trading thread |
| `arb-dashboard` | React 18 operator cockpit (Vite + Tailwind + Recharts) | Operator interface fully decoupled from the JVM process stack — deployable on any browser without a JVM dependency |

---

## Technology Choices & Rationale

### Java 21

**Chosen for:** production-grade JIT quality, mature HFT ecosystem, and the ability to achieve zero-GC on the hot path through pre-allocation without sacrificing expressiveness on analytics layers.

- **Virtual Threads (JEP 444):** warm-path analytics threads block freely on calibration loops without consuming OS threads; hot path runs on a pinned platform thread the scheduler never preempts mid-tick
- **Record classes:** immutable, structurally typed value objects (dividend schedule entries, reference data) at zero runtime cost
- **Sealed interfaces:** exhaustive, statically verifiable SBE dispatch hierarchy the JIT can fully devirtualise
- **JIT inline expansion:** after warm-up, the entire `NavCalculator → FuturesFvCalculator → FvEngine.dispatch()` chain compiles to a single native method — native code quality equivalent to C++ for fixed-point multiply-accumulate

**Why not alternatives:**
- **Python:** GIL prevents true parallelism across analytics tiers; object model allocates on every arithmetic operation
- **Go:** GC scan latency is non-deterministic at nanosecond scale; no Epsilon-equivalent abort-on-alloc guarantee
- **C++:** entire analytics stack (Strata, finmath-lib, Agrona) would need reimplementing from scratch

---

### Aeron IPC

**Chosen for:** zero-allocation lock-free inter-process messaging over shared memory — the only transport that fits inside a sub-10µs hot-path budget.

- **Mechanism:** lock-free SPSC ring buffer over `/dev/shm` (POSIX shared memory); producer writes directly to memory-mapped file, consumer polls the same physical memory — no kernel calls, no copies, no network stack
- **Measured latency:** 200–500 ns loopback on modern hardware
- **Zero allocation:** `offer()`/`poll()` produce no heap objects on either end
- **Non-blocking:** `Publication.offer()` returns immediately (or a back-pressure code) — hot thread never blocks on a slow consumer
- **Back-pressure visibility:** codes monitored and published to `LATENCY_STREAM (1006)` for operator view in `SystemHealth`

**Why not alternatives:**
- **Kafka:** broker requires TCP round-trip per message even on a single host → milliseconds of added latency + broker availability dependency
- **ZeroMQ `ipc://`:** avoids the network stack but still crosses the kernel boundary per send (~several µs)
- **Disruptor:** single-process only — cannot span the `arb-gambit` / `arb-strategy` module boundary
- **gRPC:** Protobuf encoding + HTTP/2 framing overhead incompatible with a zero-GC hot path

---

### SBE Serialisation

**Chosen for:** zero-allocation encode/decode of fixed-schema financial messages — eliminates serialisation cost from the latency budget entirely.

- **Flyweight model:** generated encoder/decoder classes wrap a `DirectBuffer`; `tick.price()` reads 8 bytes from a fixed offset, constructs no Java object
- **Schema-first:** changes to `arb-messages.xml` regenerate all encoder/decoder classes via `./gradlew :arb-common:generateSbeStubs` — schema drift between publisher and subscriber is a compile-time error, not a runtime surprise

**Why not alternatives:**
- **Protobuf:** allocates `Message.Builder` on every decode; boxes all numeric fields to object equivalents
- **FlatBuffers:** avoids most decode allocation but still requires an object-graph root pointer
- **JSON (Jackson streaming):** allocates `String` for every field name and value; text-to-number parsing alone would exceed the entire hot-path latency budget

---

### Decimal4j for Fixed-Point Arithmetic

**Chosen for:** exact decimal arithmetic with a `long → long` API — no floating-point rounding, no autoboxing.

- **Fixed-point scale:** prices at 10⁴ (HSI 19000.00 → `190_000_000L`), basis BPS at 10², constituent weights at 10⁶ — applied at ingestion, released only in `JsonMessages` for the WebSocket display layer
- **`DecimalArithmetic` API:** accepts and returns `long` exclusively — no boxing, no rounding error, no allocation

**Why not alternatives:**
- **`double`:** most decimal fractions cannot be represented exactly in binary — rounding errors compound across 82 constituent weights into phantom basis signals; auto-boxing to `Double` allocates on the hot path
- **`BigDecimal`:** allocates a `byte[]` backing array per value — no better than `Double` from a GC perspective

---

### Agrona Collections

**Chosen for:** cache-friendly symbol lookups with zero allocation on `get()`.

- **`Object2ObjectHashMap`:** open addressing with linear probing over a flat `Object[]` array — no `Entry` node objects; key and value sit in adjacent array positions
- **Pre-interned keys:** all symbol `String` objects interned at startup → `get()` uses reference equality (`==`), not `equals()` → no `char[]` content comparison
- **Cache efficiency:** 82 constituent lookups per tick walk a contiguous memory block that fits in L2 cache vs. `HashMap`'s 82 pointer-chasing cache misses

**Why not JDK `HashMap`:** allocates one `Map.Entry` per key-value pair; `put()` allocates on every insertion; `get()` chases heap pointers

---

### OpenGamma Strata and finmath-lib

**Chosen for:** two libraries, deliberately tiered — no single library satisfies both zero-GC and analytics-completeness simultaneously.

- **Strata (warm path ~30s):** yield curve bootstrapping (OIS discount curve), dividend discount model, calendar arithmetic, day-count conventions — most complete open-source ISDA-style ETD implementation in Java; excluded from hot path because `ImmutableBean` allocates freely by design
  - ⚠️ **Strata B-S trap:** `BlackScholesFormulaRepository.price()` boxes `Double` on every call (two heap allocs per call) — never used on any thread sharing GC with the hot path
- **finmath-lib `AnalyticFormulas` (warm path):** B-S option pricing and implied-vol Newton iteration using only primitive `double` — near-zero-GC; used by `VolSurfaceCalibrator` on a 30-second schedule
- **finmath-lib Monte Carlo (cold path / offline):** allocates `RandomVariable[]` and `double[]` path arrays — acceptable because it runs offline, results feed into position size limits, never per-tick

---

### Vert.x for the Web Gateway

**Chosen for:** non-blocking event-loop that bridges the Aeron binary bus to WebSocket JSON without touching any trading thread.

- **Single verticle thread:** handles all WebSocket connections, message fan-out, and Aeron poll callbacks through one I/O loop (Netty-backed) — no blocking operations anywhere
- **Process isolation:** separate container/JVM guarantees that a slow browser client or reconnecting dashboard cannot apply back-pressure to the `onMarketData()` hot path

**Why not embedding WebSocket in `arb-strategy`:** slow clients would back-pressure the same event loop as strategy callbacks, potentially adding milliseconds of jitter to the hot path

---

### React 18, Vite, Tailwind CSS, Shadcn/UI, Recharts

**Chosen for:** a fast, zero-runtime-overhead operator cockpit with real-time chart rendering.

- **React 18 concurrent rendering:** P&L area chart and order book update from the same WebSocket stream without one render blocking the other
- **Vite:** native ES-module dev server, near-instantaneous HMR with TypeScript (vs. webpack's full bundle recompile)
- **Tailwind CSS:** static CSS generated at build time — no runtime style injection, no DOM mutation per render (vs. styled-components/Emotion which hash and insert `<style>` tags on every render)
- **Shadcn/UI:** component primitives copied as plain TypeScript source into `src/components/ui/` — no runtime library dependency, zero bundle overhead beyond actual usage; Material-UI / Chakra / Ant Design excluded for importing entire component graphs with animation and theming runtimes
- **Recharts (SVG via React vDOM):** chart updates go through React's normal reconciliation — efficient diffing for 1s-interval data deltas vs. full canvas repaints of Canvas-based libraries
- **Zustand:** fine-grained subscriptions — only components that consume an updated data slice re-render

---

### H2 for the Web Gateway Audit Log

**Chosen for:** embedded RDBMS with zero network overhead for an append-only, single-writer audit log.

- Runs in the same JVM process as `arb-web-gateway` — no network socket, no separate service lifecycle
- Standard JDBC API, persists to a local file
- **Chronicle Map / Chronicle Queue:** zero-GC upgrade path if the audit log ever moves onto a latency-sensitive thread (off-heap memory-mapped files, sub-µs persistence) — not required today as the gateway is outside the trading thread's latency budget

**Why not PostgreSQL:** network round-trip + separate service lifecycle + connection pool management for a use case that is append-only and single-writer

---

### Docker Compose with `ipc: host`

**Chosen for:** restoring shared POSIX IPC namespace so Aeron `/dev/shm` ring buffers are visible across all trading containers.

- Docker containers run in isolated IPC namespaces by default — a `/dev/shm` segment created by one container is invisible to another
- `ipc: host` places all trading containers (`arb-market-data`, `arb-gambit`, `arb-strategy`, `arb-execution`, `arb-web-gateway`) in the host's IPC namespace
- Without it: `MediaDriver` cannot map the same shared-memory file in both publisher and subscriber → `Publication.offer()` silently drops all messages

**Why not Aeron UDP between containers:** kernel network stack traversal adds 3–5µs per hop even on loopback — would consume more than half the 10µs hot-path budget on infrastructure overhead alone

---

## Zero-GC Architecture

Zero-GC on the hot path means that no Java object is allocated between
the moment a market-data tick arrives from Aeron and the moment the
resulting order — if the strategy fires — is submitted back to Aeron.
Any allocation on this path will eventually trigger a garbage collection
pause. Even a concurrent G1 minor collection targeting a few hundred
microseconds is catastrophic when the arb window is measured in tens of
microseconds and the basis signal has a half-life shorter than the pause.

The system enforces the zero-GC invariant through three complementary
structural mechanisms. First, all SBE decoders are pre-allocated
`UnsafeBuffer` flyweights instantiated in the constructor of each
subscriber class. When `ArbSequencer` receives a fragment from Aeron,
it wraps the incoming `DirectBuffer` with a pre-allocated
`MarketDataTickDecoder` flyweight by calling
`decoder.wrap(buffer, offset, actingBlockLength, actingVersion)`. No
object is created. The decoder advances a position pointer and returns
primitive values through typed accessors.

Second, all price arithmetic uses Decimal4j's `long`-to-`long` API.
`navPerUnit`, `futuresFv`, `basis`, and `annualisedBasisBps` are all
`long` fields computed through fixed-point multiply-and-shift operations
with no boxed types at any point in the call chain.

Third, symbol lookups use Agrona's `Object2ObjectHashMap` with
pre-interned `String` keys. `store.get(symbol)` performs an array-index
identity comparison rather than allocating a `Map.Entry` or constructing
a `String` from the SBE byte field.

The three computation tiers and their constraints:

| Tier | Timing | GC constraint | What runs here |
|---|---|---|---|
| **Hot path** | < 10µs per tick | Zero-GC — no allocation permitted | `NavCalculator`, `FuturesFvCalculator`, `FvEngine` dispatch, `ArbSequencer` strategy fan-out, `OrderSink.submit()` |
| **Warm path** | ~30s periodic | Allocation permitted | `DividendCalendar` (Strata yield curves), `VolSurfaceCalibrator` (finmath-lib B-S IV), `SpreadVolEstimator` (Welford rolling σ) |
| **Cold path** | Offline / batch | Allocation unrestricted | `MonteCarloPositionSizer` (finmath-lib MC VaR → max lots), full Strata Greeks |

The warm-to-hot bridge is the critical design detail that makes the
three-tier layering work without requiring a lock. Warm-path threads
compute calibrated values — dividend present value, implied vol surface
intercept, hedge ratio β — and write them into `AtomicLong` fields using
`setRelease()`. The hot thread reads them using `getAcquire()`.
Acquire-release ordering provides the required happens-before guarantee:
the hot thread is guaranteed to observe the value written by the most
recent `setRelease()` call, without the cost of a full `StoreLoad`
memory barrier:

```java
// Warm thread (single writer, ~30-second period):
dividendPv.setRelease(Decimal4j.toLong(newDividendPv, SCALE_4));
impliedVolBps.setRelease(newImpliedVol);

// Hot thread (per-tick reader — no allocation, no full barrier):
long pv  = dividendPv.getAcquire();
long iv  = impliedVolBps.getAcquire();
long fv  = FuturesFvCalculator.compute(nav, riskFreeRate, pv, daysToExpiry);
```

`LongAdder` is explicitly excluded from this pattern. It is designed
for high-contention multi-writer increment workloads — it spreads
internal state across multiple `Cell` objects to reduce contention, and
its `sum()` method accumulates those cells non-atomically. The result
of `sum()` is not a coherent point-in-time snapshot of any single
writer's value; it is the accumulated total across all cells, which may
straddle two writes during a concurrent `add()`. More practically,
`LongAdder` has no `set(value)` method. `AtomicLong.setRelease/
getAcquire` is the correct, minimal-overhead primitive for a
single-writer / single-reader value handoff.

Zero-GC compliance is validated by two mechanisms. The `*ZeroGcTest`
classes in each module run the hot-path logic 100,000 iterations and
assert via JVM allocation profiling that exactly zero objects were
allocated. In CI, the same tests run with Epsilon GC
(`-XX:+UseEpsilonGC`), a no-op collector that aborts the JVM immediately
on any allocation — making a hot-path allocation an outright test
failure rather than a subtle performance regression caught much later.

---

## Two-Leg Index Arbitrage Mechanics

Every strategy in the system is a variant of the same two-leg trade
structure. Leg 1 takes a position in a derivative instrument — an index
future or an ETF — that is mispriced relative to its theoretical fair
value. Leg 2 simultaneously takes the opposite position in the underlying
cash instrument: either the basket of index constituent stocks weighted
to replicate the index, or a correlated derivative in a different market.
The two legs together form a delta-neutral spread with no net directional
exposure. Profit is realised when the price gap between the legs
converges — a convergence that is contractually guaranteed at futures
expiry and typically occurs within minutes or hours intraday as
competing arbitrageurs collectively close the gap.

The primary signal is the **basis**, defined throughout the system as:

```
basis_bps = (futures_price − fair_value) / fair_value × 10_000
```

Fair value is the theoretical futures price derived from the current spot
index NAV, the risk-free overnight rate, time to expiry in calendar days,
and the present value of expected dividends over the futures lifetime.
When the annualised basis exceeds a strategy-specific entry threshold —
typically 15 BPS for `HkexBasisArb` — the signal is active: sell the
overpriced futures, simultaneously buy the constituent basket at the
implied cheaper level. When the basis converges back toward zero, both
legs are unwound at a profit equal to the basis captured minus
transaction costs.

The constituent basket is constructed by `BasketSlicer` using reference
data from `ReferenceDataStore`. For an HSI futures basis arb, `BasketSlicer`
reads the 82 current HSI constituent weights, converts the target futures
notional to constituent share quantities accounting for lot sizes, and
generates one `OrderRequest` per constituent. The futures order and the
82 basket orders must be submitted in rapid sequence. The futures leg is
submitted first to lock in the entry price, because futures liquidity is
deeper and the futures price is the signal source.

Partial fills create residual delta risk. If only 40 of 82 constituent
orders fill before the market moves, the position is partially hedged —
long an incomplete basket against a fully short futures position, with
net long exposure to the unfilled constituents. `PositionBook` tracks
fill state per `orderId` from `ORDER_UPDATE` messages on stream 1003.
`ArbSequencer` queries `PositionBook` before allowing any strategy to
generate new signals: if a prior two-leg trade has outstanding partial
fills, the strategy enters a cooldown. `PositionBook` is not merely a
P&L calculator — it is an active constraint on signal generation.

Delta hedging extends beyond simple basket replication in two strategies.
`VolSkewBasisArb` adjusts the basis entry threshold dynamically based on
the current implied-vol put skew: when fear premium is elevated, the
fair-value estimate for the futures leg is adjusted upward, tightening
the effective threshold and reducing entry frequency. `HkCnIndexPairArb`
trades the HSI / CSI300 spread using a β coefficient estimated by
`SpreadVolEstimator`'s rolling Welford σ computation. The β adjusts the
relative leg size so that the pair trade is beta-neutral, not only
delta-neutral.

---

## Two-Layer Pricing Engine

`arb-gambit` contains the fair value computation that all strategies
depend on. Its design cleanly separates computation that must be current
to the nanosecond from computation that can tolerate being 30 seconds
stale, and provides a bridge between those two worlds that adds no
latency to the hot path.

On every `MarketDataTick` from stream 1001, `FvEngine` invokes
`NavCalculator` to compute the current net asset value of the index
basket. `NavCalculator.compute()` takes a pre-allocated `long[]` of
current constituent prices and a pre-allocated `long[]` of constituent
weights — both maintained by `ReferenceDataStore` and updated on each
tick — and returns a single `long` NAV value using Decimal4j
fixed-point multiply-accumulate. No objects are created. The result is
passed directly to `FuturesFvCalculator.compute()`, which reads the
risk-free rate and dividend PV from `AtomicLong` fields and applies the
standard cost-of-carry formula in fixed-point arithmetic. The resulting
`FvUpdate` is encoded into a pre-allocated `UnsafeBuffer` and published
to `FV_STREAM (1005)` in a single `Publication.offer()` call.

NAV must be recalculated on every tick because index constituent prices
change continuously with every trade. The HSI has 82 constituents; a
large trade in HSBC or Tencent moves the index by a measurable amount.
A stale NAV of even one second would cause the strategy to compare a
current futures price against an outdated fair value, generating a
phantom basis signal.

Dividends, by contrast, are discrete events known in advance from
exchange publications. The `DividendCalendar` computes the present value
of expected dividends over the futures lifetime using Strata's discount
curve infrastructure — bootstrapping the OIS curve from market rates,
computing payment dates using exchange calendar rules, and discounting
each payment back to today. This multi-step computation involves
`ImmutableBean` construction and boxed arithmetic that is fundamentally
incompatible with the hot path. But the result changes only when a
constituent stock goes ex-dividend (at most once per quarter per stock)
or when interest rates move materially. Running the dividend PV
computation every 30 seconds and bridging the result via
`AtomicLong.setRelease()` captures the economic accuracy of the
analytics with no cost to the hot-path latency budget.

The same argument applies to the implied-vol surface. `VolSurfaceCalibrator`
uses finmath-lib's `AnalyticFormulas` to fit a smile model to quoted
HKEX option prices, updating the surface every 30 seconds. The vol
surface feeds `VolSkewBasisArb`'s threshold adjustment, read hot-path
via `AtomicLong.getAcquire()`. Implied vol does not move tick-by-tick
in normal markets; 30-second staleness has no practical impact on
signal quality, while eliminating B-S calibration from the hot path
saves approximately 5µs per tick.

| Layer | Timing | GC constraint | Key classes |
|---|---|---|---|
| **Hot path** (per-tick) | < 10µs | Zero-GC mandatory | `NavCalculator`, `FuturesFvCalculator`, `FvEngine` |
| **Warm path** (~30s periodic) | 10ms–1s | Allocation OK | `DividendCalendar` (Strata), `VolSurfaceCalibrator` (finmath-lib), `SpreadVolEstimator` |
| **Cold path** (batch/offline) | No budget | Allocation unrestricted | `MonteCarloPositionSizer` (finmath-lib MC), full Strata Greeks |

---

## Strategy Dispatch Architecture

`ArbSequencer` is the central event loop of `arb-strategy`. It runs on
a single dedicated platform thread, polling all subscribed Aeron channels
in a busy-spin loop using Aeron's `FragmentAssembler` callback model.
On each fragment received, `ArbSequencer` extracts the SBE `templateId`
from the message header using a direct `getShort()` on the `UnsafeBuffer`
offset and dispatches to all registered strategies via a pre-compiled
switch statement:

```java
switch (templateId) {
    case MarketDataTickDecoder.TEMPLATE_ID     -> fanOut(t -> s.onMarketData(t, sink));
    case QuoteTickDecoder.TEMPLATE_ID          -> fanOut(t -> s.onQuote(t, sink));
    case MarketVolumeTickDecoder.TEMPLATE_ID   -> fanOut(t -> s.onMarketVolume(t, sink));
    case ReferenceDataRecordDecoder.TEMPLATE_ID-> fanOut(r -> s.onReferenceData(r));
    case FvUpdateDecoder.TEMPLATE_ID           -> fanOut(f -> s.onFvUpdate(f, sink));
}
```

Each strategy receives a read-only flyweight decoder wrapping the same
pre-allocated `UnsafeBuffer`. Strategy code reads fields directly from
the shared memory buffer without materialising any Java object. The
`fanOut` lambda is a pre-allocated field in `ArbSequencer` — it is not
allocated on every dispatch — and the JIT compiles it to a direct
call-site after warm-up.

A single-threaded event loop is preferred over per-strategy threads for
three reasons. First, it eliminates all lock contention between strategies
that share market state — `HkexBasisArb` and `MhiHsiBasisArb` both read
HSI constituent prices from `ReferenceDataStore`, and with a single
thread there is no possibility of one strategy reading partially updated
state while another processes a reference data record. Second, all
strategy working-set data — `PositionBook`, `ReferenceDataStore`, the
strategy instances — fits within a single thread's CPU cache footprint.
A multi-threaded design would cause constant cross-thread cache
invalidation as threads competed for the same cache lines in
`PositionBook`. Third, the latency profile is deterministic: with N
enabled strategies, each tick costs exactly N dispatch calls in the same
thread with no context switching overhead.

The eight current strategies and their market scope:

| ID | Class | Category | Markets | Signal source |
|---|---|---|---|---|
| A1 | `HkexBasisArb` | Index futures arb | HKEX | `annualisedBasisBps` from `FvUpdate` vs threshold |
| A2 | `MhiHsiBasisArb` | Index futures arb | HKEX | MHI/HSI cross-contract spread, scaled by lot ratio |
| B1 | `TwseEtfArb` | ETF arb | TAIFEX / TWSE | ETF market price vs `NavCalculator` output |
| B2 | `CrossBorderEtfArb` | ETF arb | HKEX / SGX | CSOP A50 ETF (HKD) vs SGX A50 futures (USD), FX-adjusted |
| C1 | `SsfBasisArb` | SSF arb | TAIFEX / TWSE | TSMC SSF vs TSMC spot cost-of-carry |
| C2 | `SsfCalendarSpreadArb` | SSF arb | TAIFEX | Near vs far-month TSMC SSF calendar spread |
| D1 | `HkCnIndexPairArb` | Pair trading | HKEX / CSI | HSI vs CSI300 β-adjusted Z-score (60-tick rolling) |
| E1 | `VolSkewBasisArb` | Vol-informed arb | HKEX | IV skew-adaptive basis entry threshold |

All strategies implement `com.arb.strategy.Strategy`:

```java
public interface Strategy {
    void onMarketData(MarketDataTickDecoder tick, OrderSink orders);
    void onFvUpdate(FvUpdateDecoder fv, OrderSink orders);
    default void onQuote(QuoteTickDecoder tick, OrderSink orders) {}
    default void onMarketVolume(MarketVolumeTickDecoder tick, OrderSink orders) {}
    default void onReferenceData(ReferenceDataRecordDecoder record) {}
}
```

`OrderSink` is the zero-GC write interface to `ORDER_STREAM (1002)`.
Its `submit()` method encodes an `OrderRequest` into a pre-allocated
`UnsafeBuffer` using the SBE encoder and publishes it in a single
`Publication.offer()` call, with no heap allocation at any point in the
call chain.

Strategies are registered in `StrategyRegistry` and independently
toggled at runtime via `config/strategies.properties` or through the
`ControlPanel` WebSocket command interface. Commands route through
`AeronControlPublisher` on `CONTROL_STREAM (1004)`. The emergency halt
command — a `SystemEvent` with `eventType=HALT` — is processed by
`ArbSequencer` before any strategy dispatch, cancelling all open orders
via `OrderSink.cancelAll()` and disabling fan-out for the remainder of
the session.

---

## Pre-Trade Risk Architecture

`RiskGateway` in `arb-execution` intercepts every `OrderRequest` from
`ORDER_STREAM (1002)` before it reaches `MockExchangeConnector`. Its
purpose is to prevent two categories of error: fat-finger errors
(orders with obviously wrong price or quantity due to a bug or data
corruption in strategy logic) and position limit breaches (orders that
would push gross exposure in a symbol beyond a configured risk threshold).

Fat-finger protection operates on two dimensions. A quantity check
rejects any order whose lot count exceeds a per-symbol maximum limit
configured in `config/risk.properties`. A price deviation check computes
the percentage distance between the order price and the most recently
observed `MarketDataTick` price for the same symbol: if the order price
deviates by more than 5% in either direction, the order is rejected and
a `SystemEvent` with `eventType=RISK_BREACH` is published to
`CONTROL_STREAM (1004)` for operator visibility. Both checks use `long`
arithmetic on pre-cached values — the last tick price per symbol is
stored in a pre-allocated `long[]` indexed by a pre-interned symbol ID
integer. The entire fat-finger check is three fixed-point multiplications
and two comparisons.

Position limit checks query `PositionBook` for the current gross
exposure per symbol, add the proposed order's notional, and compare
against the configured limit. `PositionBook` maintains `long[]` arrays
of fill quantities and average fill prices, updated on every
`OrderUpdate` from `ORDER_UPDATE_STREAM (1003)`. The complete position
limit evaluation — including notional computation — adds approximately
1–5µs to the order path and involves no heap allocation.

Separating risk from strategy logic provides a critical architectural
benefit beyond performance: the `RiskGateway` can be unit-tested,
audited, and adjusted independently of any strategy implementation.
Risk parameters are configured in `config/risk.properties` and reloadable
without restarting the strategy engine. In a production deployment, this
separation maps directly to the regulatory requirement that pre-trade
risk controls are independently verifiable by a compliance function with
no visibility into strategy logic.

---

## SBE Message Catalogue

All messages share `SCHEMA_ID=1`, `BYTE_ORDER=LITTLE_ENDIAN`. Prices and
index values use fixed-point scale 10⁴. Basis BPS values use scale 10².
Constituent weights use scale 10⁶. Implied volatility uses scale 10⁷
(20.00% IV → `2_000_000L`).

| ID | Message | Key Fields | Channel |
|---|---|---|---|
| 1 | `MarketDataTick` | symbol(12), exchange, price, qty, timestamp | MARKET_DATA (1001) |
| 2 | `OrderRequest` | symbol, side, price, qty, orderId, orderType | ORDER (1002) |
| 3 | `SystemEvent` | eventType, message | CONTROL (1004) |
| 4 | `QuoteTick` | symbol(12), exchange, iep, bidPrice, askPrice, timestamp | MARKET_DATA (1001) |
| 5 | `MarketVolumeTick` | symbol(12), exchange, iev, dailyVolume, timestamp | MARKET_DATA (1001) |
| 6 | `ReferenceDataRecord` | symbol(12), exchange, lotSize, tickSize, currency(3), constituentWeight | MARKET_DATA (1001) |
| 7 | `FvUpdate` | symbol(12), exchange, navPerUnit, futuresFv, basis, annualisedBasisBps, timestamp | FV (1005) |
| 8 | `OrderUpdate` | orderId, execStatus, fillQty, avgFillPrice, timestamp | ORDER_UPDATE (1003) |
| 9 | `LatencyStats` | category(8), 7 bucket counts, minNs, maxNs, avgNs, sampleCount, timestamp | LATENCY (1006) |

`LatencyStats` is published periodically by `arb-strategy` and
`arb-execution` to report tick-to-signal and signal-to-order latency
distributions. The 7 bucket counts represent a fixed histogram with
boundaries at 1µs, 5µs, 10µs, 50µs, 100µs, 500µs, and >500µs.
`arb-web-gateway` consumes `LATENCY_STREAM (1006)` and renders the
histogram in the `SystemHealth` dashboard view, giving operators
real-time visibility into whether the hot path is meeting its sub-10µs
budget.

---

## Aeron Channel Map

```
Channel: "aeron:ipc"

Stream 1001 — MARKET_DATA_STREAM
    Publishers:  arb-market-data (HkexFeedHandler, TaifexFeedHandler, CsiFeedHandler)
    Subscribers: arb-gambit (FvEngine), arb-strategy (ArbSequencer)
    Messages:    MarketDataTick (1), QuoteTick (4), MarketVolumeTick (5),
                 ReferenceDataRecord (6)

Stream 1002 — ORDER_STREAM
    Publishers:  arb-strategy (strategies via OrderSink)
    Subscribers: arb-execution (RiskGateway)
    Messages:    OrderRequest (2)

Stream 1003 — ORDER_UPDATE_STREAM
    Publishers:  arb-execution (MockExchangeConnector)
    Subscribers: arb-strategy (ArbSequencer → PositionBook), arb-web-gateway
    Messages:    OrderUpdate (8)

Stream 1004 — CONTROL_STREAM
    Publishers:  arb-web-gateway (AeronControlPublisher — GUI commands, halt)
    Subscribers: arb-strategy (ArbSequencer — halt, strategy enable/disable)
    Messages:    SystemEvent (3)

Stream 1005 — FV_STREAM
    Publishers:  arb-gambit (FvEngine)
    Subscribers: arb-strategy (ArbSequencer), arb-web-gateway (LiveMonitor)
    Messages:    FvUpdate (7)

Stream 1006 — LATENCY_STREAM
    Publishers:  arb-strategy, arb-execution
    Subscribers: arb-web-gateway (SystemHealth latency histogram)
    Messages:    LatencyStats (9)
```

---

## Library GC Audit

| Library | Version | Hot path? | GC profile | Use in this system |
|---|---|---|---|---|
| **Aeron** | 1.48.0 | ✅ Yes | Zero-GC — ring buffer, no allocation on `offer()`/`poll()` | IPC messaging backbone for all streams |
| **Agrona** | 2.2.1 | ✅ Yes | Zero-GC — `Object2ObjectHashMap.get()` is array lookup; `UnsafeBuffer` wraps existing memory | Symbol-to-record lookups; all byte-buffer work |
| **SBE Tool** | (Aeron bundled) | ✅ Yes | Zero-GC — generated flyweights wrap `DirectBuffer`; no `new` on encode or decode | All inter-process message encoding and decoding |
| **Decimal4j** | 1.0.3 | ✅ Yes | True zero-GC — `DecimalArithmetic` API is `long → long` throughout | All fixed-point price arithmetic on hot and warm paths |
| **OpenGamma Strata** | 2.12.56 | ❌ Warm/cold only | Allocates freely — `ImmutableBean`, boxed `Double` generics throughout | Yield curve bootstrapping, dividend discount, calendar arithmetic |
| **finmath-lib** | 6.0.20 | ⚠️ Analytic only | `AnalyticFormulas` is near-zero-GC (all primitives); MC paths allocate `RandomVariable[]` | Analytic B-S warm-path vol calibration; MC VaR cold-path sizing |
| **Vert.x** | 4.5.7 | ❌ Gateway only | Event-loop allocates per WebSocket message; outside trading thread | WebSocket bridging in arb-web-gateway |
| **H2** | 2.x | ❌ Gateway only | JDBC allocates freely | Audit log persistence in arb-web-gateway |
| **Cucumber** | 7.x | ❌ Test only | N/A | BDD integration test scenarios |
| **JUnit 5** | 5.10.2 | ❌ Test only | N/A | Unit tests including zero-GC 100k-iteration validation |

---

## Targeted Markets & Instruments

| Market | Exchange | Instruments |
|---|---|---|
| Hong Kong | HKEX | HSI Index Futures, Mini-HSI (MHI), Single Stock Futures (SSF), CSOP A50 ETF (2822.HK) |
| Taiwan | TAIFEX / TWSE | TAIEX Futures, TWSE 50 ETF (0050.TW), TSMC SSF (near + far month) |
| China (offshore) | SGX / CSI | SGX FTSE China A50 Futures (USD), CSI300 Index Futures (CNH, offshore proxy) |

---

> For strategy mechanics and a plain-English glossary of index arb
> terminology, see [docs/strategies.md](docs/strategies.md).
