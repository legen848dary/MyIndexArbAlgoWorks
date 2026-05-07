# Copilot Instructions

## Project Overview

This is a **prototype Index Arbitrage Algorithmic Trading System** targeting HK (HKEX), China (CSI), and Taiwan (TAIFEX) markets. The system is designed for ultra-low latency and zero-GC operation in the hot path.

## Role Context

Act as a **Principal Full-Stack Architect** specializing in HFT systems. Follow the phased implementation plan in `devdocs/MyIndexArbAlgoSystemPlan.md` rigorously — do not add features outside the plan's scope.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21 (Zero-GC), Aeron IPC, SBE |
| Frontend | React, TypeScript, Tailwind CSS, Shadcn/UI, Recharts |
| Ops | Docker, Docker Compose (with IPC shared-memory tuning) |

## Build & Run

**Backend** (Gradle multi-module — root of repo):
- Modules: `arb-common`, `arb-market-data`, `arb-strategy`, `arb-execution`, `arb-web-gateway`
- Frontend lives in `arb-dashboard/` at the repo root (Vite + React, not a Gradle module)

```bash
./gradlew build                                                   # Full build
./gradlew test                                                    # All tests
./gradlew :arb-common:test                                        # Single module tests
./gradlew :arb-common:test --tests "com.arb.PingPongTest"         # Single test
```

**Frontend** (once scaffolded):
```bash
npm run dev       # Dev server
npm run build     # Production build
npm test          # Tests
```

**Ops:**
```bash
docker compose up --build    # Start full stack
docker compose down          # Tear down
```

> **Docker IPC note:** Aeron uses shared memory (`/dev/shm`). The Compose file must mount a shared `ipc: host` or a named volume for `/dev/shm` so backend containers can exchange Aeron IPC messages.

## Architecture

```
                        ┌─────────────────────────────────────────────────┐
                        │               Backend (Java 21)                  │
  MarketDataGateway  →  Aeron IPC  →  Sequencer  →  StrategyEngine  →  ExecutionGateway
      (Feeds)          (SBE encoded)  (Disruptor)   (Zero-GC logic)   (OrderRequest SBE)
                                                                             │
                        └─────────────────────────────────────────────────┘
                                                                             │  REST/WebSocket
                        ┌─────────────────────────────────────────────────┐
                        │          Frontend (React + TypeScript)           │
                        │  Recharts dashboards · Shadcn/UI components      │
                        └─────────────────────────────────────────────────┘
```

- **MarketDataGateway** (`arb-market-data`): Feed handlers for HKEX, TAIFEX, CSI that normalize ticks and publish to Aeron.
- **Sequencer** (`arb-strategy`): Single-threaded `ArbSequencer` — deterministic event ordering, reads from `MARKET_DATA_CHANNEL`, dispatches to registered `Strategy`, writes signals to `ORDER_CHANNEL`.
- **StrategyEngine** (`arb-strategy`): Implements `Strategy` interface (`onMarketData(Tick)`, `onTimer()`). Calculates Fair Value via `IndexCalculator` and `BasisCalculator`. Strategies are enable/disable-configurable via a config file.
- **ExecutionGateway** (`arb-execution`): Decodes `OrderRequest` SBE, applies pre-trade risk checks, routes to mock `ExchangeConnector`. Fills returned to `ORDER_UPDATE_CHANNEL`. Mock adds 10–50µs random jitter.
- **WebGateway** (`arb-web-gateway`, Vert.x): Bridges Aeron → WebSocket for the GUI. Subscribes to `MARKET_DATA` and `ORDER_UPDATE` channels; pushes JSON to browser. Receives GUI commands (start/halt strategies) via REST/WS and writes to Aeron `CONTROL_CHANNEL`. Exposes port `8080`.
- **Dashboard** (`arb-dashboard`, Vite + React): Four views — Live Monitor (FV vs Market Price chart), Order Book (streaming fills), Control Panel (per-strategy on/off switches), System Health (latency histograms). Dark/Light mode toggle. Serves on port `3000`.

## Key Conventions

### Messaging
- All inter-component communication uses **Aeron IPC** via `AeronPublisher`/`AeronSubscriber` wrappers defined in `arb-common`.
- All messages are **SBE-encoded**. Schemas live in `arb-common/src/main/resources/sbe/arb-messages.xml`. Java stubs generated via `SbeTool`.
- Current message types:

| ID | Message | Published by | Key Fields |
|----|---------|-------------|------------|
| 1 | `MarketDataTick` | `arb-market-data` | symbol, exchange, price, qty, timestamp |
| 2 | `OrderRequest` | `arb-strategy` | symbol, side, price, qty, orderType |
| 3 | `SystemEvent` | any | eventType, timestamp, message |
| 4 | `QuoteTick` | `arb-market-data` | symbol, exchange, iep, bidPrice, askPrice, timestamp |
| 5 | `MarketVolumeTick` | `arb-market-data` | symbol, exchange, iev, dailyVolume, timestamp |
| 6 | `ReferenceDataRecord` | `arb-market-data` | symbol, exchange, lotSize, tickSize, currency, constituentWeight |

### Zero-GC Hot Path
- **No object allocation in the hot path.** Use `agrona.collections.Int2ObjectHashMap` for constituent lookups (not `HashMap`).
- Prices are normalized to `long` (fixed decimal) on ingestion — never use `double` in the hot path.
- Validate zero-GC compliance with JFR or the Epsilon GC.

### Strategy Interface
All strategies extend the `Strategy` interface:
```java
interface Strategy {
    void onMarketData(MarketDataTickDecoder tick, OrderSink orders);
    void onQuote(QuoteTickDecoder tick, OrderSink orders);          // IEP / bid-ask
    void onMarketVolume(MarketVolumeTickDecoder tick, OrderSink orders); // IEV / daily volume
    void onReferenceData(ReferenceDataRecordDecoder record);        // static ref data — no orders
    void onTimer(long nowNanos, OrderSink orders);
}
```

The four strategy implementations are: `HkexBasisArb`, `TwseEtfArb`, `SsfBasisArb`, `CrossBorderEtfArb`.

### Pre-Trade Risk Rules
- Fat finger: reject if `Size > MaxLimit` or price deviates > 5% from last tick.
- Max position: hard stop on gross exposure.

### Frontend
- Use **Shadcn/UI** components as the base — do not introduce other component libraries.
- Charts (P&L curves, basis spreads, latency histograms) use **Recharts**.
- Tailwind utility classes only — no custom CSS files.
- Data feeds from backend via WebSocket or REST (to be defined per phase); keep all market data types in a shared `types/` directory.

### Ops
- Each backend module runs as its own Docker container; the frontend is a separate container.
- Aeron IPC between containers requires `ipc: host` in `docker-compose.yml` — do not use TCP Aeron channels between containers in the prototype.

### Testing
- **Unit tests:** JUnit 5. Key test: `PingPongTest` (10,000 Aeron IPC messages, < 10µs loopback latency).
- **BDD:** Cucumber. Feature files: `market_data.feature` (tick normalization), `end_to_end.feature` (basis widening → 51 orders: 1 future + 50 stocks).
- `BasketSlicer` breaks a basket `OrderRequest` into individual stock orders — use "Market" order type for prototype fills.

## Development Phases

Follow `devdocs/MyIndexArbAlgoSystemPlan.md` phase by phase. After completing each phase, confirm: *"I have completed Phase [X]. Tests Passed: [Y]. Ready for Phase [X+1]?"*

| Phase | Name | Focus |
|-------|------|-------|
| 0 | The Spinal Cord | Gradle scaffold, Aeron IPC backbone, SBE schemas + stubs |
| 1 | The Eyes | Market data feed handlers (HKEX, TAIFEX, CSI), normalization |
| 2 | The Brain | Sequencer + FV/Basis calculators (Zero-GC verified) |
| 2a | The Senses | Extended market data types (QuoteTick/IEP, MarketVolumeTick/IEV, ReferenceDataRecord) + ReferenceDataStore |
| 3 | The Alpha | Four arb strategy implementations + config toggle |
| 4 | The Hands | Execution gateway, pre-trade risk, `BasketSlicer` |
| 5 | The Face | `arb-web-gateway` (Vert.x) + React dashboard (4 views, dark mode) |
| 6 | The Vessel | Dockerfiles + Docker Compose (`ipc: host`, `start-all.sh`) |
| 7 | Verification | Backtest replay → P&L visualization in GUI |
