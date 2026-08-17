import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, type RenderResult } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { ToastProvider } from '../components/Toast'

export type RenderOptions = {
  /** The entry URL, including any search string the screen reads. */
  route?: string
  /** The route pattern the component sits at, so `useParams` resolves. */
  path?: string
  /** Extra routes, so a test can assert where a redirect landed. */
  extraRoutes?: { path: string; element: ReactElement }[]
}

export function renderWithProviders(
  ui: ReactElement,
  options: RenderOptions = {},
): RenderResult & { queryClient: QueryClient } {
  const { route = '/', path = '*', extraRoutes = [] } = options
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
      mutations: { retry: false },
    },
  })

  const result = render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route path={path} element={ui} />
            {extraRoutes.map((extra) => (
              <Route key={extra.path} path={extra.path} element={extra.element} />
            ))}
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )

  return { ...result, queryClient }
}
