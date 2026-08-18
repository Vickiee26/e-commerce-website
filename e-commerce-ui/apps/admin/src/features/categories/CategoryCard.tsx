import type { Category, CategoryType } from '@shopflow/api-client'
import { useState, type ReactElement } from 'react'
import { Badge } from '../../components/Badge'
import { Button } from '../../components/Button'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { useToast } from '../../components/Toast'
import { describeError } from '../../lib/errors'
import { CategoryFormDialog } from './CategoryFormDialog'
import { CategoryTypeFormDialog } from './CategoryTypeFormDialog'
import { useDeleteCategory, useDeleteCategoryType } from './queries'

type OpenDialog =
  | { kind: 'none' }
  | { kind: 'edit-category' }
  | { kind: 'delete-category' }
  | { kind: 'add-type' }
  | { kind: 'edit-type'; type: CategoryType }
  | { kind: 'delete-type'; type: CategoryType }

const CLOSED: OpenDialog = { kind: 'none' }

export function CategoryCard({ category }: { category: Category }): ReactElement {
  const [dialog, setDialog] = useState<OpenDialog>(CLOSED)
  const [failure, setFailure] = useState<string | null>(null)
  const deleteCategory = useDeleteCategory()
  const deleteType = useDeleteCategoryType()
  const showToast = useToast()

  const close = (): void => {
    setDialog(CLOSED)
    setFailure(null)
  }

  const onDeleteCategory = (): void => {
    setFailure(null)
    deleteCategory.mutate(category.id, {
      onSuccess: () => {
        showToast(`Category "${category.name}" deleted`)
        close()
      },
      // Almost always the 409 for a category that still has products. Keeping the dialog open with
      // the server's own sentence is more use than a toast that vanishes.
      onError: (error) => setFailure(describeError(error).body),
    })
  }

  const onDeleteType = (type: CategoryType): void => {
    setFailure(null)
    deleteType.mutate(type.id, {
      onSuccess: () => {
        showToast(`Type "${type.name}" removed`)
        close()
      },
      onError: (error) => setFailure(describeError(error).body),
    })
  }

  const typeNames = category.types.map((type) => type.name).join(', ')

  return (
    <li aria-label={category.name} className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="font-semibold text-slate-900">{category.name}</h2>
          <p className="text-xs text-slate-500">{category.code}</p>
        </div>
        {category.types.length === 0 ? (
          <Badge tone="warning">No types — cannot hold products</Badge>
        ) : null}
      </div>

      {category.description !== undefined ? (
        <p className="mt-2 text-sm text-slate-600">{category.description}</p>
      ) : null}

      {category.types.length > 0 ? (
        <ul className="mt-3 flex flex-col gap-2">
          {category.types.map((type) => (
            <li key={type.id} className="flex flex-wrap items-center gap-2">
              <Badge>{`${type.name} (${type.code})`}</Badge>
              <Button
                variant="ghost"
                className="px-2 text-xs"
                aria-label={`Edit type ${type.name}`}
                onClick={() => setDialog({ kind: 'edit-type', type })}
              >
                Edit
              </Button>
              <Button
                variant="ghost"
                className="px-2 text-xs text-red-700"
                aria-label={`Remove type ${type.name}`}
                onClick={() => setDialog({ kind: 'delete-type', type })}
              >
                Remove
              </Button>
            </li>
          ))}
        </ul>
      ) : null}

      {/* Wraps to a column on a narrow phone; each button keeps its 44px target. */}
      <div className="mt-4 flex flex-wrap gap-2">
        <Button
          variant="secondary"
          aria-label={`Add type to ${category.name}`}
          onClick={() => setDialog({ kind: 'add-type' })}
        >
          Add type
        </Button>
        <Button
          variant="secondary"
          aria-label={`Edit ${category.name}`}
          onClick={() => setDialog({ kind: 'edit-category' })}
        >
          Edit
        </Button>
        <Button
          variant="danger"
          aria-label={`Delete ${category.name}`}
          onClick={() => setDialog({ kind: 'delete-category' })}
        >
          Delete
        </Button>
      </div>

      <CategoryFormDialog
        open={dialog.kind === 'edit-category'}
        category={category}
        onClose={close}
      />

      <CategoryTypeFormDialog
        open={dialog.kind === 'add-type'}
        categoryId={category.id}
        categoryName={category.name}
        onClose={close}
      />

      {dialog.kind === 'edit-type' ? (
        <CategoryTypeFormDialog
          open
          categoryId={category.id}
          categoryName={category.name}
          type={dialog.type}
          onClose={close}
        />
      ) : null}

      <ConfirmDialog
        open={dialog.kind === 'delete-category'}
        title={`Delete ${category.name}?`}
        confirmLabel="Delete category"
        destructive
        busy={deleteCategory.isPending}
        error={failure}
        onConfirm={onDeleteCategory}
        onCancel={close}
      >
        {category.types.length === 0
          ? `Deleting "${category.name}" is permanent. This cannot be undone.`
          : `Deleting "${category.name}" also deletes its ${category.types.length} ` +
            `type${category.types.length === 1 ? '' : 's'} (${typeNames}). This cannot be undone.`}
      </ConfirmDialog>

      {dialog.kind === 'delete-type' ? (
        <ConfirmDialog
          open
          title={`Remove ${dialog.type.name}?`}
          confirmLabel="Remove type"
          destructive
          busy={deleteType.isPending}
          error={failure}
          onConfirm={() => onDeleteType(dialog.type)}
          onCancel={close}
        >
          {`Remove the type "${dialog.type.name}" from "${category.name}"? This cannot be undone.`}
        </ConfirmDialog>
      ) : null}
    </li>
  )
}
