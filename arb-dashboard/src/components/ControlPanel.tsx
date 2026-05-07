import { useStore } from '@/store/useStore'
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { Button } from '@/components/ui/button'
import { AlertTriangle } from 'lucide-react'

interface ControlPanelProps {
  send: (cmd: object) => void
}

export function ControlPanel({ send }: ControlPanelProps) {
  const { strategies, toggleStrategy } = useStore()

  const handleToggle = (id: string, enabled: boolean) => {
    toggleStrategy(id)
    send({ command: enabled ? 'STOP_STRATEGY' : 'START_STRATEGY', strategyId: id })
  }

  const handleHalt = () => {
    if (confirm('Emergency Halt — stop ALL strategies immediately?')) {
      send({ command: 'EMERGENCY_HALT' })
    }
  }

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle>⚙️ Control Panel</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="space-y-2">
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
        <Button
          variant="destructive"
          className="w-full gap-2"
          onClick={handleHalt}
        >
          <AlertTriangle size={16} />
          Emergency Halt
        </Button>
      </CardContent>
    </Card>
  )
}
