import type { AdminProductPage } from '@shopflow/api-client'
import { keepPreviousData, useQuery, type UseQueryResult } from '@tanstack/react-query'
import { fetchProducts } from './api'
import type { ProductFilters } from './filters'

export const PRODUCTS_QUERY_KEY = 'products'

export function useProducts(filters: ProductFilters): UseQueryResult<AdminProductPage> {
  return useQuery({
    queryKey: [PRODUCTS_QUERY_KEY, filters],
    queryFn: () => fetchProducts(filters),
    // Keeps the current rows on screen while the next page loads instead of flashing skeletons.
    placeholderData: keepPreviousData,
  })
}
