import { createBrowserRouter, Navigate } from 'react-router'
import { LoginPage } from '../features/auth/LoginPage'
import { RequireAdmin } from '../features/auth/RequireAdmin'
import { CategoriesPage } from '../features/categories/CategoriesPage'
import { ProductsPage } from '../features/products/ProductsPage'
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
      { path: '/products', element: <ProductsPage /> },
      { path: '/categories', element: <CategoriesPage /> },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
])
