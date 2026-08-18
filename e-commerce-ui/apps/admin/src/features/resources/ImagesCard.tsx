import type { AdminProduct, ProductResource } from '@shopflow/api-client'
import { useState, type ReactElement } from 'react'
import { Badge } from '../../components/Badge'
import { Button } from '../../components/Button'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { useToast } from '../../components/Toast'
import { describeError } from '../../lib/errors'
import { AddImageForm } from './AddImageForm'
import { ImagePreview } from './ImagePreview'
import { useDeleteResource, useUpdateResource } from './queries'

function label(resource: ProductResource): string {
  return resource.name ?? resource.url
}

export function ImagesCard({ product }: { product: AdminProduct }): ReactElement {
  const [removing, setRemoving] = useState<ProductResource | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const update = useUpdateResource(product.id)
  const remove = useDeleteResource(product.id)
  const showToast = useToast()

  const hasPrimary = product.resources.some((resource) => resource.isPrimary)

  const close = (): void => {
    setRemoving(null)
    setFailure(null)
  }

  const promote = (resource: ProductResource): void => {
    // Only isPrimary is sent: UpdateResourceRequest is partial, and resending url or name would
    // risk overwriting a value someone else just changed.
    update.mutate(
      { resourceId: resource.id, body: { isPrimary: true } },
      {
        onSuccess: () => showToast(`"${label(resource)}" is now the primary image`),
        onError: (error) => showToast(describeError(error).body),
      },
    )
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-lg font-semibold text-slate-900">Images</h2>

      {product.resources.length === 0 ? (
        <p className="mt-3 rounded-md bg-amber-50 p-3 text-sm text-amber-900">
          No images yet — the product will show a placeholder to customers
        </p>
      ) : (
        <ul className="mt-3 grid gap-4 sm:grid-cols-2 md:grid-cols-3">
          {product.resources.map((resource) => (
            <li
              key={resource.id}
              aria-label={label(resource)}
              className="flex flex-col gap-2 rounded-md border border-slate-200 p-3"
            >
              <ImagePreview url={resource.url} alt={label(resource)} />

              <p className="truncate text-sm font-medium text-slate-900">{label(resource)}</p>
              <p className="truncate text-xs text-slate-500">{resource.url}</p>

              <div className="flex flex-wrap items-center gap-2">
                {resource.isPrimary ? (
                  <Badge tone="success">Primary</Badge>
                ) : (
                  <Button
                    variant="secondary"
                    className="px-3 text-xs"
                    aria-label={`Make ${label(resource)} primary`}
                    // One mutation object serves every row, so `isPending` alone would spin all of
                    // them. `variables` is the row actually being promoted.
                    loading={update.isPending && update.variables?.resourceId === resource.id}
                    onClick={() => promote(resource)}
                  >
                    Make primary
                  </Button>
                )}
                <Button
                  variant="ghost"
                  className="px-3 text-xs text-red-700"
                  aria-label={`Remove ${label(resource)}`}
                  onClick={() => setRemoving(resource)}
                >
                  Remove
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}

      <AddImageForm productId={product.id} hasPrimary={hasPrimary} />

      {removing !== null ? (
        <ConfirmDialog
          open
          title={`Remove ${label(removing)}?`}
          confirmLabel="Remove image"
          destructive
          busy={remove.isPending}
          error={failure}
          onCancel={close}
          onConfirm={() => {
            setFailure(null)
            remove.mutate(removing.id, {
              onSuccess: () => {
                showToast('Image removed')
                close()
              },
              onError: (error) => setFailure(describeError(error).body),
            })
          }}
        >
          {`Remove "${label(removing)}"? The image itself is not deleted from wherever it is hosted.`}
        </ConfirmDialog>
      ) : null}
    </section>
  )
}
