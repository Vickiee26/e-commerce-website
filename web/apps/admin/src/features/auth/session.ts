import {
  AUTH_EXPIRED_EVENT,
  clearTokens,
  getAccessToken,
  getRefreshToken,
  setTokens,
  type LoginRequest,
  type UserProfile,
} from '@shopflow/api-client'
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { useEffect } from 'react'
import { useNavigate } from 'react-router'
import { fetchMe, login, logout } from './api'

export const SESSION_QUERY_KEY = ['me']

const ADMIN_ROLE = 'ADMIN'

/** Whether it is worth asking the API who we are at all. */
export function hasStoredSession(): boolean {
  return getAccessToken() !== null || getRefreshToken() !== null
}

export function useSession(): UseQueryResult<UserProfile> {
  return useQuery({
    queryKey: SESSION_QUERY_KEY,
    queryFn: fetchMe,
    enabled: hasStoredSession(),
    retry: false,
    staleTime: Infinity,
  })
}

export class NotAnAdminError extends Error {
  constructor() {
    super('This account does not have administrator access.')
    this.name = 'NotAnAdminError'
  }
}

/**
 * Login is deliberately two calls. Without the /api/me role check a customer would reach a
 * shell in which every panel independently failed with 403 — safe, but unexplainable.
 */
export function useLogin(): UseMutationResult<UserProfile, unknown, LoginRequest> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async (credentials: LoginRequest): Promise<UserProfile> => {
      const pair = await login(credentials)
      setTokens({ accessToken: pair.accessToken, refreshToken: pair.refreshToken })
      const profile = await fetchMe()
      if (!profile.roles.includes(ADMIN_ROLE)) {
        // Hold no tokens for an account that cannot use this application.
        clearTokens()
        throw new NotAnAdminError()
      }
      return profile
    },
    // Seeding the cache means the gate does not repeat the /api/me call we just made.
    onSuccess: (profile) => queryClient.setQueryData(SESSION_QUERY_KEY, profile),
  })
}

export function useLogout(): UseMutationResult<void, unknown, void> {
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  return useMutation({
    mutationFn: async (): Promise<void> => {
      const refreshToken = getRefreshToken()
      try {
        if (refreshToken !== null) await logout(refreshToken)
      } catch {
        // A failed revoke still ends the local session: leaving someone signed in to a tool
        // that can archive the catalogue would be the worse outcome.
      }
      clearTokens()
    },
    onSettled: () => {
      queryClient.clear()
      navigate('/login', { replace: true })
    },
  })
}

/**
 * The api-client announces an unrecoverable session with a window event; deciding to leave the
 * screen is the app's job, not the client's.
 */
export function useAuthExpiredRedirect(): void {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  useEffect(() => {
    const onExpired = (): void => {
      queryClient.clear()
      navigate('/login', { replace: true })
    }
    window.addEventListener(AUTH_EXPIRED_EVENT, onExpired)
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, onExpired)
  }, [navigate, queryClient])
}
