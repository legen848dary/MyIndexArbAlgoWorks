# Index Arbitrage Strategies Guide

> **Purpose:** Plain-English explanation of every strategy in this system, including all jargon.  
> Intended audience: Index-arb traders and anyone who needs to explain these strategies without reading Java code.

---

## Table of Contents

1. [Primer — What Is Index Arbitrage?](#1-primer--what-is-index-arbitrage)
2. [Index Arb Jargon Glossary](#2-index-arb-jargon-glossary)
3. [Strategy Overview](#3-strategy-overview)
4. [Group A — Index Futures Arbitrage](#group-a--index-futures-arbitrage)
   - [A1 — HkexBasisArb](#a1--hkexbasisarb)
   - [A2 — MhiHsiBasisArb](#a2--mhihsisbasisarb)
5. [Group B — ETF Arbitrage](#group-b--etf-arbitrage)
   - [B1 — TwseEtfArb](#b1--twseetfarb)
   - [B2 — CrossBorderEtfArb](#b2--crossborderetfarb)
6. [Group C — Single Stock Futures (SSF) Arbitrage](#group-c--single-stock-futures-ssf-arbitrage)
   - [C1 — SsfBasisArb](#c1--ssfbasisarb)
   - [C2 — SsfCalendarSpreadArb](#c2--ssfcalendarspreadadarb)
7. [Group D — Pair Trading](#group-d--pair-trading)
   - [D1 — HkCnIndexPairArb](#d1--hkcnindexpairarb)
8. [Group E — Volatility-Informed Arbitrage](#group-e--volatility-informed-arbitrage)
   - [E1 — VolSkewBasisArb](#e1--volskewbasisarb)
9. [Supporting Analytics](#9-supporting-analytics)

---

## 1. Primer — What Is Index Arbitrage?

> **Start here if you are new to index arb trading.**

---

### The One-Sentence Version

Index arbitrage is the practice of profiting from price discrepancies between an index (or a product representing an index) and related instruments — futures, ETFs, or correlated indices — when the gap between them exceeds the cost of executing the trade.

---

### Why Do Price Gaps Exist at All?

In a perfectly efficient market, related instruments should always be correctly priced relative to each other. In the real world, gaps appear for several reasons:

| Cause | Example |
|---|---|
| **Order flow imbalance** | A large pension fund sells 5,000 HSI futures contracts at market. The futures price drops below fair value for a few seconds. |
| **Market maker hedging lag** | A market maker quotes HSI futures but hasn't yet repriced MHI futures. Momentary spread between them. |
| **Currency or settlement friction** | An ETF trading in HKD and its equivalent CNH futures take time to be repriced after an FX move. |
| **Information asymmetry** | News hits one market faster than another (e.g., CSI300 reacts to China data before HSI). |
| **Structural constraints** | Some investors can only trade ETFs (not futures). Their buying pressure pushes the ETF above NAV. |

Crucially: **these gaps are temporary**. Markets are competitive and full of arbitrageurs looking for the same opportunities. The arb trade itself is what closes the gap — your selling pressure on the expensive instrument, and buying pressure on the cheap one, forces prices back together.

---

### The Core Arb Trade — Step by Step

Here is the fundamental structure of every index arb trade in this system:

```
Step 1: IDENTIFY a mispricing
        → "HSI futures are trading 80 BPS above their fair value"

Step 2: SELL the expensive instrument
        → Sell HSI futures at the elevated price

Step 3: BUY the equivalent cheap instrument simultaneously
        → Buy the underlying basket of HSI stocks (or a proxy)

Step 4: WAIT for convergence
        → At expiry, futures must settle exactly at the cash index level
        → The gap closes; you pocket the difference

Step 5: UNWIND
        → The two legs (futures + basket) cancel out; realise the P&L
```

**The key word is "simultaneously."** If you only do Step 2 (sell futures) without Step 3 (buy the basket), you have a directional bet, not an arb. True arb is market-neutral — you don't care whether the index goes up or down. You only care that the gap closes.

---

### Why Is It Profitable?

Three structural reasons:

**1. Convergence is mathematically guaranteed at expiry**

Index futures must settle at the official index closing value on expiry day. This is not a bet — it is a contractual certainty. If you buy the cash basket and sell futures at a premium, you are locking in the premium as riskless profit, to be realised at expiry.

**2. Intraday convergence happens even faster**

You don't have to wait until expiry. Market makers and other arbitrageurs are constantly watching the same gap. Their activity closes mispricings within seconds to minutes. You can enter and exit within a single trading session.

**3. High frequency = many small edges compound**

Each individual arb trade may only earn 20–100 BPS. But a system that executes hundreds of such trades per day, compounding small consistent edges with disciplined risk management, generates strong risk-adjusted returns. This is why HFT firms dominate index arb — speed matters more than size of edge.

---

### Why Does It Require Speed and Automation?

A human trader watching screens cannot react fast enough:

| What happens | Timescale |
|---|---|
| Large order hits HSI futures | ~1 millisecond |
| Basis deviation appears | ~1–5 milliseconds |
| Competing arb systems detect and trade | ~1–10 milliseconds |
| Gap closes | ~10–500 milliseconds |

By the time a human notices the deviation, picks up the phone, and executes both legs, the opportunity is gone. This is why automated systems like this one use:

- **Aeron IPC** — inter-process messaging in nanoseconds via shared memory
- **SBE (Simple Binary Encoding)** — zero-allocation message parsing
- **Pre-allocated order buffers** — no garbage collection pauses on the hot path
- **Co-located servers** — physically close to exchange matching engines

---

### The Risk Side — What Can Go Wrong?

Index arb is often described as "riskless." It is not. The risks are:

| Risk | Description | Mitigation in this system |
|---|---|---|
| **Execution risk** | You fill one leg but not the other. You're now directional. | `RiskGateway` checks both sides; `MockExchangeConnector` simulates realistic fills |
| **Gap risk** | Market crashes between your futures sell and basket buy. Basis widens further before closing. | `MonteCarloPositionSizer` limits lot size to 95% VaR budget |
| **Model risk** | Your fair value calculation is wrong (wrong dividends, wrong carry rate). You think there's an arb but there isn't. | `FuturesFvCalculator` (arb-gambit) uses OpenGamma Strata for warm-path FV; hot-path uses carry factor inline |
| **Liquidity risk** | You can't buy/sell the full basket at the prices you need. | `BasketSlicer` breaks large orders into smaller child orders |
| **Fat-finger risk** | Software bug sends absurdly large order. | `RiskGateway` rejects orders exceeding qty/price limits |
| **Correlation breakdown** | In pair arb, the historical β relationship breaks (e.g., political event). | Z-score entry threshold; position limits; AtomicLong β is live-updatable |

---

### The Markets in This System

| Market | Exchange | Products traded |
|---|---|---|
| **Hong Kong** | HKEX | HSI Futures, MHI Futures, CSOP A50 ETF (2822.HK) |
| **Taiwan** | TAIFEX / TWSE | TAIEX Futures, 0050.TW ETF, TSMC SSF (2330.TW) |
| **China** | CSI / SHFE | CSI300 Index (used as pair-arb reference) |

These markets are chosen because:
- They are deeply correlated (all driven by Greater China economic conditions)
- They trade in different time zones and currencies, creating regular structural gaps
- HKEX and TAIFEX have liquid, low-latency futures markets suitable for HFT

---

### What This System Does, In Plain English

```
1. Receives live market data from HKEX, TAIFEX, and CSI feeds
2. Calculates the fair value (FV) for each futures/ETF instrument in real time
3. Compares market prices to FV, computing the basis and annualised basis BPS
4. Runs 8 strategies simultaneously, each watching for a specific type of mispricing
5. When a strategy fires, it sends an order through the risk gateway (size & price checks)
6. If approved, the order goes to the exchange connector for execution
7. Fill confirmations flow back to the dashboard as P&L
8. The whole loop — market data in to order out — takes microseconds
```

---

### Quick Mental Model: The Three Types of Arb in This System

| Type | The bet | Convergence mechanism |
|---|---|---|
| **Carry arb** (A1, C1) | Futures price vs. calculated FV | Futures must settle at cash index at expiry |
| **NAV arb** (B1, B2) | ETF price vs. underlying basket NAV | Authorised participants create/redeem ETF units |
| **Statistical arb** (D1, C2, E1) | Spread between correlated instruments vs. its historical mean | Markets re-price as information diffuses; mean reversion |

---

*Continue to [Section 2 — Jargon Glossary](#2-index-arb-jargon-glossary) for definitions of all technical terms.*

---

## 2. Index Arb Jargon Glossary

This section defines every technical term used in the strategies. Read this first.

---

### Cash Index
The *theoretical* current value of a market index — calculated right now from the live prices of all constituent stocks. Examples:
- **HSI** — Hang Seng Index (Hong Kong, ~80 stocks)
- **CSI300** — China Securities Index top 300 (mainland China)
- **TAIEX** — Taiwan Stock Exchange Composite Index

The cash index is *not directly tradeable* — you can't buy the "HSI" itself. You trade it via futures or ETFs.

---

### Index Futures
A contract to buy or sell the index at a fixed price on a *future date* (the expiry). You pay a small margin upfront, not the full value. Examples:
- **HSI Futures** — traded at HKEX (HK Futures Exchange), HK$50 per index point
- **MHI Futures** — mini version of HSI, HK$10 per index point (1/5 the size)
- **TAIEX Futures** — traded at TAIFEX (Taiwan Futures Exchange)

---

### Fair Value (FV)
What a futures contract *should* be priced at today, based on objective financial math:

```
Fair Value = Spot Index × (1 + risk-free rate × days-to-expiry/365) − PV(dividends)
```

**Example:** HSI = 19,000. Risk-free rate = 2.5%. 30 days to expiry. No dividends.
```
FV = 19,000 × (1 + 0.025 × 30/365) ≈ 19,039
```
So futures *should* trade at about 19,039 if the index is 19,000.

---

### Basis
The difference between the actual futures market price and the fair value:

```
Basis = Futures Market Price − Fair Value
```

| Basis value | Meaning | Arb action |
|---|---|---|
| **Positive** | Futures are expensive (premium) | Sell futures, buy basket |
| **Negative** | Futures are cheap (discount) | Buy futures, sell basket |
| **Near zero** | No edge | Do nothing |

---

### Annualised Basis (in BPS)
The raw basis in index points is hard to compare across different expiry dates and index levels. We normalise it:

```
Annualised Basis BPS = (Basis / FV) × (365 / days-to-expiry) × 10,000
```

**Example:** Basis = 50 pts, FV = 19,000, 30 days to expiry:
```
(50/19,000) × (365/30) × 10,000 ≈ 320 BPS
```

This means the futures are trading at a 3.2% annualised premium. You can now compare a 30-day contract to a 90-day contract on a fair basis.

---

### Basis Points (BPS)
1 BPS = 0.01%. The standard unit for small percentage differences in finance.

| BPS | Percentage |
|---|---|
| 1 BPS | 0.01% |
| 10 BPS | 0.1% |
| 100 BPS | 1.0% |
| 1,000 BPS | 10.0% |

---

### Cost of Carry
The theoretical premium futures have over the spot (cash) price, reflecting the cost of *financing* a position until expiry. If you don't want to pay for a stock today, you buy a futures contract instead — but the seller needs compensation for holding the risk, so the future is priced higher:

```
Cost of Carry = Spot Price × risk-free rate × (days-to-expiry / 365)
```

Dividends *reduce* the carry (they compensate the holder of the stock, not the futures buyer).

---

### NAV (Net Asset Value)
For an ETF: the total market value of all its underlying stocks, divided by the number of shares outstanding. This is what the ETF is *truly worth*.

```
NAV per unit = Total value of all stocks in the basket / Total ETF shares
```

An ETF should trade close to its NAV. Any persistent deviation is an arb opportunity.

---

### ETF (Exchange Traded Fund)
A fund that holds a basket of stocks (usually tracking an index) and trades on a stock exchange like a single share. Examples in this system:
- **0050.TW** — Yuanta Taiwan 50 ETF. Holds the top 50 TWSE stocks. Tracks TAIEX.
- **2822.HK** — CSOP FTSE China A50 ETF. Holds China's 50 largest A-shares. Trades in HKD on HKEX.

---

### IEP (Indicative Equilibrium Price)
The real-time *estimated NAV* for an ETF, published during the trading day based on live constituent prices. Also called "indicative NAV". Taiwan Stock Exchange publishes this for 0050.TW.

---

### SSF (Single Stock Futures)
A futures contract on a *single stock*, not an index. TAIFEX (Taiwan) offers SSFs on major stocks like TSMC. The same arbitrage logic applies: the SSF should trade at spot + carry − dividends.

**TSMC (2330.TW):** Taiwan Semiconductor Manufacturing Company. The world's largest chip foundry, making up ~30% of TWSE market cap. The biggest SSF in Taiwan.

---

### Calendar Spread
The price difference between two futures contracts on the *same underlying* but *different expiry dates*:

```
Calendar Spread = Far-month price − Near-month price
```

Theoretically this equals the financing cost for the extra weeks between the two expiries. If the actual spread deviates significantly, it's an arb.

---

### Implied Volatility (IV)
The market's *expectation* of future price swings, derived from options prices. If HSI options are expensive, IV is high — the market is betting on big moves.

- High IV → market is fearful or uncertain
- Low IV → market is calm

---

### Realised Volatility (RV)
The *actual* price swings observed in the market over a recent historical window (e.g., last 20 days). Measured as the rolling standard deviation of daily returns.

The gap between IV and RV is the "volatility premium" — options traders typically pay more than the realised risk justifies (fear premium).

---

### Beta (β)
Measures how much one asset moves relative to another:

```
HSI return ≈ β × CSI300 return
```

If β = 0.8, then for every 1% CSI300 moves, HSI tends to move 0.8%. Used in pair trading to construct a "hedge ratio" — how many CSI300 contracts to hold per HSI contract to neutralise directional exposure.

---

### Z-score
Measures how unusual a value is, relative to its historical distribution:

```
Z = (current value − historical mean) / historical standard deviation
```

| Z-score | Meaning |
|---|---|
| Z = 0 | Exactly at the mean |
| Z = 1 | 1 standard deviation above mean (~16% of observations are more extreme) |
| Z = 2 | 2 standard deviations above mean (~2.3% of observations are more extreme) |
| Z > 2 | Unusual — potential mean-reversion signal |

---

### Mean Reversion
The tendency for prices (or spreads) to return to their historical average after an extreme move. Most index arb strategies rely on this — a mispricing that isn't caused by a fundamental change will eventually correct itself.

---

### MHI (Mini Hang Seng Index Futures)
A smaller-sized HSI futures contract — 1/5 the contract value of a full HSI futures. Traded on HKEX alongside the full-size HSI futures. Both track exactly the same index, so:

```
HSI price = 5 × MHI price  (always, by definition)
```

Any deviation is a mechanical arbitrage with no directional risk.

---

### Cross-border Arbitrage
When the same underlying asset (or equivalent) is listed on two different exchanges in different currencies. After converting via the FX rate, any price gap is arbitrageable. Example: CSOP A50 ETF on HKEX (HKD) vs. SGX A50 Futures (USD/CNH).

---

## 3. Strategy Overview

| ID | Name | Group | Markets | Signal source | Entry condition |
|---|---|---|---|---|---|
| **A1** | HkexBasisArb | Index Futures Arb | HKEX | `FvUpdate` (annualised basis) | Basis > 50 BPS |
| **A2** | MhiHsiBasisArb | Index Futures Arb | HKEX | `MarketDataTick` (both contracts) | `|HSI − 5×MHI| / HSI > 2 BPS` |
| **B1** | TwseEtfArb | ETF NAV Arb | TAIFEX / TWSE | `QuoteTick` (IEP) + `FvUpdate` (NAV) | `|ETF price − NAV| / NAV > 20 BPS` |
| **B2** | CrossBorderEtfArb | Cross-border ETF Arb | HKEX / SGX | `FvUpdate` (HKD NAV) + live FX | CNH-adjusted basis > 30 BPS |
| **C1** | SsfBasisArb | SSF Basis Arb | TAIFEX / TWSE | `MarketDataTick` (SSF + spot) | `(SSF − spot) / spot > carry + 15 BPS` |
| **C2** | SsfCalendarSpreadArb | SSF Calendar Spread | TAIFEX | `MarketDataTick` (near + far) | Spread deviates > 2σ from theoretical |
| **D1** | HkCnIndexPairArb | Pair Trading | HKEX / CSI | `MarketDataTick` (both indices) | β-adjusted Z-score > 2.0σ |
| **E1** | VolSkewBasisArb | Vol-Adaptive Arb | HKEX | `FvUpdate` + IV/RV bridges | Basis > adaptive threshold (vol-adjusted) |

---

## Group A — Index Futures Arbitrage

The most classic and liquid form of index arbitrage: exploit mispricings between index futures and the calculated fair value of the cash index.

---

### A1 — HkexBasisArb

**The Opportunity**

HSI Futures trade on HKEX. The FV engine continuously calculates what the futures *should* be worth (the fair value). When the futures deviate enough from fair value — either too expensive or too cheap — the strategy fires.

**The Trade**

| Situation | Action | Why |
|---|---|---|
| Futures expensive (basis > +50 BPS annualised) | **Sell** HSI futures | Futures will fall back to FV at expiry |
| Futures cheap (basis < −50 BPS annualised) | **Buy** HSI futures | Futures will rise back to FV at expiry |
| Basis between ±10 BPS | **Nothing** | No worthwhile edge after costs |

**How the Signal Arrives**

The `arb-gambit` FV engine publishes a `FvUpdate` message every time it recalculates. That message contains `annualisedBasisBps` (the 4-decimal fixed-point version of the BPS figure). The strategy reads this directly off the Aeron stream with zero memory allocation.

**Risk**

- *Gap risk:* market jumps dramatically before you can hedge the cash basket side
- *Liquidity risk:* the basket may be hard to execute near the close

**Prototype parameters:** Entry = 50 BPS, exit-suppression = 10 BPS, max 10 lots.

---

### A2 — MhiHsiBasisArb

**The Opportunity**

HKEX lists two contracts on the same Hang Seng Index:
- **HSI Futures** — HK$50 per index point
- **MHI Futures (Mini)** — HK$10 per index point (1/5 the size)

They track exactly the same index. Therefore the price relationship `HSI = 5 × MHI` must always hold. Any deviation is pure mechanical arbitrage with no index direction risk.

**Why Deviations Happen**

- Institutional flows that hit only one contract size (large orders on HSI, retail on MHI)
- Momentary liquidity imbalances around large prints
- Market maker re-hedging activity

**The Trade**

```
spread = HSI price − 5 × MHI price
```

| Situation | Action |
|---|---|
| `spread > 0` (HSI expensive) | Sell HSI, Buy 5× MHI |
| `spread < 0` (MHI expensive) | Buy HSI, Sell 5× MHI |

**Why It Works**

No directional bet is needed. Both contracts converge to the same final settlement value on expiry day. Even intraday, market makers force them back into alignment within seconds.

**Prototype parameter:** Threshold = 2 BPS (very tight — this arb is fast and low-margin).

---

## Group B — ETF Arbitrage

ETF arbitrage exploits the gap between an ETF's market price and its underlying NAV. Authorised participants (large institutions) can create/redeem ETF units for the actual basket, which enforces the convergence.

---

### B1 — TwseEtfArb

**The ETF:** `0050.TW` — Yuanta Taiwan 50 ETF. Holds the top 50 companies on TWSE, weighted by market cap.

**The Opportunity**

During trading hours, `0050.TW` can trade above or below its NAV. The gap rarely persists because authorised participants can:
- **Create** new ETF units: buy the 50 stocks, deliver to the fund, receive ETF shares at NAV
- **Redeem** existing ETF units: hand in ETF shares, receive the 50 stocks at NAV

This creation/redemption mechanism enforces NAV parity. Our strategy captures the gap *before* it closes.

**Signals Used**

1. **IEP (Indicative Equilibrium Price)** — arrives via `QuoteTick`. This is the TWSE's own real-time NAV estimate during trading hours.
2. **`navPerUnit`** — arrives via `FvUpdate`. Our `NavCalculator` in `arb-gambit` computes this from constituent prices.

**The Trade**

| Situation | Action |
|---|---|
| ETF market price > NAV by > 20 BPS | **Sell** ETF (overpriced) |
| ETF market price < NAV by > 20 BPS | **Buy** ETF (underpriced) |

**Prototype parameter:** Threshold = 20 BPS, max 10 lots.

---

### B2 — CrossBorderEtfArb

**The Assets**

- **2822.HK** — CSOP FTSE China A50 ETF. Trades on HKEX in HKD. Holds China's 50 largest A-share companies.
- **SGX FTSE China A50 Futures** — trades in Singapore in USD/CNH. References the same underlying index.

**The Opportunity**

Same index, two different markets, two different currencies. After converting HKD → CNH via the live FX rate, the two prices should be equivalent. Any residual gap is the cross-border basis.

**How the Signal is Computed**

```
1. Get navPerUnit for 2822.HK  (HKD)
2. Convert to CNH:  navCNH = navHKD × FX_rate(HKD→CNH)
3. Get futuresFv   (CNH)
4. cnhBasis = (futuresFv − navCNH) / navCNH   (expressed in BPS)
5. If cnhBasis > 30 BPS → SELL the expensive side
```

**The Risk**

The FX rate can move between order entry and settlement. The `fxRateHkdCnh100` is updated in real-time via a warm-path bridge (`AtomicLong`), so the strategy always uses a fresh rate.

**Prototype parameters:** Threshold = 30 BPS (wider than B1 to account for FX slippage).

---

## Group C — Single Stock Futures (SSF) Arbitrage

SSF arb applies the same carry-model logic used for index futures, but to single stocks. TAIFEX offers SSFs on blue-chip Taiwan stocks, the most liquid being TSMC (2330.TW).

---

### C1 — SsfBasisArb

**What the "correct" price of a TSMC SSF should be**

```
SSF Fair Value = TSMC Spot Price
              × (1 + 2.5% annual rate × days-to-expiry/365)
              − Present Value of dividends before expiry
```

If TSMC is at 800 TWD and there are 30 days to expiry (no dividend):
```
FV ≈ 800 × (1 + 0.025 × 30/365) ≈ 801.64 TWD
```

So the SSF *should* trade at roughly 801.64. If it's trading at 804, it's overpriced by 2.36 TWD ≈ 29 BPS above what the carry formula says. That's the arb.

**The Trade**

```
spread = (ssfPrice − spotPrice) / spotPrice   [in BPS]
if spread > carry_BPS + 15 BPS buffer → SELL SSF, BUY spot stock
```

We add a 15 BPS buffer to cover transaction costs (bid-ask spreads, exchange fees).

**Why the Dividend PV Matters**

If TSMC goes ex-dividend before the SSF expires, the SSF price will drop by the dividend amount on ex-date. Our `dividendPv` bridge (updated by the warm-path `DividendCalendar`) adjusts the fair value accordingly.

**Prototype parameters:** Carry = 2.5%, buffer = 15 BPS, max 50 lots (SSF lots are smaller than index lots).

---

### C2 — SsfCalendarSpreadArb

**The Setup**

Two TSMC SSF contracts are traded simultaneously:
- **TSMC-SSF-NEAR** — expires in ~30 days
- **TSMC-SSF-FAR** — expires in ~90 days

**The Theoretical Spread**

The far contract should cost more than the near contract by exactly the additional carry:

```
Theoretical spread = Near price × 2.5% × (90−30 days)/365
                   = Near price × 2.5% × 60/365
                   ≈ Near price × 0.41%
```

**The Signal**

We track the rolling standard deviation (σ) of the actual observed spread using `SpreadVolEstimator`. When the observed spread deviates from the theoretical spread by more than 2σ, we enter:

```
if |observedSpread − theoreticalSpread| > 2 × σ:
    if observedSpread > theoretical: SELL far, BUY near
    if observedSpread < theoretical: BUY far, SELL near
```

**Why Use σ Instead of a Fixed Threshold?**

Markets are more volatile at some times than others (earnings, macro events). Using a rolling standard deviation means we adapt automatically — requiring a bigger deviation during choppy markets before committing capital.

**Prototype parameters:** σ multiplier = 2, carry rate = 2.5%.

---

## Group D — Pair Trading

Pair trading doesn't require a mathematical "fair value" formula. Instead, it exploits the *statistical relationship* between two correlated assets. When they diverge from their historical relationship, we bet on reversion.

---

### D1 — HkCnIndexPairArb

**The Pair**

- **HSI** — Hang Seng Index (Hong Kong)
- **CSI300** — Top 300 China A-share companies (mainland)

Both measure the performance of Greater China equities. They are highly correlated (driven by the same macro factors: China growth, RMB policy, trade flows) but are priced in different markets with different rules.

**The Hedge Ratio (β)**

HSI doesn't move one-for-one with CSI300. Historically, HSI moves about 80% as much:

```
HSI return ≈ 0.8 × CSI300 return     → β = 0.8
```

So the "hedge-adjusted spread" is:

```
spread = HSI price − 0.8 × CSI300 price
```

**The Signal**

The spread isn't constant — it has a mean and standard deviation over recent history. We compute the Z-score:

```
Z = (spread − rolling_mean) / rolling_σ
```

| Z-score | Interpretation | Action |
|---|---|---|
| Z > +2.0 | HSI unusually expensive vs CSI300 | Sell HSI, Buy CSI300 |
| Z < −2.0 | CSI300 unusually expensive vs HSI | Buy HSI, Sell CSI300 |
| |Z| < 0.5 | Spread back to normal | Close position |

**Key Risks**

- **Regime change:** The β=0.8 relationship is calibrated on recent history. A structural break (e.g., major Hong Kong political event, China market circuit breaker) can make β unstable.
- **Currency:** HSI is in HKD, CSI300 is in CNY. The HKD/CNY peg is stable but not immovable.
- This is a *statistical* arb — not a mechanical one. Losses are possible if the spread keeps widening.

**Prototype parameters:** β = 0.8 (live-updatable), entry Z = 2.0σ, exit Z = 0.5σ.

---

## Group E — Volatility-Informed Arbitrage

These strategies layer volatility information on top of standard basis arb to make entries smarter.

---

### E1 — VolSkewBasisArb

**The Core Idea**

Standard HSI basis arb (A1) uses a fixed entry threshold (50 BPS). But markets are not always equally calm. During high-volatility periods, basis signals are noisier — many "opportunities" are actually just noise that will reverse quickly. Trading on them leads to losses.

The fix: **raise the entry threshold when the market is scared** (IV > RV).

**The Vol Premium Signal**

```
Vol premium = max(0, IV − RV)
```

- `IV` = implied volatility from HSI options (updated every minute via warm-path bridge)
- `RV` = realised volatility over last 20 days (updated by `SpreadVolEstimator`)

**Adaptive Threshold Formula**

```
adaptiveThreshold = baseThreshold + max(0, IV − RV) / scaleDown

where: baseThreshold = 50 BPS, scaleDown = 10
```

**Concrete Examples**

| Market regime | IV | RV | Adaptive threshold | Meaning |
|---|---|---|---|---|
| Normal | 20% | 15% | 50 + 500/10 = **100 BPS** | Slightly more conservative |
| Earnings week | 30% | 15% | 50 + 1500/10 = **200 BPS** | Much more conservative |
| Post-crisis calm | 15% | 15% | 50 + 0/10 = **50 BPS** | Standard threshold |
| High vol fear | 40% | 20% | 50 + 2000/10 = **250 BPS** | Very selective |

**The Trade**

Same as A1 — but only enter when `basis > adaptiveThreshold`:

```
if annualisedBasisBps > adaptiveThreshold → SELL futures
```

**Why This Is Better Than A1 Alone**

In low-vol markets, both A1 and E1 behave similarly. In high-vol markets, E1 sits out most false signals that A1 would chase — improving the signal-to-noise ratio and reducing drawdowns.

---

## 9. Supporting Analytics

These components feed parameters into the strategies. They run on slower ("warm" or "cold") paths and publish results via lock-free `AtomicLong` bridges that strategies read with zero allocation.

---

### SpreadVolEstimator

**What it does:** Computes a rolling standard deviation (σ) of spread observations.

**Algorithm:** [Welford's online algorithm](https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance#Welford's_online_algorithm) — numerically stable, single-pass, uses a pre-allocated ring buffer of 20 observations. No heap allocations.

**Output:** `spreadSigmaBps100` — the rolling σ of the spread, in BPS×100.

**Used by:** C2 (calendar spread threshold), D1 (pair trading Z-score denominator).

**Update frequency:** Every second (warm path — not the nanosecond hot path).

---

### MonteCarloPositionSizer

**What it does:** Runs 10,000 simulated price paths (Black-Scholes model) to compute the 95% Value-at-Risk (VaR) for a basket position. Translates the VaR into a *maximum lot count* that keeps potential loss within the risk budget.

**In plain English:**
> "Imagine 10,000 possible futures for the HSI over the next 30 days. In the worst 5% of scenarios, how much could we lose? If that's too much, reduce the position size."

**Algorithm:** Black-Scholes Geometric Brownian Motion via [finmath-lib](https://finmath.net/finmath-lib/). 10,000 paths × 50 time steps. Runtime ~500ms per calibration on commodity hardware — this is the *cold path*.

**Output:** `maxLotsHkex` — maximum contracts any HKEX strategy is allowed to trade, published via `setRelease()`.

**Update frequency:** Every 5 minutes, or at market open (never on the nanosecond hot path — it allocates memory for the MC paths).

---

### How Position Sizing Works End-to-End

```
1. [Cold path, every 5 min]
   MonteCarloPositionSizer.calibrate(currentSpot, riskFreeRate, 30/365)
   → computes max lots based on VaR
   → writes to maxLots.setRelease(N)

2. [Warm path, every second]
   SpreadVolEstimator.update(latestSpread)
   → writes to spreadSigmaBps100.setRelease(σ)

3. [Hot path, every tick, ~nanosecond]
   Strategy.onFvUpdate(fv, orders)
   → reads maxLots.getAcquire()      ← LoadLoad fence, no GC
   → reads spreadSigma.getAcquire()  ← LoadLoad fence, no GC
   → sends order via OrderSink       ← pre-allocated SBE buffer, no GC
```

The `setRelease()` / `getAcquire()` pair provides a memory fence equivalent to `volatile` but without the `StoreLoad` fence overhead of `volatile`. This is the standard zero-GC pattern for passing parameters from slow analytics threads to nanosecond trading loops.

---

*This document is updated at the end of each implementation phase.*  
*For technical API details, see the [README](../README.md) and the Java source in `arb-strategy/src/main/java/com/arb/strategy/`.*
