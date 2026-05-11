import { useEffect, useRef, useCallback } from 'react'
import { useStore } from '../store/useStore'
import { useTradeStore } from '../store/useTradeStore'
import type { WsMessage, OrderRequestMsg, OrderUpdateMsg, SimulationStatusMsg, LatencyStatsMsg } from '../types/messages'

const WS_URL = 'ws://localhost:8080/ws'
const RECONNECT_DELAY = 3000

export function useWebSocket() {
  const ws         = useRef<WebSocket | null>(null)
  const reconnectT = useRef<ReturnType<typeof setTimeout> | null>(null)
  const {
    setConnected, handleMarketData, handleFvUpdate,
    handleOrderUpdate, handleSystemEvent, handleSimulationStatus, handleLatencyStats,
  } = useStore()
  const {
    handleOrderRequest,
    handleOrderUpdate: handleTradeOrderUpdate,
  } = useTradeStore()

  const connect = useCallback(() => {
    const socket = new WebSocket(WS_URL)
    ws.current   = socket

    socket.onopen = () => {
      setConnected(true)
      if (reconnectT.current) clearTimeout(reconnectT.current)
    }

    socket.onmessage = (ev) => {
      try {
        const msg: WsMessage = JSON.parse(ev.data as string)
        switch (msg.type) {
          case 'MARKET_DATA':        handleMarketData(msg);                        break
          case 'FV_UPDATE':          handleFvUpdate(msg);                          break
          case 'ORDER_UPDATE':
            handleOrderUpdate(msg as OrderUpdateMsg)
            if ((msg as OrderUpdateMsg).basketId !== 0) handleTradeOrderUpdate(msg as OrderUpdateMsg)
            break
          case 'SYSTEM_EVENT':       handleSystemEvent(msg);                       break
          case 'ORDER_REQUEST':      handleOrderRequest(msg as OrderRequestMsg);   break
          case 'SIMULATION_STATUS':  handleSimulationStatus(msg as SimulationStatusMsg); break
          case 'LATENCY_STATS':      handleLatencyStats(msg as LatencyStatsMsg);         break
        }
      } catch { /* ignore malformed messages */ }
    }

    socket.onclose = () => {
      setConnected(false)
      reconnectT.current = setTimeout(connect, RECONNECT_DELAY)
    }

    socket.onerror = () => {
      socket.close()
    }
  }, [
    setConnected, handleMarketData, handleFvUpdate,
    handleOrderUpdate, handleSystemEvent, handleSimulationStatus, handleLatencyStats,
    handleOrderRequest, handleTradeOrderUpdate,
  ])

  useEffect(() => {
    connect()
    return () => {
      if (reconnectT.current) clearTimeout(reconnectT.current)
      ws.current?.close()
    }
  }, [connect])

  const send = useCallback((cmd: object | string) => {
    if (ws.current?.readyState === WebSocket.OPEN) {
      ws.current.send(typeof cmd === 'string' ? cmd : JSON.stringify(cmd))
    }
  }, [])

  return { send }
}
