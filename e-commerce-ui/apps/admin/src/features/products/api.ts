import {
  request,
  type AdminProduct,
  type AdminProductPage,
  type CreateProductRequest,
  type UpdateProductRequest,
} from '@shopflow/api-client'
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

/** 404 if `categoryTypeId` does not belong to `categoryId`; the form makes that unreachable. */
export function createProduct(body: CreateProductRequest): Promise<AdminProduct> {
  return request<AdminProduct>('/api/admin/products', { method: 'POST', body })
}

/** Answers 200 for an archived product and includes archived variants. */
export function fetchProduct(id: string): Promise<AdminProduct> {
  return request<AdminProduct>(`/api/admin/products/${id}`)
}

export function updateProduct(id: string, body: UpdateProductRequest): Promise<AdminProduct> {
  return request<AdminProduct>(`/api/admin/products/${id}`, { method: 'PATCH', body })
}

/** Archives: sets archivedAt, keeps the row, 204, idempotent. Not a delete. */
export function archiveProduct(id: string): Promise<void> {
  return request<void>(`/api/admin/products/${id}`, { method: 'DELETE' })
}

/** Does not resurrect variants archived separately. */
export function restoreProduct(id: string): Promise<AdminProduct> {
  return request<AdminProduct>(`/api/admin/products/${id}/restore`, { method: 'POST' })
}
