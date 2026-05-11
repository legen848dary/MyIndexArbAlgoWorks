import { cn } from '@/lib/utils'
import React from 'react'

type Variant = 'default' | 'secondary' | 'success' | 'destructive' | 'warning'

const variants: Record<Variant, string> = {
  default:     'bg-primary text-primary-foreground',
  secondary:   'bg-secondary text-secondary-foreground',
  success:     'bg-green-500 text-white dark:bg-green-600',
  destructive: 'bg-destructive text-destructive-foreground',
  warning:     'bg-yellow-500 text-white dark:bg-yellow-600',
}

interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?: Variant
}

export function Badge({ className, variant = 'default', children, ...props }: BadgeProps) {
  return (
    <span
      className={cn('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold', variants[variant], className)}
      {...props}
    >
      {children}
    </span>
  )
}
