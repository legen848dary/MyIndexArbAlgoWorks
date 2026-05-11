import { useState } from 'react'
import { useTradeStore } from '@/store/useTradeStore'
import type { ArbTrade } from '@/store/useTradeStore'
import { TradeDetailModal } from '@/components/TradeDetailModal'

const STATUS_COLORS: Record<string, string> = {
  OPEN:     'border-blue-500/50 bg-blue-500/10 text-blue-400',
  PARTIAL:  'border-yellow-500/50 bg-yellow-500/10 text-yellow-400',
  COMPLETE: 'border-green-500/50 bg-green-500/10 text-green-400',
  FAILED:   'border-red-500/50 bg-red-500/10 text-red-400',
}

function TradeRow({ trade, onClick }: { trade: ArbTrade; onClick: () => void }) {
  const leg1        = trade.leg1
  const age         = ((Date.now() - trade.timestamp) / 1000).toFixed(0)
  const statusClass = STATUS_COLORS[trade.status] ?? 'border-slate-500/50 bg-slate-500/10 text-slate-400'

  const filledLegs  = (leg1?.status === 'FILLED' ? 1 : 0) + trade.leg2Legs.filter(l => l.status === 'FILLED').length
  const totalLegs   = (leg1 ? 1 : 0) + trade.leg2Legs.length

  return (
    <div
      onClick={onClick}
      className="flex items-center gap-3 rounded-lg border bg-card px-4 py-3 text-sm cursor-pointer hover:bg-muted/30 transition-colors group"
      title="Click for full trade details & decision log"
    >
      {/* Basket ID + Strategy */}
      <div className="flex flex-col min-w-[140px]">
        <span className="font-mono text-xs text-muted-foreground">#{String(trade.basketId).slice(-6)}</span>
        <span className="font-semibold text-sm">{trade.strategy}</span>
      </div>

      {/* Leg 1 direction */}
      <div className="flex-1">
        {leg1 ? (
          <div className="flex items-center gap-1.5">
            <span className={`text-xs font-bold ${leg1.side === 'BUY' ? 'text-green-400' : 'text-red-400'}`}>
              {leg1.side}
            </span>
            <span className="font-mono text-xs">{leg1.symbol}</span>
            <span className="text-xs text-muted-foreground">+{trade.leg2Legs.length} basket</span>
          </div>
        ) : (
          <span className="text-xs text-muted-foreground">—</span>
        )}
      </div>

      {/* Fill progress */}
      <div className="text-center min-w-[60px]">
        <p className="text-xs text-muted-foreground">Filled</p>
        <p className="text-sm font-bold">{filledLegs}/{totalLegs}</p>
      </div>

      {/* P&L */}
      <div className="text-right min-w-[80px]">
        {trade.pnl != null ? (
          <>
            <p className="text-xs text-muted-foreground">P&L</p>
            <p className={`text-sm font-bold ${trade.pnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
              {trade.pnl >= 0 ? '+' : ''}{trade.pnl.toFixed(2)}
            </p>
          </>
        ) : (
          <p className="text-xs text-muted-foreground">{age}s ago</p>
        )}
      </div>

      {/* Status badge */}
      <span className={`inline-flex items-center rounded border px-2 py-0.5 text-xs font-semibold ${statusClass} min-w-[68px] justify-center`}>
        {trade.status}
      </span>

      <span className="text-muted-foreground/40 group-hover:text-muted-foreground transition-colors">›</span>
    </div>
  )
}

export function TradesPage() {
  const { recentTrades }              = useTradeStore()
  const [selectedBasketId, setBasketId]     = useState<number | null>(null)

  if (recentTrades.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <div className="text-4xl mb-4">📊</div>
        <p className="text-lg font-medium text-muted-foreground">No 2-leg trades yet</p>
        <p className="text-sm text-muted-foreground mt-1">
          Start the simulation and enable strategies — arb trades appear every ~30 seconds.
        </p>
      </div>
    )
  }

  const openCount     = recentTrades.filter(t => t.status === 'OPEN' || t.status === 'PARTIAL').length
  const completeCount = recentTrades.filter(t => t.status === 'COMPLETE').length
  const failedCount   = recentTrades.filter(t => t.status === 'FAILED').length
  const totalPnl      = recentTrades.filter(t => t.pnl != null).reduce((s, t) => s + t.pnl!, 0)

  return (
    <>
      <TradeDetailModal basketId={selectedBasketId} onClose={() => setBasketId(null)} />

      <div className="space-y-4">
        {/* Summary bar */}
        <div className="flex gap-4 rounded-lg border bg-card p-3">
          <div className="text-center px-3">
            <p className="text-xs text-muted-foreground">Open / Partial</p>
            <p className="text-xl font-bold text-blue-400">{openCount}</p>
          </div>
          <div className="text-center px-3">
            <p className="text-xs text-muted-foreground">Completed</p>
            <p className="text-xl font-bold text-green-400">{completeCount}</p>
          </div>
          <div className="text-center px-3">
            <p className="text-xs text-muted-foreground">Failed</p>
            <p className="text-xl font-bold text-red-400">{failedCount}</p>
          </div>
          <div className="flex-1 text-center px-3">
            <p className="text-xs text-muted-foreground">Total P&L</p>
            <p className={`text-xl font-bold ${totalPnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
              {totalPnl >= 0 ? '+' : ''}{totalPnl.toFixed(2)}
            </p>
          </div>
          <div className="text-center px-3">
            <p className="text-xs text-muted-foreground">Total Trades</p>
            <p className="text-xl font-bold">{recentTrades.length}</p>
          </div>
        </div>

        {/* Trade rows — click any to open detail modal */}
        <div className="space-y-2">
          <p className="text-xs text-muted-foreground px-1">
            Click any trade to view full details and algo decision log →
          </p>
          {recentTrades.map((trade) => (
            <TradeRow
              key={trade.basketId}
              trade={trade}
              onClick={() => setBasketId(trade.basketId)}
            />
          ))}
        </div>
      </div>
    </>
  )
}
