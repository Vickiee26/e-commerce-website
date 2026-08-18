import { useState, type ReactElement } from 'react'
import { Button } from '../../components/Button'
import { EmptyState, ErrorPanel, Skeleton } from '../../components/QueryStates'
import { CategoryCard } from './CategoryCard'
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
