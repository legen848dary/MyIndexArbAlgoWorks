import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatPrice(scaled4: number): string {
  return (scaled4 / 10_000).toLocaleString('en-HK', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

export function formatTimestamp(ns: number): string {
  return new Date(ns / 1_000_000).toLocaleTimeString('en-HK', { hour12: false })
}
