import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { useStore } from '@/store/useStore'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import type { LatencyStatsMsg } from '@/types/messages'

function nsFmt(ns: number): string {
  if (ns === 0) return '—'
  if (ns < 1_000) return `${ns}ns`
  if (ns < 1_000_000) return `${(ns / 1_000).toFixed(1)}µs`
  return `${(ns / 1_000_000).toFixed(2)}ms`
}

function buckets(s: LatencyStatsMsg) {
  return [
    { range: '<1µs',      count: s.b0Sub1us     },
    { range: '1–5µs',     count: s.b1to5us      },
    { range: '5–10µs',    count: s.b5to10us     },
    { range: '10–50µs',   count: s.b10to50us    },
    { range: '50–100µs',  count: s.b50to100us   },
    { range: '100–500µs', count: s.b100to500us  },
    { range: '>500µs',    count: s.bOver500us   },
  ]
}

const CATEGORY_LABELS: Record<string, string> = {
  'SIGNAL':   'Signal → Order Latency',
  'RISK_CHK': 'Risk Check Latency',
}

const CATEGORY_COLORS: Record<string, string> = {
  'SIGNAL':   '#38bdf8',
  'RISK_CHK': '#fb923c',
}

function LatencyPanel({ stat, color }: { stat: LatencyStatsMsg; color: string }) {
  return (
    <div>
      <div className="flex items-center justify-between mb-1">
        <p className="text-xs font-medium">{CATEGORY_LABELS[stat.category] ?? stat.category}</p>
        <div className="flex gap-3 text-xs text-muted-foreground">
          <span>min <span className="text-foreground font-mono">{nsFmt(stat.minNs)}</span></span>
          <span>avg <span className="text-foreground font-mono">{nsFmt(stat.avgNs)}</span></span>
          <span>max <span className="text-foreground font-mono">{nsFmt(stat.maxNs)}</span></span>
          <span className="text-muted-foreground">n={stat.sampleCount}</span>
        </div>
      </div>
      <ResponsiveContainer width="100%" height={100}>
        <BarChart data={buckets(stat)} margin={{ top: 0, right: 5, left: -20, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
          <XAxis dataKey="range" tick={{ fontSize: 9 }} />
          <YAxis tick={{ fontSize: 9 }} />
          <Tooltip
            contentStyle={{ backgroundColor: 'hsl(var(--card))', border: '1px solid hsl(var(--border))' }}
            formatter={(v: number) => [v, 'samples']}
          />
          <Bar dataKey="count" fill={color} radius={[2, 2, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

export function SystemHealth() {
  const { connected, events, latencyStats } = useStore()
  const categories = ['SIGNAL', 'RISK_CHK']
  const hasLatency = categories.some(c => latencyStats[c])

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
        {hasLatency ? (
          <div className="space-y-4">
            {categories.map(cat => latencyStats[cat] && (
              <LatencyPanel
                key={cat}
                stat={latencyStats[cat]}
                color={CATEGORY_COLORS[cat] ?? '#3b82f6'}
              />
            ))}
          </div>
        ) : (
          <div className="space-y-2">
            <p className="text-xs text-muted-foreground mb-1">Latency Histograms</p>
            <p className="text-xs text-muted-foreground italic">
              Waiting for first latency snapshot (published every 5s once arb trades fire)…
            </p>
          </div>
        )}

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

