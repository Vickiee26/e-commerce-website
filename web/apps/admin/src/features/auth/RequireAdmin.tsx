import { isApiError } from '@shopflow/api-client'
import type { ReactElement, ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { Button } from '../../components/Button'
import { ErrorPanel, Skeleton } from '../../components/QueryStates'
import { hasStoredSession, useLogout, useSession } from './session'

/**
 * A UX affordance, not the enforcement: SecurityConfig.java:66 is what actually protects
 * /api/admin/**. This exists so a refusal is explained rather than shown as a broken shell.
 */
export function RequireAdmin({ children }: { children: ReactNode }): ReactElement {
  const location = useLocation()
  const { data, status, error, refetch } = useSession()

  if (!hasStoredSession()) {
    return <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />
  }

  if (status === 'pending') {
    return (
      <div className="mx-auto max-w-md p-6">
        <Skeleton rows={3} label="Checking your access" />
      </div>
    )
  }

  if (status === 'error') {
    if (isApiError(error) && (error.status === 401 || error.status === 403)) {
      return <Navigate to="/login" replace />
    }
    return (
      <div className="mx-auto max-w-lg p-6">
        <ErrorPanel error={error} onRetry={() => void refetch()} />
      </div>
    )
  }

  if (!data.roles.includes('ADMIN')) return <NoAdminAccess />

  return <>{children}</>
}

function NoAdminAccess(): ReactElement {
  const signOut = useLogout()

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-4 p-6 text-center">
      <h1 className="text-xl font-semibold">Administrator access required</h1>
      <p className="text-slate-600">
        This account does not have administrator access. Sign in with an administrator account to
        manage the catalogue.
      </p>
      <Button onClick={() => signOut.mutate()} loading={signOut.isPending}>
        Sign out
      </Button>
    </main>
  )
}
