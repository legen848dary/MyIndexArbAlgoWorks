import { useTradeStore, type ArbTrade, type LegInfo } from '@/store/useTradeStore'
import { Card, CardContent } from '@/components/ui/card'

const STATUS_COLORS: Record<string, string> = {
  OPEN:     'bg-blue-500/20 text-blue-400 border-blue-500/30',
  PARTIAL:  'bg-yellow-500/20 text-yellow-400 border-yellow-500/30',
  COMPLETE: 'bg-green-500/20 text-green-400 border-green-500/30',
  FAILED:   'bg-red-500/20 text-red-400 border-red-500/30',
}

const LEG_STATUS_DOT: Record<string, string> = {
  PENDING:      'bg-slate-400',
  FILLED:       'bg-green-500',
  PARTIAL_FILL: 'bg-yellow-500',
  REJECTED:     'bg-red-500',
  CANCELLED:    'bg-slate-600',
}

function LegRow({ leg, legLabel }: { leg: LegInfo; legLabel: string }) {
  const price = leg.price / 10_000
  const fillPrice = leg.fillPrice != null ? leg.fillPrice / 10_000 : null

  return (
    <div className="flex items-center gap-2 rounded-md bg-muted/20 px-3 py-1.5 text-xs">
      <span className={`h-2 w-2 rounded-full flex-shrink-0 ${LEG_STATUS_DOT[leg.status] ?? 'bg-slate-400'}`} />
      <span className="font-medium text-muted-foreground w-10">{legLabel}</span>
      <span className={`font-bold w-8 ${leg.side === 'BUY' ? 'text-green-400' : 'text-red-400'}`}>
        {leg.side}
      </span>
      <span className="font-mono flex-1">{leg.symbol}</span>
      <span className="text-muted-foreground">qty={leg.qty}</span>
      <span className="font-mono text-xs">
        @{fillPrice != null ? fillPrice.toFixed(2) : price.toFixed(2)}
        {fillPrice != null && <span className="text-green-400 ml-1">(filled)</span>}
      </span>
    </div>
  )
}

function TradeCard({ trade }: { trade: ArbTrade }) {
  const age = ((Date.now() - trade.timestamp / 1_000_000) / 1000).toFixed(1)
  const statusClass = STATUS_COLORS[trade.status] ?? 'bg-slate-500/20 text-slate-400'

  return (
    <Card className="overflow-hidden">
      <CardContent className="p-3 space-y-2">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-xs font-mono text-muted-foreground">#{trade.basketId}</span>
            <span className="text-sm font-semibold">{trade.strategy}</span>
          </div>
          <div className="flex items-center gap-2">
            <span className={`inline-flex items-center rounded-md border px-1.5 py-0.5 text-xs font-medium ${statusClass}`}>
              {trade.status}
            </span>
            <span className="text-xs text-muted-foreground">{age}s ago</span>
          </div>
        </div>

        {/* Leg 1 */}
        {trade.leg1 && (
          <div>
            <p className="text-xs text-muted-foreground mb-1 font-medium">Leg 1 — Futures / ETF</p>
            <LegRow leg={trade.leg1} legLabel="L1" />
          </div>
        )}

        {/* Leg 2 */}
        {trade.leg2Legs.length > 0 && (
          <div>
            <p className="text-xs text-muted-foreground mb-1 font-medium">
              Leg 2 — Constituent Basket ({trade.leg2Legs.length} stocks)
            </p>
            <div className="space-y-1 max-h-32 overflow-y-auto">
              {trade.leg2Legs.map((leg, i) => (
                <LegRow key={leg.orderId} leg={leg} legLabel={`L2.${i + 1}`} />
              ))}
            </div>
          </div>
        )}

        {/* P&L */}
        {trade.pnl != null && (
          <div className={`flex items-center justify-between rounded-md px-3 py-1.5 text-sm font-bold ${
            trade.pnl >= 0 ? 'bg-green-500/10 text-green-400' : 'bg-red-500/10 text-red-400'
          }`}>
            <span>Arb P&L</span>
            <span>{trade.pnl >= 0 ? '+' : ''}{trade.pnl.toFixed(2)} HKD/TWD</span>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

export function TradesPage() {
  const { recentTrades } = useTradeStore()

  if (recentTrades.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <div className="text-4xl mb-4">📊</div>
        <p className="text-lg font-medium text-muted-foreground">No 2-leg trades yet</p>
        <p className="text-sm text-muted-foreground mt-1">
          Start the simulation and enable strategies — arb trades appear here every ~13 seconds.
        </p>
      </div>
    )
  }

  const openCount     = recentTrades.filter(t => t.status === 'OPEN' || t.status === 'PARTIAL').length
  const completeCount = recentTrades.filter(t => t.status === 'COMPLETE').length
  const totalPnl      = recentTrades.filter(t => t.pnl != null).reduce((s, t) => s + t.pnl!, 0)

  return (
    <div className="space-y-4">
      {/* Summary bar */}
      <div className="flex gap-4 rounded-lg border bg-card p-3">
        <div className="text-center">
          <p className="text-xs text-muted-foreground">Open Trades</p>
          <p className="text-xl font-bold text-blue-400">{openCount}</p>
        </div>
        <div className="text-center">
          <p className="text-xs text-muted-foreground">Completed</p>
          <p className="text-xl font-bold text-green-400">{completeCount}</p>
        </div>
        <div className="text-center flex-1">
          <p className="text-xs text-muted-foreground">Total P&L</p>
          <p className={`text-xl font-bold ${totalPnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
            {totalPnl >= 0 ? '+' : ''}{totalPnl.toFixed(2)}
          </p>
        </div>
      </div>

      {/* Trade cards */}
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        {recentTrades.map((trade) => (
          <TradeCard key={trade.basketId} trade={trade} />
        ))}
      </div>
    </div>
  )
}
