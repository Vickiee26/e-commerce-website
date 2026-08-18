import { isApiError } from '@shopflow/api-client'
import { QueryClient } from '@tanstack/react-query'

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        // Retry only what a retry can fix. A 400 or 404 is settled; status 0 is a NetworkError.
        retry: (failureCount, error) =>
          failureCount < 2 && isApiError(error) && (error.status === 0 || error.status >= 500),
      },
      mutations: { retry: false },
    },
  })
}
