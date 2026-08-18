import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { setBaseUrl } from '@shopflow/api-client'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { API, server } from './msw'

// jsdom's fetch needs an absolute URL; in the browser the base stays empty and the dev proxy
// keeps everything same-origin.
setBaseUrl(API)

// 'error' rather than 'warn': a request no handler covers is a test that is lying about scope.
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))

afterEach(() => {
  server.resetHandlers()
  cleanup()
})

afterAll(() => server.close())
