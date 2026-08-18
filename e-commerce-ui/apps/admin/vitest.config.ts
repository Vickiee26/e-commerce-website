import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  test: {
    // Vitest's default glob also claims *.spec.ts, which would hand it the Playwright suite in
    // e2e/ and fail on `@playwright/test`. Unit tests live in src; Playwright owns e2e.
    include: ['src/**/*.test.{ts,tsx}'],
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
