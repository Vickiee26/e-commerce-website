import { defineConfig, devices } from '@playwright/test'

function required(name: string): string {
  const value = process.env[name]
  if (value === undefined || value === '') {
    throw new Error(
      `${name} is not set. Run the suite with the backend environment loaded:\n` +
        `  (set -a; . ../../../e-commerce-backend/.env; set +a; pnpm e2e)`,
    )
  }
  return value
}

export default defineConfig({
  testDir: './e2e',
  // One shared database. Parallel workers would fight over the same category codes.
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:5173',
    // 'off' on purpose, and it is what makes the spec's hand-rolled tracing possible. The runner
    // starts tracing on ANY context built from the `browser` fixture — including the one
    // catalogue.spec.ts creates in beforeAll — so a non-'off' value here both collides with that
    // file's own tracing.start() ("Tracing has been already started") and records signIn's fill()
    // with ADMIN_PASSWORD in plaintext. The journey trace is started there instead, after sign-in.
    trace: 'off',
    screenshot: 'only-on-failure',
  },
  // Playwright starts the dev server, which brings the /api and /auth proxy with it. It does NOT
  // start the backend: that has to be up already, and the suite fails loudly at sign-in if it isn't.
  webServer: {
    command: 'pnpm dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 120_000,
  },
  projects: [
    { name: 'laptop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    {
      name: 'mobile',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 390, height: 844 },
        hasTouch: true,
        isMobile: true,
      },
    },
  ],
  // Reading the credentials here fails the whole run at config load, before a browser starts, when
  // either is missing. The email and a boolean are recorded; the password never is.
  metadata: { adminEmail: required('ADMIN_EMAIL'), passwordPresent: required('ADMIN_PASSWORD') !== '' },
})
