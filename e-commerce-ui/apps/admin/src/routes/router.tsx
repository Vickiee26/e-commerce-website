import { createBrowserRouter, Navigate } from 'react-router'
import { LoginPage } from '../features/auth/LoginPage'
import { RequireAdmin } from '../features/auth/RequireAdmin'
import { CategoriesPage } from '../features/categories/CategoriesPage'
import { AdminLayout } from './AdminLayout'
import { NotFoundPage } from './NotFoundPage'

/** Later tasks add /products, /products/new and /products/:id as children. */
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: (
      <RequireAdmin>
        <AdminLayout />
      </RequireAdmin>
    ),
    children: [
      { path: '/', element: <Navigate to="/products" replace /> },
      { path: '/categories', element: <CategoriesPage /> },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
])
