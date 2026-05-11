import { useState } from 'react'
import { useStore } from '@/store/useStore'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { AlertTriangle, Play, Square } from 'lucide-react'

const SIM_PROFILES = [
  { value: 'HKEX_BASIS_ARB', label: 'HSI Basis Arb (HKEX)' },
  { value: 'TWSE_ETF_ARB',   label: 'TWSE ETF NAV Arb'      },
  { value: 'SSF_CALENDAR',   label: 'SSF Calendar Spread'   },
  { value: 'HK_CN_PAIR',     label: 'HK/CN Index Pair'      },
]

const PHASE_COLORS: Record<string, string> = {
  STEADY:      'text-slate-400',
  ARB_RAMP:    'text-yellow-400',
  ARB_WINDOW:  'text-green-400',
  CONVERGENCE: 'text-blue-400',
}

interface ControlPanelProps {
  send: (cmd: object | string) => void
}

export function ControlPanel({ send }: ControlPanelProps) {
  const { strategies, toggleStrategy, simRunning, simProfile, simPhase } = useStore()
  const [selectedProfile, setSelectedProfile] = useState('HKEX_BASIS_ARB')

  const handleToggle = (id: string, enabled: boolean) => {
    toggleStrategy(id)
    send({ command: enabled ? 'STOP_STRATEGY' : 'START_STRATEGY', strategyId: id })
  }

  const handleHalt = () => {
    if (confirm('Emergency Halt — stop ALL strategies immediately?')) {
      send({ command: 'EMERGENCY_HALT' })
    }
  }

  const handleStartSim = () => {
    send(`START_SIMULATION:${selectedProfile}`)
  }

  const handleStopSim = () => {
    send('STOP_SIMULATION')
  }

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle>⚙️ Control Panel</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">

        {/* Simulation Control */}
        <div className="rounded-lg border border-border/60 bg-muted/30 p-3 space-y-3">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold">Simulation</span>
            <div className="flex items-center gap-2">
              <span className={`text-xs font-medium ${PHASE_COLORS[simPhase] ?? 'text-slate-400'}`}>
                {simPhase}
              </span>
              <Badge variant={simRunning ? 'default' : 'secondary'} className="text-xs">
                {simRunning ? '▶ Running' : '■ Stopped'}
              </Badge>
            </div>
          </div>

          <select
            value={selectedProfile}
            onChange={(e) => setSelectedProfile(e.target.value)}
            className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm"
          >
            {SIM_PROFILES.map((p) => (
              <option key={p.value} value={p.value}>{p.label}</option>
            ))}
          </select>

          <div className="flex gap-2">
            <Button
              size="sm"
              className="flex-1 gap-1"
              onClick={handleStartSim}
              disabled={simRunning}
            >
              <Play size={12} />
              Start
            </Button>
            <Button
              size="sm"
              variant="outline"
              className="flex-1 gap-1"
              onClick={handleStopSim}
              disabled={!simRunning}
            >
              <Square size={12} />
              Stop
            </Button>
          </div>

          {simRunning && (
            <p className="text-xs text-muted-foreground">
              Profile: <span className="font-medium text-foreground">{simProfile}</span>
              &nbsp;·&nbsp;Arb window every ~13s
            </p>
          )}
        </div>

        {/* Strategy Toggles */}
        <div className="space-y-2">
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Strategies</p>
          {strategies.map((st) => (
            <div key={st.id} className="flex items-center justify-between rounded-md border p-2">
              <span className="text-sm font-medium">{st.id}</span>
              <Switch
                checked={st.enabled}
                onCheckedChange={(checked) => handleToggle(st.id, !checked)}
              />
            </div>
          ))}
        </div>

        {/* Kill Switch */}
        <Button
          variant="destructive"
          className="w-full gap-2"
          onClick={handleHalt}
        >
          <AlertTriangle size={16} />
          Kill Switch
        </Button>
      </CardContent>
    </Card>
  )
}
