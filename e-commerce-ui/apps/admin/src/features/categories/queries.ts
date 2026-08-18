import type {
  Category,
  CategoryType,
  CreateCategoryRequest,
  CreateCategoryTypeRequest,
  UpdateCategoryRequest,
  UpdateCategoryTypeRequest,
} from '@shopflow/api-client'
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import {
  createCategory,
  createCategoryType,
  deleteCategory,
  deleteCategoryType,
  fetchCategories,
  updateCategory,
  updateCategoryType,
} from './api'

export const CATEGORIES_QUERY_KEY = ['categories']

export function useCategories(): UseQueryResult<Category[]> {
  return useQuery({
    queryKey: CATEGORIES_QUERY_KEY,
    queryFn: fetchCategories,
    // Changes rarely and feeds every product form's dropdowns.
    staleTime: 5 * 60_000,
  })
}

/**
 * Invalidate rather than patch: for a catalogue tool the server's truth beats a guess, and the
 * refetch is one small request.
 */
export function useCreateCategory(): UseMutationResult<Category, unknown, CreateCategoryRequest> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: createCategory,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_QUERY_KEY }),
  })
}

export function useUpdateCategory(): UseMutationResult<
  Category,
  unknown,
  { id: string; body: UpdateCategoryRequest }
> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ id, body }) => updateCategory(id, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_QUERY_KEY }),
  })
}

export function useDeleteCategory(): UseMutationResult<void, unknown, string> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: deleteCategory,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_QUERY_KEY }),
  })
}

export function useCreateCategoryType(): UseMutationResult<
  CategoryType,
  unknown,
  { categoryId: string; body: CreateCategoryTypeRequest }
> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ categoryId, body }) => createCategoryType(categoryId, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_QUERY_KEY }),
  })
}

export function useUpdateCategoryType(): UseMutationResult<
  CategoryType,
  unknown,
  { typeId: string; body: UpdateCategoryTypeRequest }
> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ typeId, body }) => updateCategoryType(typeId, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_QUERY_KEY }),
  })
}

export function useDeleteCategoryType(): UseMutationResult<void, unknown, string> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: deleteCategoryType,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_QUERY_KEY }),
  })
}
