import { useStore } from './store/useStore'
import { useWebSocket } from './hooks/useWebSocket'
import { LiveMonitor } from './components/LiveMonitor'
import { OrderBook } from './components/OrderBook'
import { ControlPanel } from './components/ControlPanel'
import { SystemHealth } from './components/SystemHealth'
import { PnlPanel } from './components/PnlPanel'
import { Moon, Sun } from 'lucide-react'
import { Button } from './components/ui/button'

export default function App() {
  const { darkMode, toggleDarkMode, connected } = useStore()
  const { send } = useWebSocket()

  return (
    <div className="min-h-screen bg-background text-foreground">
      {/* Top Bar */}
      <header className="sticky top-0 z-10 border-b bg-card/80 backdrop-blur-sm">
        <div className="mx-auto flex max-w-screen-xl items-center justify-between px-4 py-3">
          <div className="flex items-center gap-3">
            <span className="text-xl font-bold tracking-tight">⚡ IndexArb Cockpit</span>
            <span className="hidden text-xs text-muted-foreground sm:block">
              HK · TW · CN  |  Java 21  ·  Aeron IPC  ·  Zero-GC
            </span>
          </div>
          <div className="flex items-center gap-3">
            <span className={`text-xs font-medium ${connected ? 'text-green-400' : 'text-red-400'}`}>
              {connected ? '● Live' : '○ Offline'}
            </span>
            <Button variant="ghost" size="sm" onClick={toggleDarkMode} className="px-2">
              {darkMode ? <Sun size={16} /> : <Moon size={16} />}
            </Button>
          </div>
        </div>
      </header>

      {/* Main Grid */}
      <main className="mx-auto max-w-screen-xl px-4 py-6">
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
          {/* LiveMonitor spans 2 cols on large screens */}
          <div className="lg:col-span-2">
            <LiveMonitor />
          </div>
          {/* System Health */}
          <SystemHealth />
          {/* Order Book */}
          <OrderBook />
          {/* P&L Panel */}
          <PnlPanel />
          {/* Control Panel */}
          <ControlPanel send={send} />
        </div>
      </main>
    </div>
  )
}
