import { useTradeStore } from '@/store/useTradeStore'
import type { ArbTrade, LegInfo } from '@/store/useTradeStore'

// ── helpers ───────────────────────────────────────────────────────────────────

function fmtPrice(raw: number): string {
  return (raw / 10_000).toFixed(2)
}

function fmtAge(ts: number): string {
  const sec = Math.floor((Date.now() - ts) / 1000)
  if (sec < 60) return `${sec}s ago`
  return `${Math.floor(sec / 60)}m ${sec % 60}s ago`
}

const STATUS_BADGE: Record<string, string> = {
  OPEN:     'bg-blue-500/20 text-blue-300 border-blue-500/40',
  PARTIAL:  'bg-yellow-500/20 text-yellow-300 border-yellow-500/40',
  COMPLETE: 'bg-green-500/20 text-green-300 border-green-500/40',
  FAILED:   'bg-red-500/20 text-red-300 border-red-500/40',
}

const LEG_STATUS_DOT: Record<string, string> = {
  PENDING:      'bg-slate-400',
  FILLED:       'bg-green-500',
  PARTIAL_FILL: 'bg-yellow-400',
  REJECTED:     'bg-red-500',
  CANCELLED:    'bg-slate-600',
}

// ── Decision Log generator ────────────────────────────────────────────────────

interface DecisionSection {
  icon: string
  title: string
  lines: string[]
}

function buildDecisionLog(trade: ArbTrade): DecisionSection[] {
  const leg1 = trade.leg1
  const isShort = leg1?.side === 'SELL'
  const futuresSide = isShort ? 'SELL' : 'BUY'
  const spotSide    = isShort ? 'BUY'  : 'SELL'

  if (trade.strategy === 'HkexBasisArb' || trade.strategy === 'MhiHsiBasisArb') {
    const indexName = trade.strategy === 'HkexBasisArb' ? 'HSI (Hang Seng Index)' : 'Mini-HSI'
    const priceDesc = isShort
      ? 'Futures were trading at a PREMIUM to Fair Value. The market price exceeded the computed FV by more than the 10 BPS entry threshold.'
      : 'Futures were trading at a DISCOUNT to Fair Value. The market price was below the computed FV by more than the 10 BPS entry threshold.'

    return [
      {
        icon: '📡',
        title: 'Signal Detection',
        lines: [
          `Strategy: ${trade.strategy} — ${indexName} Basis Arbitrage`,
          `Trigger: Annualised basis exceeded ±10 BPS entry threshold`,
          priceDesc,
          `Direction: ${isShort ? 'Positive basis (futures RICH) → short futures, long basket' : 'Negative basis (futures CHEAP) → long futures, short basket'}`,
        ],
      },
      {
        icon: '🎯',
        title: `Leg 1 — Signal Leg (${leg1?.symbol ?? 'HSI Futures'})`,
        lines: [
          `Action: ${futuresSide} ${leg1?.qty ?? '–'} futures lot(s) at FV price`,
          `Entry price: HKD ${fmtPrice(leg1?.price ?? 0)} per contract`,
          `Rationale: ${isShort
            ? 'Sell the overpriced futures. Lock in the basis premium at entry. Profit realised when futures converge down to FV.'
            : 'Buy the underpriced futures. Lock in the basis discount at entry. Profit realised when futures converge up to FV.'}`,
          `Order type: LIMIT — fills at or better than FV price`,
        ],
      },
      {
        icon: '🛡️',
        title: `Leg 2 — Delta-1 Hedge Leg (${trade.leg2Legs.length} constituent stocks)`,
        lines: [
          `Action: ${spotSide} ${trade.leg2Legs.length} HSI constituent stocks`,
          `Lot sizes: proportional to each stock's index weight × futures notional`,
          `Rationale: The constituent basket replicates the index exposure. ${spotSide === 'BUY' ? 'Going long spot' : 'Going short spot'} creates a delta-neutral position — the basket's price movement offsets the futures position, leaving only the basis spread as net exposure.`,
          `Result: Delta-neutral (Δ ≈ 0). The position is NOT a directional bet on the index level.`,
        ],
      },
      {
        icon: '📈',
        title: 'P&L Mechanics & Exit',
        lines: [
          `Entry basis locked: ~60 BPS annualised`,
          `Exit: When the basis converges back to ~0, close both legs. The spread collapse delivers the premium as realised P&L.`,
          `Risk: Execution risk (slippage on 11 legs). Basis may widen further before convergence ("basis risk").`,
          `Hedge ratio: 1:1 index-equivalent notional between futures and basket.`,
        ],
      },
    ]
  }

  if (trade.strategy === 'TwseEtfArb') {
    return [
      {
        icon: '📡',
        title: 'Signal Detection',
        lines: [
          'Strategy: TwseEtfArb — Taiwan 50 ETF Arbitrage',
          'Trigger: 0050.TW ETF NAV deviated from fair value by > 20 BPS',
          isShort
            ? 'ETF was trading at a PREMIUM to its basket NAV → create and sell ETF'
            : 'ETF was trading at a DISCOUNT to its basket NAV → buy ETF and redeem basket',
        ],
      },
      {
        icon: '🎯',
        title: `Leg 1 — ETF Leg (${leg1?.symbol ?? '0050.TW'})`,
        lines: [
          `Action: ${futuresSide} 0050.TW ETF at current market price`,
          `Entry price: TWD ${fmtPrice(leg1?.price ?? 0)} per unit`,
          `Rationale: ${isShort ? 'Sell the overpriced ETF. The ETF premium to NAV will compress.' : 'Buy the discounted ETF. The ETF discount to NAV will compress.'}`,
        ],
      },
      {
        icon: '🛡️',
        title: `Leg 2 — Constituent Basket (${trade.leg2Legs.length} stocks)`,
        lines: [
          `Action: ${spotSide} ${trade.leg2Legs.length} TWSE 50 constituent stocks`,
          `Rationale: The basket mirrors the ETF holdings. This is the classic ETF creation/redemption arb — lock in the NAV spread.`,
          `Delta neutral: long ETF + short basket (or vice versa) = only NAV-spread exposure.`,
        ],
      },
    ]
  }

  // Generic fallback for other strategies
  return [
    {
      icon: '📡',
      title: 'Signal Detection',
      lines: [
        `Strategy: ${trade.strategy}`,
        `Trigger: Spread exceeded entry threshold`,
        `Direction: ${futuresSide} Leg 1 / ${spotSide} Leg 2 basket`,
      ],
    },
    {
      icon: '🛡️',
      title: 'Hedge Structure',
      lines: [
        `Leg 1 (signal): ${futuresSide} ${leg1?.symbol ?? '–'} to capture spread`,
        `Leg 2 (hedge): ${spotSide} ${trade.leg2Legs.length} instruments to replicate opposing index exposure`,
        `Delta target: Δ ≈ 0 — basis-only exposure after both legs filled`,
      ],
    },
  ]
}

// ── Sub-components ────────────────────────────────────────────────────────────

function LegDetailRow({ leg, label }: { leg: LegInfo; label: string }) {
  const entryPrice = fmtPrice(leg.price)
  const fillPrice  = leg.fillPrice != null ? fmtPrice(leg.fillPrice) : null

  return (
    <div className="flex items-start gap-3 rounded-md bg-muted/20 px-3 py-2 text-xs">
      <span className={`mt-0.5 h-2 w-2 flex-shrink-0 rounded-full ${LEG_STATUS_DOT[leg.status] ?? 'bg-slate-400'}`} />
      <span className="w-10 font-medium text-muted-foreground flex-shrink-0">{label}</span>
      <span className={`w-8 font-bold flex-shrink-0 ${leg.side === 'BUY' ? 'text-green-400' : 'text-red-400'}`}>
        {leg.side}
      </span>
      <span className="flex-1 font-mono">{leg.symbol}</span>
      <span className="text-muted-foreground">qty {leg.qty}</span>
      <div className="text-right font-mono min-w-[100px]">
        <div>order @ {entryPrice}</div>
        {fillPrice != null && <div className="text-green-400">filled @ {fillPrice}</div>}
      </div>
      <span className={`inline-flex items-center rounded border px-1.5 py-0.5 font-medium ${
        leg.status === 'FILLED' ? 'border-green-500/40 bg-green-500/20 text-green-300' :
        leg.status === 'REJECTED' ? 'border-red-500/40 bg-red-500/20 text-red-300' :
        leg.status === 'PENDING' ? 'border-slate-500/40 bg-slate-500/20 text-slate-300' :
        'border-yellow-500/40 bg-yellow-500/20 text-yellow-300'
      }`}>
        {leg.status}
      </span>
    </div>
  )
}

// ── Main modal ────────────────────────────────────────────────────────────────

interface Props {
  basketId: number | null
  onClose: () => void
}

export function TradeDetailModal({ basketId, onClose }: Props) {
  // Live lookup — re-renders automatically as fills arrive
  const trade = useTradeStore((s) => basketId != null ? (s.trades.get(basketId) ?? null) : null)
  if (!trade) return null

  const decisionLog = buildDecisionLog(trade)
  const statusClass = STATUS_BADGE[trade.status] ?? 'bg-slate-500/20 text-slate-300 border-slate-500/40'
  const allLegs: { leg: LegInfo; label: string }[] = []
  if (trade.leg1) allLegs.push({ leg: trade.leg1, label: 'L1' })
  trade.leg2Legs.forEach((l, i) => allLegs.push({ leg: l, label: `L2.${i + 1}` }))

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4"
      onClick={onClose}
    >
      <div
        className="relative w-full max-w-3xl max-h-[90vh] overflow-y-auto rounded-xl border bg-card shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="sticky top-0 z-10 flex items-center justify-between border-b bg-card px-6 py-4">
          <div className="flex items-center gap-3">
            <span className="font-mono text-sm text-muted-foreground">
              Basket #{String(trade.basketId).slice(-6)}
            </span>
            <span className="text-lg font-bold">{trade.strategy}</span>
            <span className={`inline-flex items-center rounded-md border px-2 py-0.5 text-xs font-semibold ${statusClass}`}>
              {trade.status}
            </span>
          </div>
          <div className="flex items-center gap-4">
            <span className="text-sm text-muted-foreground">{fmtAge(trade.timestamp)}</span>
            <button
              onClick={onClose}
              className="rounded-md p-1 text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
              aria-label="Close"
            >
              ✕
            </button>
          </div>
        </div>

        <div className="space-y-5 px-6 py-5">
          {/* P&L banner */}
          {trade.pnl != null && (
            <div className={`rounded-lg px-4 py-3 text-center ${
              trade.pnl >= 0 ? 'bg-green-500/10 border border-green-500/30' : 'bg-red-500/10 border border-red-500/30'
            }`}>
              <p className="text-xs text-muted-foreground mb-0.5">Realised Arb P&L</p>
              <p className={`text-2xl font-bold ${trade.pnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                {trade.pnl >= 0 ? '+' : ''}{trade.pnl.toFixed(2)} HKD/TWD
              </p>
            </div>
          )}

          {/* Legs */}
          <div>
            <h3 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider mb-2">
              Order Legs ({allLegs.length} total)
            </h3>
            <div className="space-y-1">
              {allLegs.map(({ leg, label }) => (
                <LegDetailRow key={leg.orderId} leg={leg} label={label} />
              ))}
            </div>
          </div>

          {/* Progress summary */}
          {allLegs.length > 0 && (
            <div className="grid grid-cols-3 gap-3 text-center">
              {(['PENDING', 'FILLED', 'REJECTED'] as const).map((s) => {
                const count = allLegs.filter((l) => l.leg.status === s).length
                return (
                  <div key={s} className="rounded-lg border bg-muted/10 px-3 py-2">
                    <p className="text-xs text-muted-foreground">{s}</p>
                    <p className={`text-xl font-bold ${
                      s === 'FILLED' ? 'text-green-400' :
                      s === 'REJECTED' ? 'text-red-400' : 'text-slate-400'
                    }`}>{count}</p>
                  </div>
                )
              })}
            </div>
          )}

          {/* Decision Log */}
          <div>
            <h3 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider mb-3">
              🧠 Algo Decision Log
            </h3>
            <div className="space-y-4">
              {decisionLog.map((section, idx) => (
                <div key={idx} className="rounded-lg border bg-muted/10 p-4">
                  <p className="text-sm font-semibold mb-2">
                    {section.icon} {section.title}
                  </p>
                  <ul className="space-y-1">
                    {section.lines.map((line, i) => (
                      <li key={i} className="text-xs text-muted-foreground leading-relaxed pl-2 border-l-2 border-muted">
                        {line}
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
