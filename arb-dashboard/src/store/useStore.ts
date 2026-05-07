import { create } from 'zustand'
import type { MarketDataMsg, FvUpdateMsg, OrderUpdateMsg, SystemEventMsg } from '../types/messages'

export interface PricePoint {
  ts: number
  market: number   // price / 10_000
  fv: number       // futuresFv / 10_000
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

  // Orders (last 100)
  orders: OrderUpdateMsg[]
  handleOrderUpdate: (msg: OrderUpdateMsg) => void

  // Strategies
  strategies: StrategyState[]
  toggleStrategy: (id: string) => void

  // System events (last 50)
  events: SystemEventMsg[]
  handleSystemEvent: (msg: SystemEventMsg) => void

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
      const last = prev[prev.length - 1]
      const fv   = last?.fv ?? 0
      const next: PricePoint = { ts: msg.ts, market: msg.price / 10_000, fv }
      const arr  = [...prev.slice(-59), next]
      return { priceHistory: { ...s.priceHistory, [msg.symbol]: arr } }
    }),
  handleFvUpdate: (msg) =>
    set((s) => {
      const prev = s.priceHistory[msg.symbol] ?? []
      const last = prev[prev.length - 1]
      const market = last?.market ?? 0
      const next: PricePoint = { ts: msg.ts, market, fv: msg.futuresFv / 10_000 }
      const arr = [...prev.slice(-59), next]
      return { priceHistory: { ...s.priceHistory, [msg.symbol]: arr } }
    }),

  orders: [],
  handleOrderUpdate: (msg) =>
    set((s) => ({ orders: [msg, ...s.orders].slice(0, 100) })),

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

  darkMode: true,
  toggleDarkMode: () =>
    set((s) => {
      const next = !s.darkMode
      if (next) document.documentElement.classList.add('dark')
      else       document.documentElement.classList.remove('dark')
      return { darkMode: next }
    }),
}))
