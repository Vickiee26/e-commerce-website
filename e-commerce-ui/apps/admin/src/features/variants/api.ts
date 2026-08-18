import {
  request,
  type AdjustStockRequest,
  type AdminVariant,
  type CreateVariantRequest,
  type StockAdjustment,
  type UpdateVariantRequest,
} from '@shopflow/api-client'

/** `stockQuantity` here is an opening balance; every later change is a delta. */
export function createVariant(
  productId: string,
  body: CreateVariantRequest,
): Promise<AdminVariant> {
  return request<AdminVariant>(`/api/admin/products/${productId}/variants`, { method: 'POST', body })
}

/** Colour and size only — `UpdateVariantRequest` has no stock field by design. */
export function updateVariant(
  variantId: string,
  body: UpdateVariantRequest,
): Promise<AdminVariant> {
  return request<AdminVariant>(`/api/admin/variants/${variantId}`, { method: 'PATCH', body })
}

export function archiveVariant(variantId: string): Promise<void> {
  return request<void>(`/api/admin/variants/${variantId}`, { method: 'DELETE' })
}

export function restoreVariant(variantId: string): Promise<AdminVariant> {
  return request<AdminVariant>(`/api/admin/variants/${variantId}/restore`, { method: 'POST' })
}

/**
 * A signed, non-zero delta with a reason. The row is locked server-side, so this is the only safe
 * way to move stock; 409 `Insufficient stock` when the result would be negative.
 */
export function adjustStock(
  variantId: string,
  body: AdjustStockRequest,
): Promise<StockAdjustment> {
  return request<StockAdjustment>(`/api/admin/variants/${variantId}/stock`, { method: 'POST', body })
}
