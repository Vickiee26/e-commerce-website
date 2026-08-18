import type { AdminProduct, AdminVariant } from '@shopflow/api-client'
import { useState, type ReactElement } from 'react'
import { Badge } from '../../components/Badge'
import { Button } from '../../components/Button'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { useToast } from '../../components/Toast'
import { isArchived } from '../../lib/archive'
import { describeError } from '../../lib/errors'
import { StockDialog } from './StockDialog'
import { VariantFormDialog } from './VariantFormDialog'
import { useArchiveVariant, useRestoreVariant } from './queries'

type OpenDialog =
  | { kind: 'none' }
  | { kind: 'add' }
  | { kind: 'edit'; variant: AdminVariant }
  | { kind: 'stock'; variant: AdminVariant }
  | { kind: 'archive'; variant: AdminVariant }
  | { kind: 'restore'; variant: AdminVariant }

const CLOSED: OpenDialog = { kind: 'none' }

function label(variant: AdminVariant): string {
  return `${variant.color} / ${variant.size}`
}

export function VariantsCard({ product }: { product: AdminProduct }): ReactElement {
  const [dialog, setDialog] = useState<OpenDialog>(CLOSED)
  const [failure, setFailure] = useState<string | null>(null)
  const archive = useArchiveVariant(product.id)
  const restore = useRestoreVariant(product.id)
  const showToast = useToast()

  const close = (): void => {
    setDialog(CLOSED)
    setFailure(null)
  }

  const liveCount = product.variants.filter((variant) => !isArchived(variant)).length

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-semibold text-slate-900">Variants</h2>
        <Button variant="secondary" onClick={() => setDialog({ kind: 'add' })}>
          Add variant
        </Button>
      </header>

      {liveCount === 0 ? (
        <p className="mt-3 rounded-md bg-amber-50 p-3 text-sm text-amber-900">
          No variants yet — customers cannot buy this product
        </p>
      ) : null}

      {product.variants.length > 0 ? (
        <ul className="mt-3 flex flex-col gap-3">
          {product.variants.map((variant) => {
            const archived = isArchived(variant)

            return (
              <li
                key={variant.id}
                aria-label={label(variant)}
                className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-slate-200 p-3"
              >
                <div className="min-w-0">
                  <p className="font-medium text-slate-900">{label(variant)}</p>
                  <p className="mt-1 flex flex-wrap items-center gap-2 text-sm text-slate-600">
                    <span>{`${variant.stockQuantity} in stock`}</span>
                    {archived ? <Badge tone="neutral">Archived</Badge> : null}
                    {!archived && variant.stockQuantity === 0 ? (
                      <Badge tone="danger">Out of stock</Badge>
                    ) : null}
                  </p>
                </div>

                <div className="flex flex-wrap gap-2">
                  {archived ? (
                    <Button
                      variant="secondary"
                      aria-label={`Restore ${label(variant)}`}
                      onClick={() => setDialog({ kind: 'restore', variant })}
                    >
                      Restore
                    </Button>
                  ) : (
                    <>
                      <Button
                        variant="secondary"
                        aria-label={`Adjust stock for ${label(variant)}`}
                        onClick={() => setDialog({ kind: 'stock', variant })}
                      >
                        Adjust stock
                      </Button>
                      <Button
                        variant="ghost"
                        aria-label={`Edit ${label(variant)}`}
                        onClick={() => setDialog({ kind: 'edit', variant })}
                      >
                        Edit
                      </Button>
                      <Button
                        variant="ghost"
                        className="text-red-700"
                        aria-label={`Archive ${label(variant)}`}
                        onClick={() => setDialog({ kind: 'archive', variant })}
                      >
                        Archive
                      </Button>
                    </>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      ) : null}

      <VariantFormDialog open={dialog.kind === 'add'} productId={product.id} onClose={close} />

      {dialog.kind === 'edit' ? (
        <VariantFormDialog open productId={product.id} variant={dialog.variant} onClose={close} />
      ) : null}

      {dialog.kind === 'stock' ? (
        <StockDialog open productId={product.id} variant={dialog.variant} onClose={close} />
      ) : null}

      {dialog.kind === 'archive' ? (
        <ConfirmDialog
          open
          title={`Archive ${label(dialog.variant)}?`}
          confirmLabel="Archive"
          destructive
          busy={archive.isPending}
          error={failure}
          onCancel={close}
          onConfirm={() => {
            setFailure(null)
            archive.mutate(dialog.variant.id, {
              onSuccess: () => {
                showToast('Variant archived')
                close()
              },
              onError: (error) => setFailure(describeError(error).body),
            })
          }}
        >
          {`Archiving "${label(dialog.variant)}" removes it from sale. Its stock and history are kept.`}
        </ConfirmDialog>
      ) : null}

      {dialog.kind === 'restore' ? (
        <ConfirmDialog
          open
          title={`Restore ${label(dialog.variant)}?`}
          confirmLabel="Restore"
          busy={restore.isPending}
          error={failure}
          onCancel={close}
          onConfirm={() => {
            setFailure(null)
            restore.mutate(dialog.variant.id, {
              onSuccess: () => {
                showToast('Variant restored')
                close()
              },
              onError: (error) => setFailure(describeError(error).body),
            })
          }}
        >
          {`Restoring "${label(dialog.variant)}" puts it back on sale with ${dialog.variant.stockQuantity} in stock.`}
        </ConfirmDialog>
      ) : null}
    </section>
  )
}
