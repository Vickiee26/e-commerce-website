import type {
  CreateResourceRequest,
  ProductResource,
  UpdateResourceRequest,
} from '@shopflow/api-client'
import { useMutation, useQueryClient, type UseMutationResult } from '@tanstack/react-query'
import { PRODUCTS_LIST_QUERY_KEY, productQueryKey } from '../products/queries'
import { createResource, deleteResource, updateResource } from './api'

/**
 * Promoting one resource demotes the rest, so the whole product is refetched rather than one row
 * patched — a local guess would leave two images claiming to be primary.
 */
function useResourceInvalidation(productId: string): () => void {
  const queryClient = useQueryClient()

  return () => {
    void queryClient.invalidateQueries({ queryKey: productQueryKey(productId) })
    void queryClient.invalidateQueries({ queryKey: PRODUCTS_LIST_QUERY_KEY })
  }
}

export function useCreateResource(
  productId: string,
): UseMutationResult<ProductResource, unknown, CreateResourceRequest> {
  const invalidate = useResourceInvalidation(productId)

  return useMutation({
    mutationFn: (body: CreateResourceRequest) => createResource(productId, body),
    onSuccess: invalidate,
  })
}

export function useUpdateResource(
  productId: string,
): UseMutationResult<
  ProductResource,
  unknown,
  { resourceId: string; body: UpdateResourceRequest }
> {
  const invalidate = useResourceInvalidation(productId)

  return useMutation({
    mutationFn: ({ resourceId, body }) => updateResource(resourceId, body),
    onSuccess: invalidate,
  })
}

export function useDeleteResource(productId: string): UseMutationResult<void, unknown, string> {
  const invalidate = useResourceInvalidation(productId)

  return useMutation({ mutationFn: deleteResource, onSuccess: invalidate })
}
