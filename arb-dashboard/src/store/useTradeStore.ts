import { create } from 'zustand'
import type { OrderRequestMsg, OrderUpdateMsg } from '../types/messages'

export interface LegInfo {
  orderId: number
  symbol: string
  side: 'BUY' | 'SELL'
  price: number       // ×10^4 from request
  qty: number
  orderType?: 'MARKET' | 'LIMIT'
  fillPrice?: number  // ×10^4, set when filled
  fillQty?: number
  status: 'PENDING' | 'FILLED' | 'PARTIAL_FILL' | 'REJECTED' | 'CANCELLED'
}

export interface ArbTrade {
  basketId: number
  strategy: string
  timestamp: number
  leg1?: LegInfo      // futures/ETF leg
  leg2Legs: LegInfo[] // constituent basket legs
  pnl?: number
  status: 'OPEN' | 'PARTIAL' | 'COMPLETE' | 'FAILED'
}

interface TradeStoreState {
  trades: Map<number, ArbTrade>
  recentTrades: ArbTrade[]  // last 20 completed/open trades for rendering

  handleOrderRequest: (msg: OrderRequestMsg) => void
  handleOrderUpdate: (msg: OrderUpdateMsg) => void
}

const MAX_TRADES = 20

export const useTradeStore = create<TradeStoreState>()((set) => ({
  trades: new Map(),
  recentTrades: [],

  handleOrderRequest: (msg) => {
    if (msg.basketId === 0) return

    set((s) => {
      const trades = new Map(s.trades)
      const existing = trades.get(msg.basketId)

      const legInfo: LegInfo = {
        orderId: msg.orderId,
        symbol: msg.symbol,
        side: msg.side,
        price: msg.price,
        qty: msg.qty,
        orderType: msg.orderType,
        status: 'PENDING',
      }

      if (!existing) {
        const trade: ArbTrade = {
          basketId: msg.basketId,
          strategy: inferStrategy(msg.symbol),
          timestamp: msg.ts,
          leg1: msg.legIndex === 1 ? legInfo : undefined,
          leg2Legs: msg.legIndex === 2 ? [legInfo] : [],
          status: 'OPEN',
        }
        trades.set(msg.basketId, trade)
      } else {
        if (msg.legIndex === 1) {
          existing.leg1 = legInfo
        } else {
          existing.leg2Legs = [...existing.leg2Legs, legInfo]
        }
        trades.set(msg.basketId, existing)
      }

      const recentTrades = Array.from(trades.values())
        .sort((a, b) => b.timestamp - a.timestamp)
        .slice(0, MAX_TRADES)

      return { trades, recentTrades }
    })
  },

  handleOrderUpdate: (msg) => {
    if (msg.basketId === 0) return

    set((s) => {
      const trades = new Map(s.trades)
      const trade = trades.get(msg.basketId)
      if (!trade) return s

      const updateLeg = (leg: LegInfo): LegInfo => {
        if (leg.orderId !== msg.orderId) return leg
        return {
          ...leg,
          fillPrice: msg.fillPrice,
          fillQty: msg.fillQty,
          status: msg.status as LegInfo['status'],
        }
      }

      const updatedTrade: ArbTrade = {
        ...trade,
        leg1: trade.leg1 ? updateLeg(trade.leg1) : undefined,
        leg2Legs: trade.leg2Legs.map(updateLeg),
      }

      const { pnl, status } = computeTradeState(updatedTrade)
      updatedTrade.pnl = pnl
      updatedTrade.status = status

      trades.set(msg.basketId, updatedTrade)

      const recentTrades = Array.from(trades.values())
        .sort((a, b) => b.timestamp - a.timestamp)
        .slice(0, MAX_TRADES)

      return { trades, recentTrades }
    })
  },
}))

function inferStrategy(symbol: string): string {
  if (symbol.includes('HSI') || symbol.endsWith('.HK')) return 'HkexBasisArb'
  if (symbol.includes('0050') || symbol.includes('TW')) return 'TwseEtfArb'
  if (symbol.includes('CSI') || symbol.includes('CN')) return 'HkCnIndexPairArb'
  if (symbol.includes('SSF')) return 'SsfBasisArb'
  return 'Unknown'
}

function computeTradeState(trade: ArbTrade): { pnl: number | undefined; status: ArbTrade['status'] } {
  // Only the signal leg (leg1) rejection is fatal — hedge leg rejections are tolerated
  if (trade.leg1?.status === 'REJECTED') return { pnl: undefined, status: 'FAILED' }

  const leg1Filled = trade.leg1?.status === 'FILLED'
  // Exclude rejected leg2 orders from the fill-completion check
  const activeLeg2 = trade.leg2Legs.filter(l => l.status !== 'REJECTED')
  const leg2AllFilled = activeLeg2.length > 0 && activeLeg2.every(l => l.status === 'FILLED')

  if (leg1Filled && leg2AllFilled) {
    const leg1Notional = (trade.leg1!.fillPrice ?? 0) / 10_000 * (trade.leg1!.fillQty ?? 0)
    const leg2Notional = activeLeg2.reduce(
      (sum, l) => sum + (l.fillPrice ?? 0) / 10_000 * (l.fillQty ?? 0), 0)
    const pnl = trade.leg1!.side === 'SELL'
      ? leg1Notional - leg2Notional
      : leg2Notional - leg1Notional
    return { pnl, status: 'COMPLETE' }
  }
  if (leg1Filled || trade.leg2Legs.some(l => l.status === 'FILLED')) {
    return { pnl: undefined, status: 'PARTIAL' }
  }
  return { pnl: undefined, status: 'OPEN' }
}
