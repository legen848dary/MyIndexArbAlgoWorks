import { useState, useEffect } from 'react'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer, ReferenceLine } from 'recharts'
import { useStore } from '@/store/useStore'
import { useTradeStore } from '@/store/useTradeStore'
import type { ArbTrade } from '@/store/useTradeStore'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { TradeDetailModal } from '@/components/TradeDetailModal'
import { formatTimestamp } from '@/lib/utils'

const STATUS_COLORS: Record<string, string> = {
  OPEN:     'border-blue-500/50 bg-blue-500/10 text-blue-400',
  PARTIAL:  'border-yellow-500/50 bg-yellow-500/10 text-yellow-400',
  COMPLETE: 'border-green-500/50 bg-green-500/10 text-green-400',
  FAILED:   'border-red-500/50 bg-red-500/10 text-red-400',
}

function RecentTradeRow({ trade, onClick }: { trade: ArbTrade; onClick: () => void }) {
  const leg1 = trade.leg1
  const age  = ((Date.now() - trade.timestamp) / 1000).toFixed(0)
  const statusClass = STATUS_COLORS[trade.status] ?? 'border-slate-500/50 bg-slate-500/10 text-slate-400'

  return (
    <div
      onClick={onClick}
      className="flex items-center gap-2 rounded-md border px-3 py-2 text-xs cursor-pointer hover:bg-muted/30 transition-colors"
      title="Click for full trade details & decision log"
    >
      <span className="font-mono text-muted-foreground w-14 flex-shrink-0">
        #{String(trade.basketId).slice(-5)}
      </span>
      <span className="font-semibold flex-1 truncate">{trade.strategy}</span>
      {leg1 && (
        <span className={`font-bold ${leg1.side === 'BUY' ? 'text-green-400' : 'text-red-400'}`}>
          {leg1.side} {leg1.symbol}
        </span>
      )}
      {trade.leg2Legs.length > 0 && (
        <span className="text-muted-foreground">+{trade.leg2Legs.length} basket</span>
      )}
      <span className={`inline-flex items-center rounded border px-1.5 py-0.5 font-medium text-xs ${statusClass}`}>
        {trade.status}
      </span>
      {trade.pnl != null && (
        <span className={`font-bold w-16 text-right ${trade.pnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
          {trade.pnl >= 0 ? '+' : ''}{trade.pnl.toFixed(0)}
        </span>
      )}
      <span className="text-muted-foreground w-8 text-right flex-shrink-0">{age}s</span>
      <span className="text-muted-foreground/50 flex-shrink-0">›</span>
    </div>
  )
}

interface LiveMonitorProps {
  onViewAllTrades?: () => void
}

/** Custom SVG label rendered at the top of each ReferenceLine signal marker. */
function SignalLabel({ viewBox, side }: { viewBox?: { x: number; y: number }; side: string }) {
  if (!viewBox) return null
  const { x, y } = viewBox
  const isBuy  = side === 'BUY'
  const color  = isBuy ? '#10b981' : '#f59e0b'  // green for long, amber for short
  const arrow  = isBuy ? '▲' : '▼'
  const label  = isBuy ? 'LONG' : 'SHORT'
  return (
    <g>
      <rect x={x - 1} y={y + 4} width={46} height={15} rx={2} fill={color} fillOpacity={0.92} />
      <text x={x + 2} y={y + 15} fill="white" fontSize={9} fontWeight="bold" fontFamily="monospace">
        {arrow} {label}
      </text>
    </g>
  )
}

export function LiveMonitor({ onViewAllTrades }: LiveMonitorProps) {
  const [symbol, setSymbol]         = useState('HSI.HK')
  const [selectedBasketId, setBasketId] = useState<number | null>(null)

  // Only show symbols that have received data — derived from live priceHistory keys
  const availableSymbols = useStore((s) => Object.keys(s.priceHistory))
  const priceHistory     = useStore((s) => s.priceHistory[symbol] ?? [])
  const recentTrades     = useTradeStore((s) => s.recentTrades.slice(0, 4))

  // Auto-switch to the first available symbol when the selected one has no data
  useEffect(() => {
    if (availableSymbols.length > 0 && !availableSymbols.includes(symbol)) {
      setSymbol(availableSymbols[0])
    }
  }, [availableSymbols, symbol])

  const chartData = priceHistory.map((p) => ({
    time:   formatTimestamp(p.ts),
    Market: p.market,
    FV:     p.fv,
  }))

  // Signal markers are already tagged onto priceHistory by useStore.handleOrderRequest
  // when the leg-1 ORDER_REQUEST arrives — no timestamp matching needed.
  const signalMarkers = priceHistory
    .map((p, i) => p.signal ? { x: chartData[i].time, side: p.signal } : null)
    .filter(Boolean) as Array<{ x: string; side: 'BUY' | 'SELL' }>

  return (
    <>
      <TradeDetailModal basketId={selectedBasketId} onClose={() => setBasketId(null)} />

      <div className="space-y-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle>📈 Live Monitor — FV vs Market Price</CardTitle>
            <select
              value={symbol}
              onChange={(e) => setSymbol(e.target.value)}
              className="rounded-md border bg-background px-3 py-1 text-sm"
            >
              {availableSymbols.length === 0
                ? <option value="">No data yet</option>
                : availableSymbols.map((s) => <option key={s} value={s}>{s}</option>)
              }
            </select>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={280}>
              <LineChart data={chartData} margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
                <XAxis dataKey="time" tick={{ fontSize: 11 }} />
                <YAxis domain={['auto', 'auto']} tick={{ fontSize: 11 }} />
                <Tooltip
                  contentStyle={{ backgroundColor: 'hsl(var(--card))', border: '1px solid hsl(var(--border))' }}
                />
                <Legend />
                <Line type="monotone" dataKey="Market" stroke="#3b82f6" dot={false} strokeWidth={2} />
                <Line type="monotone" dataKey="FV"     stroke="#10b981" dot={false} strokeWidth={2} strokeDasharray="4 2" />
                {signalMarkers.map((m, i) => (
                  <ReferenceLine
                    key={`sig-${i}-${m.x}`}
                    x={m.x}
                    stroke={m.side === 'BUY' ? '#10b981' : '#f59e0b'}
                    strokeWidth={1.5}
                    strokeDasharray="3 3"
                    label={<SignalLabel side={m.side} />}
                  />
                ))}
              </LineChart>
            </ResponsiveContainer>
            {signalMarkers.length > 0 && (
              <p className="mt-1 text-right text-[10px] text-muted-foreground">
                <span className="inline-block w-2.5 h-2.5 rounded-sm bg-emerald-500 mr-1 align-middle" />▲ LONG signal&nbsp;&nbsp;
                <span className="inline-block w-2.5 h-2.5 rounded-sm bg-amber-500 mr-1 align-middle" />▼ SHORT signal
              </p>
            )}
          </CardContent>
        </Card>

        {/* Recent trades — last 4, clickable, link to full Trades page */}
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle>🔄 Recent Arb Trades</CardTitle>
            {onViewAllTrades && (
              <button
                onClick={onViewAllTrades}
                className="text-xs text-primary hover:underline focus:outline-none"
              >
                View all →
              </button>
            )}
          </CardHeader>
          <CardContent>
            {recentTrades.length === 0 ? (
              <p className="py-6 text-center text-sm text-muted-foreground">
                No trades yet — start simulation &amp; enable strategies to see 2-leg arb trades here
              </p>
            ) : (
              <div className="space-y-1.5">
                {recentTrades.map((trade) => (
                  <RecentTradeRow
                    key={trade.basketId}
                    trade={trade}
                    onClick={() => setBasketId(trade.basketId)}
                  />
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </>
  )
}
