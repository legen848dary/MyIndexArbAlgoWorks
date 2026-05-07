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
  ts: number
}

export interface SystemEventMsg {
  type: 'SYSTEM_EVENT'
  eventType: 'INFO' | 'WARNING' | 'ERROR'
  message: string
  ts: number
}

export type WsMessage = MarketDataMsg | FvUpdateMsg | OrderUpdateMsg | SystemEventMsg

export interface ControlCommand {
  command: 'START_STRATEGY' | 'STOP_STRATEGY' | 'EMERGENCY_HALT'
  strategyId?: string
}
