import { request, type AdminProductPage } from '@shopflow/api-client'
import type { ProductFilters } from './filters'

/** `request` drops empty values, so a blank `q` or `categoryId` never reaches the backend. */
export function fetchProducts(filters: ProductFilters): Promise<AdminProductPage> {
  return request<AdminProductPage>('/api/admin/products', {
    query: {
      q: filters.q,
      categoryId: filters.categoryId,
      archived: filters.archived,
      sort: filters.sort,
      direction: filters.direction,
      page: filters.page,
      size: filters.size,
    },
  })
}
