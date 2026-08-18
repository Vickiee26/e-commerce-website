import { clearTokens, getAccessToken, getRefreshToken } from '@shopflow/api-client'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { LoginPage } from './LoginPage'

const TOKEN_PAIR = { accessToken: 'access-1', refreshToken: 'refresh-1', tokenType: 'Bearer', expiresIn: 900 }

const ADMIN = {
  id: '11111111-1111-1111-1111-111111111111',
  email: 'admin@shopflow.local',
  fullName: 'Administrator',
  emailVerified: true,
  roles: ['ADMIN'],
  createdAt: '2026-08-17T09:00:00Z',
}

const CUSTOMER = { ...ADMIN, id: '22222222-2222-2222-2222-222222222222', email: 'shopper@example.com', roles: ['CUSTOMER'] }

function renderLogin() {
  return renderWithProviders(<LoginPage />, {
    route: '/login',
    path: '/login',
    extraRoutes: [{ path: '/products', element: <h1>Products</h1> }],
  })
}

async function submitCredentials(email: string, password: string): Promise<void> {
  await userEvent.type(screen.getByLabelText(/email/i), email)
  await userEvent.type(screen.getByLabelText(/password/i), password)
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
}

beforeEach(() => {
  clearTokens()
  sessionStorage.clear()
})

describe('LoginPage', () => {
  it('signs an admin in and lands on the products list', async () => {
    server.use(
      http.post(`${API}/auth/login`, () => HttpResponse.json(TOKEN_PAIR)),
      http.get(`${API}/api/me`, () => HttpResponse.json(ADMIN)),
    )
    renderLogin()

    await submitCredentials('admin@shopflow.local', 'correct-horse-battery')

    expect(await screen.findByRole('heading', { name: 'Products' })).toBeInTheDocument()
    expect(getAccessToken()).toBe('access-1')
    expect(getRefreshToken()).toBe('refresh-1')
  })

  it('rejects a valid non-admin login with an explanation and keeps no tokens', async () => {
    server.use(
      http.post(`${API}/auth/login`, () => HttpResponse.json(TOKEN_PAIR)),
      http.get(`${API}/api/me`, () => HttpResponse.json(CUSTOMER)),
    )
    renderLogin()

    await submitCredentials('shopper@example.com', 'correct-horse-battery')

    expect(await screen.findByRole('alert')).toHaveTextContent('does not have administrator access')
    expect(screen.queryByRole('heading', { name: 'Products' })).not.toBeInTheDocument()
    await waitFor(() => expect(getAccessToken()).toBeNull())
    expect(getRefreshToken()).toBeNull()
  })

  it('shows the server wording for bad credentials', async () => {
    server.use(
      http.post(`${API}/auth/login`, () =>
        problemResponse(401, { title: 'Unauthorized', detail: 'Invalid email or password' }),
      ),
    )
    renderLogin()

    await submitCredentials('admin@shopflow.local', 'wrong-password-here')

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
    expect(getRefreshToken()).toBeNull()
  })

  it('validates before reaching the network', async () => {
    renderLogin()

    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument()
    expect(screen.getByText('Password is required')).toBeInTheDocument()
  })
})
