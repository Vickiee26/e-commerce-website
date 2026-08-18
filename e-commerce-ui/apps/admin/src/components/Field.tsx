import type { InputHTMLAttributes, ReactElement, ReactNode } from 'react'

/** Shared with <select> and <textarea> so every control lines up and clears 44px. */
export const inputClass =
  'min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-slate-900 focus:outline-none disabled:bg-slate-100 disabled:text-slate-500'

export type FieldProps = {
  label: string
  /** The id of the control inside, so the label and the error are wired to it. */
  htmlFor: string
  error?: string
  hint?: ReactNode
  required?: boolean
  children: ReactNode
}

export function Field({ label, htmlFor, error, hint, required = false, children }: FieldProps): ReactElement {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={htmlFor} className="text-sm font-medium text-slate-800">
        {label}
        {required ? <span className="text-red-600"> *</span> : null}
      </label>
      {children}
      {hint !== undefined ? (
        <p id={`${htmlFor}-hint`} className="text-xs text-slate-500">
          {hint}
        </p>
      ) : null}
      {error !== undefined ? (
        <p id={`${htmlFor}-error`} role="alert" className="text-sm text-red-700">
          {error}
        </p>
      ) : null}
    </div>
  )
}

export type TextInputProps = InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }

export function TextInput({ invalid = false, className = '', ...rest }: TextInputProps): ReactElement {
  return (
    <input
      className={`${inputClass} ${invalid ? 'border-red-500' : ''} ${className}`}
      aria-invalid={invalid}
      {...rest}
    />
  )
}
