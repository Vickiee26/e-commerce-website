import { createBrowserRouter, Navigate } from 'react-router'
import { NotFoundPage } from './NotFoundPage'

/** Later tasks add /login, /products, /products/new, /products/:id and /categories. */
export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/products" replace /> },
  { path: '*', element: <NotFoundPage /> },
])
