import { useState, type ReactElement } from 'react'
import { Link, useParams } from 'react-router'
import { Badge } from '../../components/Badge'
import { Button } from '../../components/Button'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { ErrorPanel, Skeleton } from '../../components/QueryStates'
import { useToast } from '../../components/Toast'
import { describeError } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { VariantsCard } from '../variants/VariantsCard'
import { ProductDetailsCard } from './ProductDetailsCard'
import { useArchiveProduct, useProduct, useRestoreProduct } from './queries'

export function ProductDetailPage(): ReactElement {
  const { id = '' } = useParams<{ id: string }>()
  const product = useProduct(id)
  const archive = useArchiveProduct(id)
  const restore = useRestoreProduct(id)
  const showToast = useToast()
  const [confirming, setConfirming] = useState<'archive' | 'restore' | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  const close = (): void => {
    setConfirming(null)
    setFailure(null)
  }

  if (product.isPending) return <Skeleton rows={6} label="Loading product" />

  if (product.isError) {
    return (
      <div className="flex flex-col gap-3">
        <ErrorPanel
          error={product.error}
          onRetry={describeError(product.error).retryable ? () => void product.refetch() : undefined}
        />
        <Link to="/products" className="text-sm font-medium text-slate-700 underline">
          Back to products
        </Link>
      </div>
    )
  }

  const archivedAt = product.data.archivedAt

  const onArchive = (): void => {
    setFailure(null)
    archive.mutate(undefined, {
      onSuccess: () => {
        showToast('Product archived')
        close()
      },
      onError: (error) => setFailure(describeError(error).body),
    })
  }

  const onRestore = (): void => {
    setFailure(null)
    restore.mutate(undefined, {
      onSuccess: () => {
        showToast('Product restored')
        close()
      },
      onError: (error) => setFailure(describeError(error).body),
    })
  }

  return (
    <section className="flex max-w-3xl flex-col gap-4">
      <Link to="/products" className="text-sm text-slate-600 underline-offset-2 hover:underline">
        ← Back to products
      </Link>

      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold text-slate-900">{product.data.name}</h1>
          <p className="mt-1 text-sm text-slate-600">
            {`${product.data.categoryName} / ${product.data.categoryTypeName}`}
          </p>
          {archivedAt !== undefined ? (
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <Badge tone="neutral">Archived</Badge>
              <span className="text-xs text-slate-500">{`since ${formatDateTime(archivedAt)}`}</span>
            </div>
          ) : null}
        </div>

        {archivedAt !== undefined ? (
          <Button variant="secondary" onClick={() => setConfirming('restore')}>
            Restore product
          </Button>
        ) : (
          <Button variant="danger" onClick={() => setConfirming('archive')}>
            Archive product
          </Button>
        )}
      </header>

      <ProductDetailsCard product={product.data} />
      <VariantsCard product={product.data} />

      {/* Task 13 adds the images section here. */}

      <ConfirmDialog
        open={confirming === 'archive'}
        title="Archive this product?"
        confirmLabel="Archive"
        destructive
        busy={archive.isPending}
        error={failure}
        onConfirm={onArchive}
        onCancel={close}
      >
        {`Archiving hides "${product.data.name}" from customers. Nothing is deleted and you can restore it later.`}
      </ConfirmDialog>

      <ConfirmDialog
        open={confirming === 'restore'}
        title="Restore this product?"
        confirmLabel="Restore"
        busy={restore.isPending}
        error={failure}
        onConfirm={onRestore}
        onCancel={close}
      >
        {`Restoring shows "${product.data.name}" to customers again. Variants archived separately stay archived.`}
      </ConfirmDialog>
    </section>
  )
}
