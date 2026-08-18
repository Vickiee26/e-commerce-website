import { describe, expect, it } from 'vitest'
import { formatDateTime, formatUsd } from './format'

describe('formatUsd', () => {
  it('formats catalogue prices as USD', () => {
    expect(formatUsd(49)).toBe('$49.00')
    expect(formatUsd(1299.5)).toBe('$1,299.50')
    expect(formatUsd(0)).toBe('$0.00')
  })
})

describe('formatDateTime', () => {
  it('renders an ISO instant in a readable form', () => {
    expect(formatDateTime('2026-08-17T09:30:00Z')).toMatch(/Aug 17, 2026/)
  })
})
