import type { Category } from '@shopflow/api-client'
import { useState, type ReactElement } from 'react'
import { Badge } from '../../components/Badge'
import { Button } from '../../components/Button'
import { EmptyState, ErrorPanel, Skeleton } from '../../components/QueryStates'
import { CategoryFormDialog } from './CategoryFormDialog'
import { useCategories } from './queries'

export function CategoriesPage(): ReactElement {
  const categories = useCategories()
  const [createOpen, setCreateOpen] = useState(false)

  return (
    <section className="flex flex-col gap-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold text-slate-900">Categories</h1>
        <Button onClick={() => setCreateOpen(true)}>New category</Button>
      </header>

      {categories.isPending ? <Skeleton rows={3} label="Loading categories" /> : null}

      {categories.isError ? (
        <ErrorPanel error={categories.error} onRetry={() => void categories.refetch()} />
      ) : null}

      {categories.isSuccess && categories.data.length === 0 ? (
        <EmptyState
          title="No categories yet"
          description="Every product needs a category and a type, so the catalogue starts here."
          action={<Button onClick={() => setCreateOpen(true)}>Create your first category</Button>}
        />
      ) : null}

      {categories.isSuccess && categories.data.length > 0 ? (
        <ul className="flex flex-col gap-3">
          {categories.data.map((category) => (
            <CategoryCard key={category.id} category={category} />
          ))}
        </ul>
      ) : null}

      <CategoryFormDialog open={createOpen} onClose={() => setCreateOpen(false)} />
    </section>
  )
}

/** Task 8 adds the edit, delete and add-type actions to this card. */
export function CategoryCard({ category }: { category: Category }): ReactElement {
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
        <ul className="mt-3 flex flex-wrap gap-2">
          {category.types.map((type) => (
            <li key={type.id}>
              <Badge>{`${type.name} (${type.code})`}</Badge>
            </li>
          ))}
        </ul>
      ) : null}
    </li>
  )
}
