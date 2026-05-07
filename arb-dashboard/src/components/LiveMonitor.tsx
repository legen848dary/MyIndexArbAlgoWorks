import { useState } from 'react'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts'
import { useStore } from '@/store/useStore'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { formatTimestamp } from '@/lib/utils'

const SYMBOLS = ['HSI.HK', 'MHI.HK', '0050.TW', '2330.TW', 'CSI300.CN']

export function LiveMonitor() {
  const [symbol, setSymbol]   = useState('HSI.HK')
  const priceHistory          = useStore((s) => s.priceHistory[symbol] ?? [])

  const chartData = priceHistory.map((p) => ({
    time:   formatTimestamp(p.ts),
    Market: p.market,
    FV:     p.fv,
  }))

  return (
    <Card className="col-span-2">
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
  )
}
