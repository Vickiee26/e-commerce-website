import { createBrowserRouter, Navigate } from 'react-router'
import { LoginPage } from '../features/auth/LoginPage'
import { RequireAdmin } from '../features/auth/RequireAdmin'
import { AdminLayout } from './AdminLayout'
import { NotFoundPage } from './NotFoundPage'

/** Later tasks add /products, /products/new, /products/:id and /categories as children. */
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: (
      <RequireAdmin>
        <AdminLayout />
      </RequireAdmin>
    ),
    children: [{ path: '/', element: <Navigate to="/products" replace /> }],
  },
  { path: '*', element: <NotFoundPage /> },
])
