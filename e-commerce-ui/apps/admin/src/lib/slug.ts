/** The backend's constraint on category and category-type codes, copied verbatim. */
export const CODE_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/

const MAX_CODE_LENGTH = 100

/**
 * Derives a code from a name. Codes must match CODE_PATTERN and are at most 100 characters,
 * so an upper-cased or underscored code is a 400 rather than a cosmetic difference. Diacritics
 * are folded rather than stripped to nothing, so "Abayāt" stays recognisable as "abayat".
 */
export function toCode(name: string): string {
  return name
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+/, '')
    .slice(0, MAX_CODE_LENGTH)
    .replace(/-+$/, '')
}
