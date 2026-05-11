export interface MarketDataMsg {
  type: 'MARKET_DATA'
  symbol: string
  exchange: string
  price: number   // fixed-point ×10^4
  qty: number
  ts: number
}

export interface FvUpdateMsg {
  type: 'FV_UPDATE'
  symbol: string
  exchange: string
  futuresFv: number       // ×10^4
  navPerUnit: number      // ×10^4
  basis: number           // ×10^4
  annualisedBasisBps100: number
  ts: number
}

export interface OrderUpdateMsg {
  type: 'ORDER_UPDATE'
  orderId: number
  symbol: string
  side: 'BUY' | 'SELL'
  fillPrice: number   // ×10^4
  fillQty: number
  status: 'NEW' | 'FILLED' | 'PARTIAL_FILL' | 'REJECTED' | 'CANCELLED'
  rejectCode: number
  basketId: number    // 0 = standalone
  legIndex: number    // 0 = standalone, 1 = futures/ETF leg, 2 = basket leg
  ts: number
}

export interface SystemEventMsg {
  type: 'SYSTEM_EVENT'
  eventType: 'INFO' | 'WARNING' | 'ERROR'
  message: string
  ts: number
}

export interface OrderRequestMsg {
  type: 'ORDER_REQUEST'
  orderId: number
  symbol: string
  side: 'BUY' | 'SELL'
  price: number       // ×10^4
  qty: number
  orderType: 'MARKET' | 'LIMIT'
  basketId: number
  legIndex: number    // 1 = futures/ETF, 2 = basket/spot
  ts: number
}

export interface SimulationStatusMsg {
  type: 'SIMULATION_STATUS'
  running: boolean
  profile: 'HKEX_BASIS_ARB' | 'TWSE_ETF_ARB' | 'SSF_CALENDAR' | 'HK_CN_PAIR'
  tickCount: number
  phase: 'STEADY' | 'ARB_RAMP' | 'ARB_WINDOW' | 'CONVERGENCE'
  ts: number
}

export type WsMessage = MarketDataMsg | FvUpdateMsg | OrderUpdateMsg | SystemEventMsg | OrderRequestMsg | SimulationStatusMsg

export interface ControlCommand {
  command: 'START_STRATEGY' | 'STOP_STRATEGY' | 'EMERGENCY_HALT' | 'START_SIMULATION' | 'STOP_SIMULATION' | 'SET_PROFILE'
  strategyId?: string
  profile?: string
}
