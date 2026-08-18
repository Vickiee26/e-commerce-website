import { useState, type ReactElement } from 'react'
import { NavLink, Outlet } from 'react-router'
import { Button } from '../components/Button'
import { useAuthExpiredRedirect, useLogout, useSession } from '../features/auth/session'

const NAV_ITEMS = [
  { to: '/products', label: 'Products' },
  { to: '/categories', label: 'Categories' },
]

function navClass({ isActive }: { isActive: boolean }): string {
  return `flex min-h-11 items-center rounded-md px-3 text-sm font-medium ${
    isActive ? 'bg-slate-900 text-white' : 'text-slate-700 hover:bg-slate-200'
  }`
}

export function AdminLayout(): ReactElement {
  const [drawerOpen, setDrawerOpen] = useState(false)
  const { data: profile } = useSession()
  const signOut = useLogout()
  useAuthExpiredRedirect()

  const nav = (
    <nav className="flex flex-col gap-1" onClick={() => setDrawerOpen(false)}>
      {NAV_ITEMS.map((item) => (
        <NavLink key={item.to} to={item.to} className={navClass}>
          {item.label}
        </NavLink>
      ))}
    </nav>
  )

  return (
    <div className="min-h-dvh md:flex">
      {/* Mobile: top bar plus a slide-over drawer. */}
      <header className="sticky top-0 z-30 flex items-center justify-between gap-2 border-b border-slate-200 bg-white px-3 py-2 md:hidden">
        <Button variant="ghost" aria-label="Open menu" onClick={() => setDrawerOpen(true)} className="px-3">
          <span aria-hidden="true">☰</span>
        </Button>
        <span className="font-semibold">ShopFlow Admin</span>
        <Button variant="ghost" onClick={() => signOut.mutate()} loading={signOut.isPending} className="px-3">
          Sign out
        </Button>
      </header>

      {drawerOpen ? (
        <div className="fixed inset-0 z-40 bg-slate-900/50 md:hidden" onClick={() => setDrawerOpen(false)}>
          <div
            className="h-full w-72 bg-slate-100 p-4"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="mb-4 flex items-center justify-between">
              <span className="font-semibold">Catalogue</span>
              <Button variant="ghost" aria-label="Close menu" onClick={() => setDrawerOpen(false)} className="px-3">
                <span aria-hidden="true">✕</span>
              </Button>
            </div>
            {nav}
          </div>
        </div>
      ) : null}

      {/* Desktop: persistent sidebar. */}
      <aside className="hidden w-60 shrink-0 flex-col justify-between border-r border-slate-200 bg-slate-100 p-4 md:flex">
        <div className="flex flex-col gap-4">
          <span className="font-semibold">ShopFlow Admin</span>
          {nav}
        </div>
        <div className="flex flex-col gap-2 text-xs text-slate-600">
          <span className="truncate">{profile?.email}</span>
          <Button variant="secondary" onClick={() => signOut.mutate()} loading={signOut.isPending}>
            Sign out
          </Button>
        </div>
      </aside>

      <main className="min-w-0 flex-1 p-4 md:p-6">
        <Outlet />
      </main>
    </div>
  )
}
