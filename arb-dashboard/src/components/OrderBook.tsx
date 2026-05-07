import { useStore } from '@/store/useStore'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { formatPrice, formatTimestamp } from '@/lib/utils'

export function OrderBook() {
  const orders = useStore((s) => s.orders)

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle>📋 Order Book</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="overflow-auto max-h-64">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b text-muted-foreground">
                <th className="text-left py-1 pr-2">ID</th>
                <th className="text-left py-1 pr-2">Symbol</th>
                <th className="text-left py-1 pr-2">Side</th>
                <th className="text-right py-1 pr-2">Price</th>
                <th className="text-right py-1 pr-2">Qty</th>
                <th className="text-left py-1 pr-2">Status</th>
                <th className="text-right py-1">Time</th>
              </tr>
            </thead>
            <tbody>
              {orders.length === 0 && (
                <tr><td colSpan={7} className="py-4 text-center text-muted-foreground">No orders yet</td></tr>
              )}
              {orders.map((o) => (
                <tr key={o.orderId} className="border-b border-border/40 hover:bg-muted/30">
                  <td className="py-1 pr-2 font-mono">{o.orderId}</td>
                  <td className="py-1 pr-2 font-mono">{o.symbol}</td>
                  <td className="py-1 pr-2">
                    <Badge variant={o.side === 'BUY' ? 'success' : 'destructive'}>{o.side}</Badge>
                  </td>
                  <td className="py-1 pr-2 text-right font-mono">{formatPrice(o.fillPrice)}</td>
                  <td className="py-1 pr-2 text-right">{o.fillQty}</td>
                  <td className="py-1 pr-2">
                    <Badge
                      variant={
                        o.status === 'FILLED'   ? 'success' :
                        o.status === 'REJECTED' ? 'destructive' :
                        'warning'
                      }
                    >
                      {o.status}
                    </Badge>
                  </td>
                  <td className="py-1 text-right text-muted-foreground">{formatTimestamp(o.ts)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </CardContent>
    </Card>
  )
}
