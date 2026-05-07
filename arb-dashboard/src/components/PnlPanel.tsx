import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine } from 'recharts'
import { useStore } from '@/store/useStore'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { formatTimestamp } from '@/lib/utils'

export function PnlPanel() {
  const pnlHistory = useStore((s) => s.pnlHistory)
  const totalPnl   = useStore((s) => s.cumulativePnl)

  const chartData = pnlHistory.map((p) => ({
    time: formatTimestamp(p.ts),
    PnL:  Math.round(p.pnl * 100) / 100,
  }))

  const isPositive = totalPnl >= 0

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle>💰 Realised P&amp;L</CardTitle>
          <span className={`text-lg font-bold font-mono ${isPositive ? 'text-green-400' : 'text-red-400'}`}>
            {isPositive ? '+' : ''}{totalPnl.toLocaleString('en-HK', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </span>
        </div>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={180}>
          <AreaChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
            <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
            <XAxis dataKey="time" tick={{ fontSize: 10 }} />
            <YAxis tick={{ fontSize: 10 }} />
            <Tooltip
              contentStyle={{ backgroundColor: 'hsl(var(--card))', border: '1px solid hsl(var(--border))' }}
              formatter={(val: number) => [val.toFixed(2), 'P&L']}
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
        <p className="text-xs text-muted-foreground mt-2">
          Based on fill prices — SELL proceeds minus BUY costs (price × qty ÷ 10⁸ HKD)
        </p>
      </CardContent>
    </Card>
  )
}
