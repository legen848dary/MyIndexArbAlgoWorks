import { useTradeStore } from '@/store/useTradeStore'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { formatPrice } from '@/lib/utils'

const MAX_DISPLAY = 20

const STATUS_VARIANT: Record<string, 'success' | 'destructive' | 'warning' | 'secondary'> = {
  FILLED:       'success',
  REJECTED:     'destructive',
  PARTIAL_FILL: 'warning',
  PENDING:      'secondary',
  CANCELLED:    'secondary',
}

export function OrderBook() {
  // Flatten all legs from all recent trades so we see PENDING orders immediately
  // (not just fills which arrive 5-10s later).
  const recentTrades = useTradeStore((s) => s.recentTrades)

  type OrderRow = {
    orderId: number
    basketId: number
    legLabel: string
    symbol: string
    side: 'BUY' | 'SELL'
    orderPrice: number
    fillPrice?: number
    qty: number
    status: string
    tradeTimestamp: number
  }

  const rows: OrderRow[] = recentTrades.flatMap((t) => {
    const leg1Row = t.leg1
      ? [{
          orderId: t.leg1.orderId,
          basketId: t.basketId,
          legLabel: 'L1',
          symbol: t.leg1.symbol,
          side: t.leg1.side,
          orderPrice: t.leg1.price,
          fillPrice: t.leg1.fillPrice,
          qty: t.leg1.qty,
          status: t.leg1.status,
          tradeTimestamp: t.timestamp,
        }]
      : []
    const leg2Rows = t.leg2Legs.map((l, i) => ({
      orderId: l.orderId,
      basketId: t.basketId,
      legLabel: `L2.${i + 1}`,
      symbol: l.symbol,
      side: l.side,
      orderPrice: l.price,
      fillPrice: l.fillPrice,
      qty: l.qty,
      status: l.status,
      tradeTimestamp: t.timestamp,
    }))
    return [...leg1Row, ...leg2Rows]
  }).slice(0, MAX_DISPLAY)

  const totalLegs = recentTrades.reduce(
    (n, t) => n + (t.leg1 ? 1 : 0) + t.leg2Legs.length, 0
  )

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle>📋 Order Book</CardTitle>
          <span className="text-xs text-muted-foreground">
            Showing {rows.length} of {totalLegs} legs (latest {MAX_DISPLAY})
          </span>
        </div>
      </CardHeader>
      <CardContent>
        <div className="overflow-auto max-h-[600px]">
          <table className="w-full text-xs">
            <thead className="sticky top-0 bg-card">
              <tr className="border-b text-muted-foreground">
                <th className="text-left py-1 pr-2">Basket</th>
                <th className="text-left py-1 pr-2">Leg</th>
                <th className="text-left py-1 pr-2">Symbol</th>
                <th className="text-left py-1 pr-2">Side</th>
                <th className="text-right py-1 pr-2">Order Px</th>
                <th className="text-right py-1 pr-2">Fill Px</th>
                <th className="text-right py-1 pr-2">Qty</th>
                <th className="text-left py-1">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 && (
                <tr>
                  <td colSpan={8} className="py-6 text-center text-muted-foreground">
                    No orders yet — start the simulation and enable strategies
                  </td>
                </tr>
              )}
              {rows.map((o) => (
                <tr key={o.orderId} className="border-b border-border/30 hover:bg-muted/20">
                  <td className="py-1 pr-2 font-mono text-muted-foreground">#{String(o.basketId).slice(-5)}</td>
                  <td className="py-1 pr-2 text-muted-foreground">{o.legLabel}</td>
                  <td className="py-1 pr-2 font-mono">{o.symbol}</td>
                  <td className="py-1 pr-2">
                    <Badge variant={o.side === 'BUY' ? 'success' : 'destructive'}>{o.side}</Badge>
                  </td>
                  <td className="py-1 pr-2 text-right font-mono">{formatPrice(o.orderPrice)}</td>
                  <td className="py-1 pr-2 text-right font-mono text-green-400">
                    {o.fillPrice != null ? formatPrice(o.fillPrice) : '—'}
                  </td>
                  <td className="py-1 pr-2 text-right">{o.qty}</td>
                  <td className="py-1">
                    <Badge variant={STATUS_VARIANT[o.status] ?? 'secondary'}>{o.status}</Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {totalLegs > MAX_DISPLAY && (
          <p className="text-xs text-muted-foreground mt-2 text-center">
            {totalLegs - MAX_DISPLAY} older order legs not shown — DB pagination needed for history
          </p>
        )}
      </CardContent>
    </Card>
  )
}
