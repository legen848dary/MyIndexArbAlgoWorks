import { useState } from 'react'
import { useStore } from './store/useStore'
import { useWebSocket } from './hooks/useWebSocket'
import { LiveMonitor } from './components/LiveMonitor'
import { OrderBook } from './components/OrderBook'
import { ControlPanel } from './components/ControlPanel'
import { SystemHealth } from './components/SystemHealth'
import { PnlPanel } from './components/PnlPanel'
import { TradesPage } from './components/TradesPage'
import { Moon, Sun } from 'lucide-react'
import { Button } from './components/ui/button'

const TABS = [
  { id: 'monitor', label: '📈 Monitor' },
  { id: 'trades',  label: '🔄 Trades'  },
  { id: 'orders',  label: '📋 Orders'  },
  { id: 'health',  label: '🩺 Health'  },
  { id: 'pnl',     label: '💰 P&L'     },
]

export default function App() {
  const { darkMode, toggleDarkMode, connected } = useStore()
  const { send } = useWebSocket()
  const [activeTab, setActiveTab] = useState('monitor')

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

        {/* Tab Navigation */}
        <div className="mx-auto max-w-screen-xl px-4">
          <nav className="flex gap-1 pb-0">
            {TABS.map((tab) => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                  activeTab === tab.id
                    ? 'border-primary text-primary'
                    : 'border-transparent text-muted-foreground hover:text-foreground'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </nav>
        </div>
      </header>

      {/* Main Content */}
      <main className="mx-auto max-w-screen-xl px-4 py-6">
        <div className="flex gap-4">
          {/* Left: main content area */}
          <div className="flex-1 min-w-0">
            {activeTab === 'monitor' && <LiveMonitor />}
            {activeTab === 'trades'  && <TradesPage />}
            {activeTab === 'orders'  && <OrderBook />}
            {activeTab === 'health'  && <SystemHealth />}
            {activeTab === 'pnl'     && <PnlPanel />}
          </div>

          {/* Right sidebar: always-visible ControlPanel */}
          <div className="w-72 flex-shrink-0">
            <ControlPanel send={send} />
          </div>
        </div>
      </main>
    </div>
  )
}
