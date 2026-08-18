import type { ComponentProps, ReactElement } from 'react'

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost'

const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary: 'bg-slate-900 text-white hover:bg-slate-800 disabled:bg-slate-400',
  secondary: 'border border-slate-300 bg-white text-slate-900 hover:bg-slate-50 disabled:text-slate-400',
  danger: 'bg-red-600 text-white hover:bg-red-700 disabled:bg-red-300',
  ghost: 'text-slate-700 hover:bg-slate-100 disabled:text-slate-400',
}

/** ComponentProps rather than ButtonHTMLAttributes so `ref` comes with it — React 19 passes it as
 * an ordinary prop, and the mobile drawer needs one to hand focus back to its trigger. */
export type ButtonProps = ComponentProps<'button'> & {
  variant?: ButtonVariant
  loading?: boolean
}

export function Button({
  variant = 'primary',
  loading = false,
  className = '',
  disabled = false,
  children,
  ...rest
}: ButtonProps): ReactElement {
  return (
    <button
      // Defaulted before ...rest so a caller can still pass type="submit".
      type="button"
      // min-h-11 is the 44px minimum touch target the responsive rules require.
      className={`inline-flex min-h-11 items-center justify-center gap-2 rounded-md px-4 text-sm font-medium transition disabled:cursor-not-allowed ${VARIANT_CLASS[variant]} ${className}`}
      disabled={disabled || loading}
      aria-busy={loading}
      {...rest}
    >
      {children}
    </button>
  )
}
