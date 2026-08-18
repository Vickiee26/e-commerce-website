import type { Category, CreateCategoryRequest } from '@shopflow/api-client'
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { createCategory, fetchCategories } from './api'

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
