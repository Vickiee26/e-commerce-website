import { describe, expect, it } from 'vitest'
import { CODE_PATTERN, toCode } from './slug'

describe('toCode', () => {
  it('produces the lower-case kebab-case code the backend pattern demands', () => {
    expect(toCode('Abaya')).toBe('abaya')
    expect(toCode('Hijabs')).toBe('hijabs')
    expect(toCode('Accessories')).toBe('accessories')
    expect(toCode('Head Scarves & Wraps')).toBe('head-scarves-wraps')
    expect(toCode('  Prayer   Sets  ')).toBe('prayer-sets')
    expect(toCode('Abaya_Open')).toBe('abaya-open')
  })

  it('strips diacritics rather than emitting characters the pattern rejects', () => {
    expect(toCode('Abayāt')).toBe('abayat')
  })

  it('returns an empty string when nothing usable is left', () => {
    expect(toCode('   ')).toBe('')
    expect(toCode('***')).toBe('')
  })

  it('truncates to 100 characters without leaving a trailing hyphen', () => {
    const code = toCode('a'.repeat(60) + ' ' + 'b'.repeat(60))
    expect(code.length).toBeLessThanOrEqual(100)
    expect(code.endsWith('-')).toBe(false)
    expect(CODE_PATTERN.test(code)).toBe(true)
  })

  it('every generated code satisfies CODE_PATTERN', () => {
    for (const name of ['Abaya', 'Head Scarves & Wraps', 'Abaya_Open', '  Prayer   Sets  ']) {
      expect(CODE_PATTERN.test(toCode(name))).toBe(true)
    }
  })
})
