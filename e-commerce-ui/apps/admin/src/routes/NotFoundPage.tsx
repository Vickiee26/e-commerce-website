import type { ReactElement } from 'react'
import { Link } from 'react-router'

export function NotFoundPage(): ReactElement {
  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-4 p-6 text-center">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <p className="text-slate-600">That screen does not exist in the admin portal.</p>
      <Link
        to="/products"
        className="inline-flex min-h-11 items-center rounded-md bg-slate-900 px-4 font-medium text-white"
      >
        Go to products
      </Link>
    </main>
  )
}
