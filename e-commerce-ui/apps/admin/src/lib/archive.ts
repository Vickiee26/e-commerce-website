/**
 * Archiving is a timestamp, and the API has two ways of saying a row is live: the field absent, or
 * the field present as null. Everything here goes through one predicate so a live row cannot be
 * mistaken for an archived one in a component that only remembered to check for `undefined`.
 *
 * The e2e suite is what found this — every mocked fixture omitted `archivedAt`, so nothing caught
 * that the real backend sends `archivedAt: null` and made every live variant look archived.
 */
export function archivedAt(row: { archivedAt?: string | null }): string | null {
  return row.archivedAt ?? null
}

export function isArchived(row: { archivedAt?: string | null }): boolean {
  return archivedAt(row) !== null
}
