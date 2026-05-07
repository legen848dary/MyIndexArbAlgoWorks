import { cn } from '@/lib/utils'
import React from 'react'

type Variant = 'default' | 'destructive' | 'outline' | 'ghost'
type Size    = 'default' | 'sm' | 'lg'

const variantClasses: Record<Variant, string> = {
  default:     'bg-primary text-primary-foreground hover:bg-primary/90',
  destructive: 'bg-destructive text-destructive-foreground hover:bg-destructive/90',
  outline:     'border border-input bg-background hover:bg-accent hover:text-accent-foreground',
  ghost:       'hover:bg-accent hover:text-accent-foreground',
}

const sizeClasses: Record<Size, string> = {
  default: 'h-10 px-4 py-2',
  sm:      'h-9 rounded-md px-3',
  lg:      'h-11 rounded-md px-8',
}

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: Size
}

export function Button({ className, variant = 'default', size = 'default', children, ...props }: ButtonProps) {
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center rounded-md text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50',
        variantClasses[variant],
        sizeClasses[size],
        className
      )}
      {...props}
    >
      {children}
    </button>
  )
}
