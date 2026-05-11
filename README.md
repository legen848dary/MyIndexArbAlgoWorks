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

Java 21 is chosen over Go, C++, or Python for several concrete reasons.
Virtual Threads (JEP 444) allow warm-path analytics threads to block
freely on I/O or computationally heavy calibration loops without consuming
OS threads, while the hot path runs on a pinned platform thread that the
scheduler never preempts mid-tick. Record classes provide immutable,
structurally typed value objects — dividend schedule entries, reference
data records — at zero runtime cost compared to plain fields. Sealed
interfaces give the SBE message dispatch hierarchy an exhaustive,
statically verifiable type structure that the JIT compiler can fully
devirtualise.

Python is excluded because its GIL prevents true parallelism across
analytics tiers, and its object model allocates on every arithmetic
operation. Go is excluded because its garbage collector, while
generational and low-pause, still introduces jitter measurable at the
sub-100µs deadline the hot path demands. Go's GC scan latency is
non-deterministic at nanosecond scale in the way that Java's Epsilon GC
(a no-op collector that aborts immediately on any allocation) is not.
C++ would eliminate GC entirely but would require reimplementing the
entire analytics stack — Strata, finmath-lib, Agrona — from scratch.
Java 21 delivers production-grade JIT code quality, a mature HFT
ecosystem, and the discipline to achieve zero-GC on the hot path through
pre-allocation, without sacrificing expressiveness on the analytics
layers.

The JVM's JIT compiler is the additional factor that makes Java
competitive with C++ on the hot path. After warm-up, the JIT
inline-expands the entire `NavCalculator` → `FuturesFvCalculator` →
`FvEngine.dispatch()` call chain into a single compiled native method,
eliminating virtual dispatch overhead and letting the CPU's out-of-order
engine see the full arithmetic instruction stream. The resulting native
code quality is functionally equivalent to what a C++ compiler produces
for the same fixed-point multiply-accumulate operations.

### Aeron IPC

Aeron transports messages between processes over `/dev/shm` (POSIX shared
memory) using a lock-free single-producer single-consumer ring buffer.
The producer writes a message directly into the memory-mapped file; the
consumer polls the same physical memory from its own address space.
No kernel calls, no copies, no network stack. The result is measured
sub-microsecond inter-process latency — typically 200–500 ns loopback
on modern hardware — with zero heap allocation on either end.

Kafka is excluded because its broker architecture requires at least one
TCP round-trip per message even on a single host, adding milliseconds
of latency and introducing broker availability as a hard dependency.
ZeroMQ over `ipc://` transport avoids the network stack but still
crosses the kernel boundary on each send, adding several microseconds.
The Disruptor pattern is single-process only — it cannot span the
`arb-gambit` / `arb-strategy` module boundary, which is a hard
architectural requirement for process-level isolation and independent
deployment. gRPC serialises every message through Protobuf encoding and
adds HTTP/2 framing overhead, both of which are incompatible with a
zero-GC hot path.

Aeron's `Publication.offer()` returns immediately if the ring buffer has
space, or returns a negative back-pressure code if it is full. The
hot thread never blocks waiting for a slow consumer. Back-pressure codes
are monitored and published to `LATENCY_STREAM (1006)` for operator
visibility in the `SystemHealth` dashboard view.

### SBE Serialisation

Simple Binary Encoding generates Java encoder and decoder classes that
wrap a `DirectBuffer` as flyweights. There is no heap object created
during decode — the decoder is a struct that advances a position pointer
over a pre-allocated `UnsafeBuffer` and exposes primitive accessor
methods. `tick.price()` returns a `long` by reading 8 bytes from a
fixed offset; it constructs no Java object whatsoever.

This is a structural difference from Protobuf, which allocates a
`Message.Builder` on every decode and boxes all numeric fields to their
object equivalents. FlatBuffers avoids most decode allocation but still
requires an object-graph root pointer. JSON is excluded categorically:
even Jackson's streaming API allocates `String` objects for every field
name and string value encountered, and the text-to-number parsing cost
alone would exceed the entire hot-path latency budget.

For fixed-schema financial messages where every field is known at compile
time, SBE's schema-first flyweight model eliminates the entire
serialisation cost from the latency budget. Schema changes require a
code-generation step (`./gradlew :arb-common:generateSbeStubs`) that
regenerates all encoder/decoder classes from `arb-messages.xml`, making
schema drift between publisher and subscriber a compile-time error
rather than a runtime surprise.

### Decimal4j for Fixed-Point Arithmetic

IEEE 754 `double` is wrong for financial prices for two independent
reasons. First, most decimal fractions cannot be represented exactly in
binary floating point. The value 0.1 in `double` is a repeating binary
fraction — approximately 0.100000000000000005551. In a NAV calculation
summing 82 HSI constituent stock prices each multiplied by a weight
factor, these representation errors compound into a basis signal that
does not correspond to any real market condition. A strategy triggering
on a 5 BPS phantom basis caused by floating-point rounding would
generate spurious orders.

Second, every Java primitive `double` passed to a method expecting
`Double` — in any analytics library that uses generic types — is
auto-boxed to a heap-allocated `Double` object. This allocation is
invisible at the call site but real in the GC's accounting. On the hot
path, any such allocation is forbidden.

Decimal4j addresses both problems by storing all prices as `long` with
a fixed decimal scale (10⁴ for prices, 10² for basis BPS, 10⁶ for
constituent weights). Its `DecimalArithmetic` API accepts and returns
`long` exclusively — no boxing, no rounding error, no allocation.
`BigDecimal` is excluded because it allocates a `byte[]` backing array
for every value, making it no better than `Double` from a GC
perspective. The fixed-point convention — HSI 19000.00 stored as
`190_000_000L` — is applied at ingestion in the feed handlers and never
relaxed until the display layer in `arb-web-gateway`, where
`JsonMessages` formats the `long` back to a decimal string for the
WebSocket client.

### Agrona Collections

The JDK's `HashMap` allocates one `Map.Entry` node object per stored
key-value pair on the heap. Its `get()` method dereferences that node
through a pointer chain, and `HashMap.put()` allocates a new `Entry`
on every insertion. More critically, `HashMap.get()` calls `equals()`
on key comparison — for `String` keys, that involves `char[]` content
comparison even when the strings are interned.

Agrona's `Object2ObjectHashMap` uses open addressing with linear probing
over a flat `Object[]` array. There are no node objects — the key and
value for each slot sit in adjacent array positions. A `get()` call
computes a hash, jumps to the array index, and compares references in a
tight loop over contiguous memory. With pre-interned `String` keys
(reference equality, not `equals()`), the hot-path resolution of a
symbol to its constituent record is a single array index plus an
identity comparison.

For an 82-constituent HSI basket, `ReferenceDataStore` performs 82
symbol lookups per tick. With `HashMap`, that is 82 potential cache
misses chasing `Entry` node pointers scattered through the heap. With
Agrona's flat array layout, all 82 lookups walk a contiguous block of
memory that fits in L2 cache. All key `String` objects are pre-interned
at startup, so `get()` comparisons use identity equality throughout.

### OpenGamma Strata and finmath-lib

The system uses two financial analytics libraries, deliberately assigned
to different computation tiers, because no single library satisfies both
zero-GC and analytics-completeness requirements simultaneously.

OpenGamma Strata handles yield curve construction and dividend discount
models on the warm path. Strata is the most complete open-source
implementation of standard ISDA-style rate curve bootstrapping and ETD
product models available in Java, with support for calendar arithmetic,
day-count conventions, and multi-currency dividend schedules. Computing
dividend-adjusted futures fair value requires accurate interpolation of
the OIS discount curve at arbitrary future tenors — a task that requires
Strata's full curve infrastructure. Strata is excluded from the hot path
because it allocates freely by design: its `ImmutableBean` value types
create new instances on every construction.

The Strata Black-Scholes boxing trap is worth stating explicitly.
Strata's `BlackScholesFormulaRepository.price()` routes through
`NORMAL.getCDF(Double x)` where the type parameter `T` is `Double` (the
boxed type), not the primitive `double`. Every call produces two heap
allocations — one for argument boxing and one for the return value.
Calling this from any thread that shares a GC with the hot path can
trigger a minor collection at the worst possible moment.

finmath-lib fills the gap for Black-Scholes on the warm path. Its
`AnalyticFormulas` class implements standard B-S option pricing and
implied-vol Newton iteration using only primitive `double` arithmetic —
all method signatures accept and return `double`, no generics, no
boxing, near-zero-GC. `VolSurfaceCalibrator` uses `AnalyticFormulas` to
fit the implied-vol surface to quoted HKEX option prices on a 30-second
schedule.

For the cold path — Monte Carlo VaR and full-Greeks position sizing —
finmath-lib's Monte Carlo engine allocates `RandomVariable[]` arrays
and `double[]` path arrays extensively. This is acceptable because the
cold path runs offline, never on any thread sharing execution time with
the hot path. Results feed into strategy configuration as position size
limits, not per-tick decisions.

### Vert.x for the Web Gateway

`arb-web-gateway` bridges the zero-copy Aeron binary bus to WebSocket
JSON for the operator dashboard. Vert.x's non-blocking event-loop model
maps naturally to this requirement: a single verticle thread handles all
incoming WebSocket connections, message fan-out, and Aeron poll callbacks
through a single-threaded I/O loop driven by Netty. There are no
blocking operations on the Vert.x event loop — Aeron polling is a
non-blocking `poll()` call returning zero when no messages are available,
and WebSocket writes are queued to Netty's write buffer without blocking.

Embedding a WebSocket server directly in `arb-strategy` was rejected
because it would couple operator latency to the trading thread. A slow
WebSocket client — a reconnecting browser, a slow network — would apply
back-pressure to the same event loop that handles `onMarketData`
callbacks, potentially adding milliseconds of jitter to the hot path.
Vert.x in a separate container with its own JVM ensures that no amount
of dashboard activity can influence the trading process's latency.

### React 18, Vite, Tailwind CSS, Shadcn/UI, and Recharts

The operator dashboard is a React 18 single-page application served by
Nginx in production. Vite is chosen over Create React App or webpack
because its native ES-module dev server provides near-instantaneous
hot-module replacement even with TypeScript type-checking, which is a
meaningful advantage when tuning chart refresh rates and real-time
layout. React 18's concurrent rendering mode allows the P&L area chart
and the order book table to update from the same WebSocket data stream
without one update blocking the other's render cycle.

Tailwind CSS utility classes are used exclusively — no CSS-in-JS library
is introduced. Runtime style injection (styled-components, Emotion) adds
JavaScript work on every component render: generating a class-name hash,
inserting a `<style>` tag into the DOM, and applying the style. On a
dashboard refreshing P&L data every second across multiple chart
components, this overhead is measurable. Tailwind generates a static CSS
file at build time; component renders involve only class-name string
concatenation at the React level with no DOM mutation.

Shadcn/UI provides accessible, unstyled component primitives — cards,
tables, switch toggles, dialogs — copied directly into the project source
tree at `arb-dashboard/src/components/ui/`. Because Shadcn components
are plain TypeScript source, not a runtime library dependency, they carry
no bundle overhead beyond what they actually use. Material-UI, Chakra,
and Ant Design are excluded because they import entire component graphs
with their own animation and theming runtimes.

Recharts renders charts as SVG using React's virtual DOM, meaning chart
data updates go through React's normal reconciliation path. For the
latency histogram and basis spread line chart — updating at 1-second
intervals with small data deltas — React's diffing is efficient and
avoids the full canvas repaints that Canvas-based libraries perform on
every update. Zustand manages global state with fine-grained
subscriptions that limit re-renders to components that actually consume
the updated data slice.

### H2 for the Web Gateway Audit Log

The web gateway uses H2 as an embedded RDBMS for a lightweight audit log
of WebSocket messages and control events. A full PostgreSQL deployment
adds a network round-trip, a separate service lifecycle, connection pool
management, and a shared-nothing dependency that can block gateway
startup. For an audit log that is append-only, single-writer, and
accessed only through the gateway JVM, H2's embedded mode is the correct
tradeoff: it runs in the same process, persists to a local file, and
exposes the standard JDBC API without a network socket.

Chronicle Map or Chronicle Queue would be the zero-GC upgrade path for
this layer if the audit log ever moved onto a latency-sensitive thread.
Chronicle's off-heap memory-mapped files support sub-microsecond
persistence without touching the GC. That level of performance is not
required here because the gateway is explicitly outside the trading
thread's latency budget.

### Docker Compose with `ipc: host`

Aeron IPC over `/dev/shm` requires that all communicating processes share
the same POSIX IPC namespace. Docker containers run in isolated namespaces
by default — a `/dev/shm` memory segment created by one container is
invisible to another. The `ipc: host` setting in `docker-compose.yml`
places all trading-process containers (`arb-market-data`, `arb-gambit`,
`arb-strategy`, `arb-execution`, `arb-web-gateway`) into the host's IPC
namespace, restoring shared-memory visibility. Without this, Aeron's
`MediaDriver` cannot map the same shared-memory file in both publisher
and subscriber address spaces, and all `Publication.offer()` calls
silently drop messages.

The alternative — using Aeron over UDP (`aeron:udp`) between containers
on the same host — traverses the kernel network stack twice per message,
even for loopback traffic, adding several microseconds per hop. Given
that the entire hot-path budget from tick receipt to `FvUpdate`
publication is under 10µs, spending 3–5µs on inter-container transport
would consume more than half the budget on infrastructure overhead alone.

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
