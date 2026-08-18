import type { AdminProduct, AdminProductPage, CreateProductRequest } from '@shopflow/api-client'
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { createProduct, fetchProducts } from './api'
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

export function useCreateProduct(): UseMutationResult<AdminProduct, unknown, CreateProductRequest> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: createProduct,
    // Every filtered list is now stale; the key prefix invalidates them all.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [PRODUCTS_QUERY_KEY] }),
  })
}
