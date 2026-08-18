import type { ReactElement } from 'react'
import { Button } from './Button'

export function Pagination({
  page,
  totalPages,
  totalElements,
  onPageChange,
}: {
  page: number
  totalPages: number
  totalElements: number
  onPageChange: (page: number) => void
}): ReactElement | null {
  if (totalPages <= 1) return null

  return (
    <nav
      aria-label="Pagination"
      className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 pt-3"
    >
      <p className="text-sm text-slate-600">
        {`Page ${page + 1} of ${totalPages} · ${totalElements} product${totalElements === 1 ? '' : 's'}`}
      </p>
      <div className="flex gap-2">
        <Button
          variant="secondary"
          aria-label="Previous page"
          disabled={page === 0}
          onClick={() => onPageChange(page - 1)}
        >
          Previous
        </Button>
        <Button
          variant="secondary"
          aria-label="Next page"
          disabled={page >= totalPages - 1}
          onClick={() => onPageChange(page + 1)}
        >
          Next
        </Button>
      </div>
    </nav>
  )
}
