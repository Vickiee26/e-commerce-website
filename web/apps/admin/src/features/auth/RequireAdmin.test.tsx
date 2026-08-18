import { clearTokens, setTokens } from '@shopflow/api-client'
import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { RequireAdmin } from './RequireAdmin'

const ADMIN = {
  id: '11111111-1111-1111-1111-111111111111',
  email: 'admin@shopflow.local',
  fullName: 'Administrator',
  emailVerified: true,
  roles: ['ADMIN'],
  createdAt: '2026-08-17T09:00:00Z',
}

function renderGate() {
  return renderWithProviders(
    <RequireAdmin>
      <h1>Catalogue</h1>
    </RequireAdmin>,
    {
      route: '/products',
      path: '/products',
      extraRoutes: [{ path: '/login', element: <h1>Sign in</h1> }],
    },
  )
}

beforeEach(() => {
  clearTokens()
  sessionStorage.clear()
})

describe('RequireAdmin', () => {
  it('sends a visitor with no session to the login screen without calling the API', async () => {
    renderGate()

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('shows a loading state and then the protected content for an admin', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    server.use(http.get(`${API}/api/me`, () => HttpResponse.json(ADMIN)))
    renderGate()

    // Not a bare getByRole('status'): ToastProvider (in the test harness, and in App.tsx in
    // production) always renders its own role="status" live region, so that query is ambiguous.
    // A named role query does not work either — `status` takes no name from content, so the
    // sr-only label is live-region content rather than an accessible name. Assert on the label
    // and on the live region wrapping it, which is what the gate actually promises.
    expect(screen.getByText('Checking your access').closest('[role="status"]')).not.toBeNull()
    expect(await screen.findByRole('heading', { name: 'Catalogue' })).toBeInTheDocument()
  })

  it('explains the refusal for an authenticated non-admin instead of rendering the shell', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    server.use(http.get(`${API}/api/me`, () => HttpResponse.json({ ...ADMIN, roles: ['CUSTOMER'] })))
    renderGate()

    expect(await screen.findByText(/does not have administrator access/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Catalogue' })).not.toBeInTheDocument()
  })

  it('sends an expired session back to login', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    server.use(
      http.get(`${API}/api/me`, () => problemResponse(401, { title: 'Unauthorized' })),
      http.post(`${API}/auth/refresh`, () => problemResponse(401, { title: 'Invalid refresh token' })),
    )
    renderGate()

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('offers Retry when /api/me fails for a reason that is not about auth', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    server.use(http.get(`${API}/api/me`, () => problemResponse(500, { detail: 'Internal error' })))
    renderGate()

    expect(await screen.findByRole('heading', { name: 'The server failed' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
