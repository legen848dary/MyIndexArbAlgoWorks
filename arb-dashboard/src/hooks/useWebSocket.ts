import { useEffect, useRef, useCallback } from 'react'
import { useStore } from '../store/useStore'
import type { WsMessage } from '../types/messages'

const WS_URL = 'ws://localhost:8080/ws'
const RECONNECT_DELAY = 3000

export function useWebSocket() {
  const ws         = useRef<WebSocket | null>(null)
  const reconnectT = useRef<ReturnType<typeof setTimeout> | null>(null)
  const { setConnected, handleMarketData, handleFvUpdate, handleOrderUpdate, handleSystemEvent } = useStore()

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
          case 'MARKET_DATA':    handleMarketData(msg);  break
          case 'FV_UPDATE':      handleFvUpdate(msg);    break
          case 'ORDER_UPDATE':   handleOrderUpdate(msg); break
          case 'SYSTEM_EVENT':   handleSystemEvent(msg); break
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
  }, [setConnected, handleMarketData, handleFvUpdate, handleOrderUpdate, handleSystemEvent])

  useEffect(() => {
    connect()
    return () => {
      if (reconnectT.current) clearTimeout(reconnectT.current)
      ws.current?.close()
    }
  }, [connect])

  const send = useCallback((cmd: object) => {
    if (ws.current?.readyState === WebSocket.OPEN) {
      ws.current.send(JSON.stringify(cmd))
    }
  }, [])

  return { send }
}
