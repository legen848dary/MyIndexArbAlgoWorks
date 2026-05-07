import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { useStore } from '@/store/useStore'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'

export function SystemHealth() {
  const { connected, events } = useStore()

  const latencyBuckets = [
    { range: '<10µs',   count: 1240 },
    { range: '10-20µs', count: 860  },
    { range: '20-30µs', count: 420  },
    { range: '30-40µs', count: 180  },
    { range: '40-50µs', count: 60   },
    { range: '>50µs',   count: 12   },
  ]

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle>💊 System Health</CardTitle>
          <Badge variant={connected ? 'success' : 'destructive'}>
            {connected ? '● LIVE' : '○ DISCONNECTED'}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div>
          <p className="text-xs text-muted-foreground mb-2">MockExchange Round-Trip Latency</p>
          <ResponsiveContainer width="100%" height={120}>
            <BarChart data={latencyBuckets} margin={{ top: 0, right: 5, left: -20, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
              <XAxis dataKey="range" tick={{ fontSize: 10 }} />
              <YAxis tick={{ fontSize: 10 }} />
              <Tooltip
                contentStyle={{ backgroundColor: 'hsl(var(--card))', border: '1px solid hsl(var(--border))' }}
              />
              <Bar dataKey="count" fill="#3b82f6" radius={[2, 2, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>

        <div>
          <p className="text-xs text-muted-foreground mb-2">Recent Events</p>
          <div className="space-y-1 max-h-32 overflow-auto">
            {events.length === 0 && (
              <p className="text-xs text-muted-foreground">No events</p>
            )}
            {events.slice(0, 10).map((e, i) => (
              <div key={i} className="flex items-start gap-2 text-xs">
                <Badge
                  variant={e.eventType === 'ERROR' ? 'destructive' : e.eventType === 'WARNING' ? 'warning' : 'default'}
                  className="shrink-0"
                >
                  {e.eventType}
                </Badge>
                <span className="text-muted-foreground truncate">{e.message}</span>
              </div>
            ))}
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
