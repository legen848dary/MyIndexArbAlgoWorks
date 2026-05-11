import { useState } from 'react'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts'
import { useStore } from '@/store/useStore'
import { useTradeStore, type ArbTrade } from '@/store/useTradeStore'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { formatTimestamp } from '@/lib/utils'

const SYMBOLS = ['HSI.HK', 'MHI.HK', '0050.TW', '2330.TW', 'CSI300.CN']

const STATUS_COLORS: Record<string, string> = {
  OPEN:     'border-blue-500/50 bg-blue-500/10 text-blue-400',
  PARTIAL:  'border-yellow-500/50 bg-yellow-500/10 text-yellow-400',
  COMPLETE: 'border-green-500/50 bg-green-500/10 text-green-400',
  FAILED:   'border-red-500/50 bg-red-500/10 text-red-400',
}

function RecentTradeRow({ trade }: { trade: ArbTrade }) {
  const leg1 = trade.leg1
  const age  = ((Date.now() - trade.timestamp) / 1000).toFixed(0)
  const statusClass = STATUS_COLORS[trade.status] ?? 'border-slate-500/50 bg-slate-500/10 text-slate-400'

  return (
    <div className="flex items-center gap-2 rounded-md border px-3 py-2 text-xs">
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
    </div>
  )
}

interface LiveMonitorProps {
  onViewAllTrades?: () => void
}

export function LiveMonitor({ onViewAllTrades }: LiveMonitorProps) {
  const [symbol, setSymbol] = useState('HSI.HK')
  const priceHistory  = useStore((s) => s.priceHistory[symbol] ?? [])
  const recentTrades  = useTradeStore((s) => s.recentTrades.slice(0, 4))

  const chartData = priceHistory.map((p) => ({
    time:   formatTimestamp(p.ts),
    Market: p.market,
    FV:     p.fv,
  }))

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
          <CardTitle>📈 Live Monitor — FV vs Market Price</CardTitle>
          <select
            value={symbol}
            onChange={(e) => setSymbol(e.target.value)}
            className="rounded-md border bg-background px-3 py-1 text-sm"
          >
            {SYMBOLS.map((s) => <option key={s} value={s}>{s}</option>)}
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
            </LineChart>
          </ResponsiveContainer>
        </CardContent>
      </Card>

      {/* Recent trades — last 4, link to full Trades page */}
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
                <RecentTradeRow key={trade.basketId} trade={trade} />
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
