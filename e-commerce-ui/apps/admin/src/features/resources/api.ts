import {
  request,
  type CreateResourceRequest,
  type ProductResource,
  type UpdateResourceRequest,
} from '@shopflow/api-client'

/** There is no upload endpoint: a resource is a URL the backend stores as a string. */
export function createResource(
  productId: string,
  body: CreateResourceRequest,
): Promise<ProductResource> {
  return request<ProductResource>(`/api/admin/products/${productId}/resources`, {
    method: 'POST',
    body,
  })
}

/** Setting `isPrimary: true` demotes the product's other resources server-side. */
export function updateResource(
  resourceId: string,
  body: UpdateResourceRequest,
): Promise<ProductResource> {
  return request<ProductResource>(`/api/admin/resources/${resourceId}`, { method: 'PATCH', body })
}

export function deleteResource(resourceId: string): Promise<void> {
  return request<void>(`/api/admin/resources/${resourceId}`, { method: 'DELETE' })
}
