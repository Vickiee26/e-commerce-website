import type {
  AdjustStockRequest,
  AdminVariant,
  CreateVariantRequest,
  StockAdjustment,
  UpdateVariantRequest,
} from '@shopflow/api-client'
import { useMutation, useQueryClient, type UseMutationResult } from '@tanstack/react-query'
import { PRODUCTS_LIST_QUERY_KEY, productQueryKey } from '../products/queries'
import { adjustStock, archiveVariant, createVariant, restoreVariant, updateVariant } from './api'

/**
 * Every variant change alters the parent product's `variantCount` and `totalStock`, which are
 * computed server-side — so each mutation refetches the product rather than patching a cache.
 */
function useVariantInvalidation(productId: string): () => void {
  const queryClient = useQueryClient()

  return () => {
    void queryClient.invalidateQueries({ queryKey: productQueryKey(productId) })
    void queryClient.invalidateQueries({ queryKey: PRODUCTS_LIST_QUERY_KEY })
  }
}

export function useCreateVariant(
  productId: string,
): UseMutationResult<AdminVariant, unknown, CreateVariantRequest> {
  const invalidate = useVariantInvalidation(productId)

  return useMutation({
    mutationFn: (body: CreateVariantRequest) => createVariant(productId, body),
    onSuccess: invalidate,
  })
}

export function useUpdateVariant(
  productId: string,
): UseMutationResult<AdminVariant, unknown, { variantId: string; body: UpdateVariantRequest }> {
  const invalidate = useVariantInvalidation(productId)

  return useMutation({
    mutationFn: ({ variantId, body }) => updateVariant(variantId, body),
    onSuccess: invalidate,
  })
}

export function useArchiveVariant(productId: string): UseMutationResult<void, unknown, string> {
  const invalidate = useVariantInvalidation(productId)

  return useMutation({ mutationFn: archiveVariant, onSuccess: invalidate })
}

export function useRestoreVariant(
  productId: string,
): UseMutationResult<AdminVariant, unknown, string> {
  const invalidate = useVariantInvalidation(productId)

  return useMutation({ mutationFn: restoreVariant, onSuccess: invalidate })
}

export function useAdjustStock(
  productId: string,
): UseMutationResult<StockAdjustment, unknown, { variantId: string; body: AdjustStockRequest }> {
  const invalidate = useVariantInvalidation(productId)

  return useMutation({
    mutationFn: ({ variantId, body }) => adjustStock(variantId, body),
    onSuccess: invalidate,
  })
}
