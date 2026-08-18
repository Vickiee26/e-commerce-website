import {
  request,
  type AdminProduct,
  type AdminProductPage,
  type CreateProductRequest,
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
