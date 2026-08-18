import { useEffect, useState, type ReactElement } from 'react'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { Field, TextInput, inputClass } from '../../components/Field'
import { Pagination } from '../../components/Pagination'
import { EmptyState, ErrorPanel, Skeleton } from '../../components/QueryStates'
import { useCategories } from '../categories/queries'
import { ProductList } from './ProductList'
import {
  DEFAULT_FILTERS,
  useProductFilters,
  type ArchivedFilter,
  type ProductSort,
  type SortDirection,
} from './filters'
import { useProducts } from './queries'

const SEARCH_DEBOUNCE_MS = 300

export function ProductsPage(): ReactElement {
  const { filters, setFilters } = useProductFilters()
  const products = useProducts(filters)
  const categories = useCategories()

  // The box is typed into far faster than the backend should be asked, so the input keeps its own
  // value and only the settled value reaches the URL.
  const [searchDraft, setSearchDraft] = useState(filters.q)

  // A Back navigation or Clear filters changes `filters.q` from outside; follow it.
  useEffect(() => setSearchDraft(filters.q), [filters.q])

  useEffect(() => {
    if (searchDraft === filters.q) return
    const timer = setTimeout(() => setFilters({ q: searchDraft }), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [searchDraft, filters.q, setFilters])

  const filtered =
    filters.q !== '' || filters.categoryId !== '' || filters.archived !== DEFAULT_FILTERS.archived

  return (
    <section className="flex flex-col gap-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold text-slate-900">Products</h1>
        {/* A Link, not a Button: Button renders a <button> and cannot navigate. */}
        <Link
          to="/products/new"
          className="inline-flex min-h-11 items-center rounded-md bg-slate-900 px-4 text-sm font-medium text-white"
        >
          New product
        </Link>
      </header>

      {/* Stacks on a phone, one row from md up. */}
      <div className="grid gap-3 md:grid-cols-4">
        <Field label="Search products" htmlFor="product-search">
          <TextInput
            id="product-search"
            type="search"
            placeholder="Name or description"
            value={searchDraft}
            onChange={(event) => setSearchDraft(event.target.value)}
          />
        </Field>

        <Field label="Category" htmlFor="product-category">
          <select
            id="product-category"
            className={inputClass}
            value={filters.categoryId}
            onChange={(event) => setFilters({ categoryId: event.target.value })}
          >
            <option value="">All categories</option>
            {(categories.data ?? []).map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Status" htmlFor="product-archived">
          <select
            id="product-archived"
            className={inputClass}
            value={filters.archived}
            onChange={(event) => setFilters({ archived: event.target.value as ArchivedFilter })}
          >
            <option value="exclude">Live</option>
            <option value="only">Archived</option>
            <option value="all">All</option>
          </select>
        </Field>

        <div className="grid grid-cols-2 gap-3">
          <Field label="Sort by" htmlFor="product-sort">
            <select
              id="product-sort"
              className={inputClass}
              value={filters.sort}
              onChange={(event) => setFilters({ sort: event.target.value as ProductSort })}
            >
              <option value="name">Name</option>
              <option value="price">Price</option>
              <option value="createdAt">Created</option>
            </select>
          </Field>

          <Field label="Direction" htmlFor="product-direction">
            <select
              id="product-direction"
              className={inputClass}
              value={filters.direction}
              onChange={(event) => setFilters({ direction: event.target.value as SortDirection })}
            >
              <option value="asc">Ascending</option>
              <option value="desc">Descending</option>
            </select>
          </Field>
        </div>
      </div>

      {products.isPending ? <Skeleton rows={5} label="Loading products" /> : null}

      {products.isError ? (
        <ErrorPanel error={products.error} onRetry={() => void products.refetch()} />
      ) : null}

      {products.data !== undefined && products.data.content.length === 0 ? (
        filtered ? (
          <EmptyState
            title="No matching products"
            description="Nothing matches these filters. Widen the search or clear them."
            action={<Button variant="secondary" onClick={() => setFilters(DEFAULT_FILTERS)}>Clear filters</Button>}
          />
        ) : (
          <EmptyState
            title="No products yet"
            description="Add your first product to start filling the catalogue."
            action={
              <Link
                to="/products/new"
                className="inline-flex min-h-11 items-center rounded-md bg-slate-900 px-4 text-sm font-medium text-white"
              >
                New product
              </Link>
            }
          />
        )
      ) : null}

      {products.data !== undefined && products.data.content.length > 0 ? (
        <>
          <div aria-busy={products.isFetching}>
            <ProductList products={products.data.content} />
          </div>
          <Pagination
            page={products.data.page}
            totalPages={products.data.totalPages}
            totalElements={products.data.totalElements}
            onPageChange={(page) => setFilters({ page })}
          />
        </>
      ) : null}
    </section>
  )
}
