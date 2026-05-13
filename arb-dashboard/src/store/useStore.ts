import { create } from 'zustand'
import type { MarketDataMsg, FvUpdateMsg, OrderUpdateMsg, SystemEventMsg, SimulationStatusMsg, LatencyStatsMsg, OrderRequestMsg } from '../types/messages'
import { useTradeStore } from './useTradeStore'

export interface PricePoint {
  ts: number
  market: number   // futures market price = (futuresFv + basis) / 10_000
  fv: number       // futures fair value = futuresFv / 10_000
  bps?: number     // annualised basis in BPS (not ×100) — for tooltip display
  signal?: 'BUY' | 'SELL'  // set when a leg-1 arb order fires on this tick
}

export interface StrategyState {
  id: string
  enabled: boolean
}

const STRATEGIES = [
  'HkexBasisArb', 'MhiHsiBasisArb', 'TwseEtfArb', 'CrossBorderEtfArb',
  'SsfBasisArb', 'SsfCalendarSpreadArb', 'HkCnIndexPairArb', 'VolSkewBasisArb',
]

interface AppState {
  // Connection
  connected: boolean
  setConnected: (v: boolean) => void

  // Market data (last 60 points per symbol)
  priceHistory: Record<string, PricePoint[]>
  handleMarketData: (msg: MarketDataMsg) => void
  handleFvUpdate: (msg: FvUpdateMsg) => void
  handleOrderRequest: (msg: OrderRequestMsg) => void  // tags leg-1 signal onto latest price point

  // Orders (last 100)
  orders: OrderUpdateMsg[]
  handleOrderUpdate: (msg: OrderUpdateMsg) => void

  // P&L tracking
  pnlHistory: Array<{ ts: number; pnl: number }>
  cumulativePnl: number

  // Strategies
  strategies: StrategyState[]
  toggleStrategy: (id: string) => void

  // System events (last 50)
  events: SystemEventMsg[]
  handleSystemEvent: (msg: SystemEventMsg) => void

  // Simulation state
  simRunning: boolean
  simProfile: string
  simPhase: string
  simTickCount: number
  handleSimulationStatus: (msg: SimulationStatusMsg) => void

  // Latency stats (keyed by category: "SIGNAL", "RISK_CHK")
  latencyStats: Record<string, LatencyStatsMsg>
  handleLatencyStats: (msg: LatencyStatsMsg) => void

  // Dark mode
  darkMode: boolean
  toggleDarkMode: () => void
}

export const useStore = create<AppState>()((set) => ({
  connected: false,
  setConnected: (v) => set({ connected: v }),

  priceHistory: {},
  handleMarketData: (msg) =>
    set((s) => {
      const prev = s.priceHistory[msg.symbol] ?? []
      // Once FV updates are flowing (fv > 0), let them drive the chart exclusively
      // to prevent interleaving spot prices with futures-vs-FV data.
      if (prev.length > 0 && prev[prev.length - 1].fv > 0) return s
      const last = prev[prev.length - 1]
      const fv   = last?.fv ?? 0
      const next: PricePoint = { ts: msg.ts, market: msg.price / 10_000, fv }
      const arr  = [...prev.slice(-59), next]
      return { priceHistory: { ...s.priceHistory, [msg.symbol]: arr } }
    }),
  handleFvUpdate: (msg) =>
    set((s) => {
      const prev = s.priceHistory[msg.symbol] ?? []
      // market = futures market price (what you can actually trade), fv = theoretical fair value
      const futuresMktPrice = (msg.futuresFv + msg.basis) / 10_000
      const futuresFv       = msg.futuresFv / 10_000
      const bps             = msg.annualisedBasisBps100 / 100
      const next: PricePoint = { ts: msg.ts, market: futuresMktPrice, fv: futuresFv, bps }
      const arr = [...prev.slice(-59), next]
      return { priceHistory: { ...s.priceHistory, [msg.symbol]: arr } }
    }),
  handleOrderRequest: (msg) => {
    // Only leg-1 (signal leg) triggers the chart marker
    if (msg.legIndex !== 1) return
    set((s) => {
      const pts = s.priceHistory[msg.symbol]
      if (!pts || pts.length === 0) return s
      const updated = [...pts]
      updated[updated.length - 1] = { ...updated[updated.length - 1], signal: msg.side as 'BUY' | 'SELL' }
      return { priceHistory: { ...s.priceHistory, [msg.symbol]: updated } }
    })
  },

  orders: [],
  handleOrderUpdate: (msg) =>
    set((s) => {
      const orders = [msg, ...s.orders].slice(0, 100)
      let pnlDelta = 0
      if (msg.status === 'FILLED' || msg.status === 'PARTIAL_FILL') {
        // fillPrice is ×10^4; divide by 10^4 then multiply by fillQty → HKD notional
        const contribution = (msg.fillPrice / 10_000) * msg.fillQty
        pnlDelta = msg.side === 'SELL' ? contribution : -contribution
      }
      const cumulativePnl = s.cumulativePnl + pnlDelta
      const newPoint = pnlDelta !== 0
        ? [{ ts: msg.ts, pnl: cumulativePnl }, ...s.pnlHistory].slice(0, 60)
        : s.pnlHistory
      return { orders, cumulativePnl, pnlHistory: newPoint }
    }),

  pnlHistory: [],
  cumulativePnl: 0,

  strategies: STRATEGIES.map((id) => ({ id, enabled: true })),
  toggleStrategy: (id) =>
    set((s) => ({
      strategies: s.strategies.map((st) =>
        st.id === id ? { ...st, enabled: !st.enabled } : st
      ),
    })),

  events: [],
  handleSystemEvent: (msg) =>
    set((s) => ({ events: [msg, ...s.events].slice(0, 50) })),

  simRunning: false,
  simProfile: 'HKEX_BASIS_ARB',
  simPhase: 'STEADY',
  simTickCount: 0,
  handleSimulationStatus: (msg) => {
    // When a new simulation starts, flush all stale trade/order/P&L state
    if (msg.phase === 'STARTING') {
      useTradeStore.getState().clearAll()
      set(() => ({
        simRunning:   true,
        simProfile:   msg.profile || 'HKEX_BASIS_ARB',
        simPhase:     'STARTING',
        simTickCount: 0,
        orders:       [],
        cumulativePnl: 0,
        pnlHistory:   [],
      }))
      return
    }
    set((s) => ({
      simRunning:   msg.running ?? msg.phase !== 'STOPPED',
      simProfile:   msg.profile || s.simProfile,
      simPhase:     msg.phase,
      simTickCount: msg.tickCount,
    }))
  },

  latencyStats: {},
  handleLatencyStats: (msg) =>
    set((s) => ({ latencyStats: { ...s.latencyStats, [msg.category]: msg } })),

  darkMode: true,
  toggleDarkMode: () =>
    set((s) => {
      const next = !s.darkMode
      if (next) document.documentElement.classList.add('dark')
      else       document.documentElement.classList.remove('dark')
      return { darkMode: next }
    }),
}))
