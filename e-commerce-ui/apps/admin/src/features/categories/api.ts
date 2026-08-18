import { request, type Category, type CreateCategoryRequest } from '@shopflow/api-client'

/** The public endpoint: it already nests types one level, which is all this screen needs. */
export function fetchCategories(): Promise<Category[]> {
  return request<Category[]>('/api/categories')
}

export function createCategory(body: CreateCategoryRequest): Promise<Category> {
  return request<Category>('/api/admin/categories', { method: 'POST', body })
}
