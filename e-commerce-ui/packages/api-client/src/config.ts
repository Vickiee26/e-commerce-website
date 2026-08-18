let baseUrl = ''

/**
 * Empty in the browser on purpose: the Vite dev proxy serves `/api` and `/auth` from the
 * app's own origin, which is what keeps requests same-origin while the backend has no CORS
 * configuration. Tests set an absolute URL because `fetch` needs one outside a document.
 */
export function setBaseUrl(url: string): void {
  baseUrl = url.replace(/\/+$/, '')
}

export function getBaseUrl(): string {
  return baseUrl
}
