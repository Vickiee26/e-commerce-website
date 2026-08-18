import { createBrowserRouter, Navigate } from 'react-router'
import { LoginPage } from '../features/auth/LoginPage'
import { RequireAdmin } from '../features/auth/RequireAdmin'
import { CategoriesPage } from '../features/categories/CategoriesPage'
import { NewProductPage } from '../features/products/NewProductPage'
import { ProductDetailPage } from '../features/products/ProductDetailPage'
import { ProductsPage } from '../features/products/ProductsPage'
import { AdminLayout } from './AdminLayout'
import { NotFoundPage } from './NotFoundPage'

/**
 * Everything behind RequireAdmin is a child of the pathless layout route, and '*' stays last.
 * `/products/new` is matched ahead of `/products/:id` by route ranking, not by this ordering.
 */
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
      { path: '/products/new', element: <NewProductPage /> },
      { path: '/products/:id', element: <ProductDetailPage /> },
      { path: '/categories', element: <CategoriesPage /> },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
])
