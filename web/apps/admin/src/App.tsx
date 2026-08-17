import { QueryClientProvider } from '@tanstack/react-query'
import type { ReactElement } from 'react'
import { RouterProvider } from 'react-router'
import { createQueryClient } from './lib/queryClient'
import { router } from './routes/router'

const queryClient = createQueryClient()

export function App(): ReactElement {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  )
}
