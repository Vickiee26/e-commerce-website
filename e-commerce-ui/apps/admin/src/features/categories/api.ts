import {
  request,
  type Category,
  type CategoryType,
  type CreateCategoryRequest,
  type CreateCategoryTypeRequest,
  type UpdateCategoryRequest,
  type UpdateCategoryTypeRequest,
} from '@shopflow/api-client'

/** The public endpoint: it already nests types one level, which is all this screen needs. */
export function fetchCategories(): Promise<Category[]> {
  return request<Category[]>('/api/categories')
}

export function createCategory(body: CreateCategoryRequest): Promise<Category> {
  return request<Category>('/api/admin/categories', { method: 'POST', body })
}

export function updateCategory(id: string, body: UpdateCategoryRequest): Promise<Category> {
  return request<Category>(`/api/admin/categories/${id}`, { method: 'PATCH', body })
}

/** 204 on success; 409 while any product still names this category. */
export function deleteCategory(id: string): Promise<void> {
  return request<void>(`/api/admin/categories/${id}`, { method: 'DELETE' })
}

/** Created under the parent category… */
export function createCategoryType(
  categoryId: string,
  body: CreateCategoryTypeRequest,
): Promise<CategoryType> {
  return request<CategoryType>(`/api/admin/categories/${categoryId}/types`, { method: 'POST', body })
}

/** …but updated and deleted by its own id. The routing really is asymmetric. */
export function updateCategoryType(
  typeId: string,
  body: UpdateCategoryTypeRequest,
): Promise<CategoryType> {
  return request<CategoryType>(`/api/admin/category-types/${typeId}`, { method: 'PATCH', body })
}

export function deleteCategoryType(typeId: string): Promise<void> {
  return request<void>(`/api/admin/category-types/${typeId}`, { method: 'DELETE' })
}
