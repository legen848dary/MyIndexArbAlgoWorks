import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from 'recharts'
import { useTradeStore } from '@/store/useTradeStore'
import { useStore } from '@/store/useStore'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { formatTimestamp } from '@/lib/utils'

function profileCurrency(profile: string): string {
  if (profile.includes('TWSE') || profile.includes('TAIWAN')) return 'TWD'
  if (profile.includes('CN') || profile.includes('CSI'))       return 'CNY'
  return 'HKD'
}

export function PnlPanel() {
  // Accurate arb spread P&L: sum of completed-basket P&Ls (leg1 proceeds − leg2 cost).
  // Old formula used raw per-fill notional which went "sky high" incorrectly.
  const completedTrades = useTradeStore((s) =>
    [...s.trades.values()]
      .filter((t) => t.status === 'COMPLETE' && t.pnl != null)
      .sort((a, b) => a.timestamp - b.timestamp)
  )
  const simProfile = useStore((s) => s.simProfile)
  const currency   = profileCurrency(simProfile)

  let cumulative = 0
  const chartData = completedTrades.map((t) => {
    cumulative += t.pnl!
    return { time: formatTimestamp(t.timestamp), PnL: Math.round(cumulative * 100) / 100 }
  })
  const totalPnl   = cumulative
  const tradeCount = completedTrades.length

  const isPositive = totalPnl >= 0

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle>💰 Realised Arb P&amp;L</CardTitle>
          <div className="text-right">
            <p className={`text-lg font-bold font-mono ${isPositive ? 'text-green-400' : 'text-red-400'}`}>
              {isPositive ? '+' : ''}{totalPnl.toLocaleString('en-HK', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
            </p>
            <p className="text-xs text-muted-foreground">{tradeCount} completed trade{tradeCount !== 1 ? 's' : ''}</p>
          </div>
        </div>
      </CardHeader>
      <CardContent>
        {chartData.length === 0 ? (
          <div className="flex items-center justify-center h-[180px] text-muted-foreground text-sm">
            Waiting for first completed arb trade…
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={180}>
            <AreaChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
              <XAxis dataKey="time" tick={{ fontSize: 10 }} />
              <YAxis tick={{ fontSize: 10 }} />
              <Tooltip
                contentStyle={{ backgroundColor: 'hsl(var(--card))', border: '1px solid hsl(var(--border))' }}
                formatter={(val: number) => [val.toFixed(2), 'Cumulative P&L']}
              />
              <ReferenceLine y={0} stroke="hsl(var(--muted-foreground))" strokeDasharray="3 3" />
              <Area
                type="monotone"
                dataKey="PnL"
                stroke={isPositive ? '#10b981' : '#ef4444'}
                fill={isPositive ? 'rgba(16,185,129,0.2)' : 'rgba(239,68,68,0.2)'}
                strokeWidth={2}
                dot={false}
              />
            </AreaChart>
          </ResponsiveContainer>
        )}
        <p className="text-xs text-muted-foreground mt-2">
          Arb spread P&L per completed basket: Leg 1 fill proceeds − Leg 2 basket cost ({currency})
        </p>
      </CardContent>
    </Card>
  )
}
