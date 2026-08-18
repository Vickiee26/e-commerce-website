import type {
  AdminProduct,
  AdminProductPage,
  CreateProductRequest,
  UpdateProductRequest,
} from '@shopflow/api-client'
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import {
  archiveProduct,
  createProduct,
  fetchProduct,
  fetchProducts,
  restoreProduct,
  updateProduct,
} from './api'
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

export function productQueryKey(id: string): unknown[] {
  return [PRODUCTS_QUERY_KEY, 'detail', id]
}

export function useProduct(id: string): UseQueryResult<AdminProduct> {
  return useQuery({
    queryKey: productQueryKey(id),
    queryFn: () => fetchProduct(id),
  })
}

/**
 * Each of these writes the server's response straight into the detail cache and invalidates the
 * lists. Nothing is patched optimistically: `variantCount` and `totalStock` are computed
 * server-side, so a guess here would be wrong as often as right.
 */
export function useUpdateProduct(
  id: string,
): UseMutationResult<AdminProduct, unknown, UpdateProductRequest> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (body: UpdateProductRequest) => updateProduct(id, body),
    onSuccess: (product) => {
      queryClient.setQueryData(productQueryKey(id), product)
      void queryClient.invalidateQueries({ queryKey: [PRODUCTS_QUERY_KEY] })
    },
  })
}

export function useArchiveProduct(id: string): UseMutationResult<void, unknown, void> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: () => archiveProduct(id),
    // 204 carries no body, so refetch rather than invent an archivedAt.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [PRODUCTS_QUERY_KEY] }),
  })
}

export function useRestoreProduct(id: string): UseMutationResult<AdminProduct, unknown, void> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: () => restoreProduct(id),
    onSuccess: (product) => {
      queryClient.setQueryData(productQueryKey(id), product)
      void queryClient.invalidateQueries({ queryKey: [PRODUCTS_QUERY_KEY] })
    },
  })
}
