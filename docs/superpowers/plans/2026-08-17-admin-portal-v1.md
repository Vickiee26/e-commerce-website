# ShopFlow Admin Portal v1 (Catalogue) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an admin web portal that can populate and maintain the currently empty ShopFlow catalogue — categories, category types, products, variants, stock and images — usable on a 390px phone and a 1440px laptop.

**Architecture:** A pnpm workspace at `web/` holding one shared package and one app. `packages/api-client` owns everything that talks HTTP: generated OpenAPI types, a `fetch` wrapper that turns every non-2xx into a single `ApiError`, the token store, and single-flight refresh. `apps/admin` is a Vite SPA whose dev server proxies `/api` and `/auth` to `http://localhost:8080`, closing the CORS gap with no backend change. TanStack Query owns all server state; the only global client state is the auth session. Each feature folder (`auth`, `categories`, `products`) owns its own queries, mutations, forms and screens and never imports from a sibling.

**Tech Stack:** Node 24 · pnpm 11 · Vite 8 · React 19 · TypeScript 7 (strict) · React Router 8 · TanStack Query 5 · Tailwind CSS 4 · react-hook-form 7 + zod 4 · openapi-typescript 7 · Vitest 4 + React Testing Library + MSW 2 · Playwright 1.62

**Spec:** `docs/superpowers/specs/2026-08-17-admin-portal-v1-design.md`

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from the spec; where a value came from backend source, the file is named so you can re-check it.

- **Node 24 is the floor.** React Router 8 requires `>=22.22.0`, jsdom 30 requires `^24.15.0`, Vitest 4 requires `>=24`. Pin with `web/.nvmrc` containing `24`.
- **Backend base URL is `http://localhost:8080`.** There is no CORS configuration in `SecurityConfig.java` — it never calls `.cors(...)`. All browser traffic must go through the Vite dev proxy so it is same-origin. Never hardcode `http://localhost:8080` in app code.
- **`GET /api/admin/products` parameters are validated with `@Pattern`; a wrong value is a 400, not a fallback.** `archived` ∈ `exclude` | `only` | `all` (default `exclude`) — **not a boolean**. `sort` ∈ `name` | `price` | `createdAt` (default `name`). `direction` ∈ `asc` | `desc` (default `asc`). `q` max 100 chars. `page` >= 0. `size` 1–100 inclusive.
- **`categoryTypeId` is required on every product.** A category with zero types can hold zero products.
- **A category type belonging to a different category returns 404, not 400** (`AdminProductService.java:186-196`). The UI must make that combination unreachable.
- **Category and type `code` must match `[a-z0-9]+(-[a-z0-9]+)*`** — lower-case kebab-case, max 100 chars. Code is **immutable**: `UpdateCategoryRequest` and `UpdateCategoryTypeRequest` carry only `name` and `description`.
- **Duplicate codes are a 409 with `detail` and no `errors[]`.** `AdminCategoryService` checks `existsByCode` (categories, globally) and `existsByCategoryIdAndCode` (types, within one category) and throws `DuplicateResourceException`, which `GlobalExceptionHandler` maps to `409 Conflict`. Two different categories may hold types with the same code.
- **Deleting a category or a type is refused with a 409 while any product still uses it** (`AdminCategoryService.deleteCategory` / `deleteCategoryType`). The cascade to types only happens once nothing depends on it.
- **Stock changes only by signed non-zero `delta` plus a mandatory `reason` (max 500).** There is no set-absolute endpoint. `AdjustStockRequest` also exposes a spurious `deltaNonZero` boolean in the generated schema — never send it.
- **`UpdateVariantRequest` carries only `color` and `size`.** Stock is a separate call.
- **Product delete is soft** (sets `archivedAt`); `restore` clears it, is idempotent, and does **not** resurrect variants archived separately. **Category delete is hard and cascades to its types.**
- **`variantCount` and `totalStock` count unarchived variants only** (`AdminProductController.java:52-53`). Column headers read "Live variants" and "Live stock".
- **An archived product answers 200, not 404, on `GET /api/admin/products/{id}`**, and includes archived variants.
- **Catalogue prices carry no currency.** Format as USD (`OrderService.java:54-57` hardcodes `USD`). Never offer a currency selector.
- **`PageResponse*` is a custom envelope:** `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`. Not Spring's `Page`.
- **There is no upload endpoint.** `CreateResourceRequest.url` is a string, max 1000. No dropzone, no file picker.
- **One breakpoint: Tailwind `md` (768px).** One query feeds both presentations — `hidden md:table` beside `md:hidden` cards. Minimum 44px touch targets. No horizontally scrolling tables.
- **Every query renders three explicit states:** loading skeleton, error panel with Retry, empty state with a primary call to action.
- **Server `fieldErrors` merge into form errors, never replace them.** Each `{field, message}` maps to `setError(field, {message})`; unmatched entries plus `detail` become a form-level message.
- **`web/packages/api-client/src/generated.ts` is generated output. Never hand-edit it.** Regenerate with `pnpm gen:api` against a running backend.
- **Secrets:** `e-commerce-backend/.env` is gitignored and must never be committed. Never print `ADMIN_PASSWORD` in terminal output or commit messages.

## File Structure

```
.gitignore                                    modified: node_modules, playwright artefacts
e-commerce-backend/.env                       modified: ADMIN_EMAIL, ADMIN_PASSWORD (never committed)
web/
├─ .nvmrc                                     24
├─ pnpm-workspace.yaml                        packages/*, apps/*
├─ package.json                               root scripts, engines
├─ tsconfig.base.json                         strict compiler options both projects extend
├─ packages/api-client/
│  ├─ package.json                            @shopflow/api-client, gen:api script
│  ├─ tsconfig.json
│  ├─ vitest.config.ts                        jsdom environment (real sessionStorage + events)
│  └─ src/
│     ├─ generated.ts                         openapi-typescript output, committed, never edited
│     ├─ problem.ts                           FieldError, ProblemDetail, ApiError, toApiError
│     ├─ config.ts                            base URL, empty in the browser
│     ├─ tokens.ts                            access token in memory, refresh in sessionStorage
│     ├─ refresh.ts                           single-flight ensureFresh()
│     ├─ http.ts                              request(): auth header, 401 retry-once, ApiError
│     ├─ schemas.ts                           named re-exports of generated schema types
│     └─ index.ts                             package surface
└─ apps/admin/
   ├─ package.json                            @shopflow/admin
   ├─ tsconfig.json
   ├─ vite.config.ts                          react + tailwind plugins, /api and /auth proxy
   ├─ vitest.config.ts                        jsdom environment, setup file
   ├─ playwright.config.ts                    390px and 1440px projects
   ├─ index.html
   ├─ e2e/helpers.ts                          signIn(), ensureCategory(), RUN_ID
   ├─ e2e/catalogue.spec.ts                   full lifecycle against the live backend
   └─ src/
      ├─ main.tsx                             mounts App
      ├─ App.tsx                              QueryClientProvider + RouterProvider
      ├─ index.css                            @import "tailwindcss"
      ├─ test/setup.ts                        RTL cleanup, MSW server lifecycle
      ├─ test/msw.ts                          shared MSW server + typed handler helpers
      ├─ test/render.tsx                      renderWithProviders()
      ├─ lib/queryClient.ts                   QueryClient factory
      ├─ lib/format.ts                        formatUsd, formatDateTime
      ├─ lib/slug.ts                          toCode(), CODE_PATTERN
      ├─ lib/errors.ts                        describeError() for panels
      ├─ lib/formErrors.ts                    applyApiErrorToForm()
      ├─ components/QueryStates.tsx           Skeleton, ErrorPanel, EmptyState
      ├─ components/Dialog.tsx                sheet on mobile, modal on desktop
      ├─ components/ConfirmDialog.tsx         destructive confirmation
      ├─ components/Field.tsx                 Field label/error wiring + TextInput
      ├─ components/Button.tsx                variants, 44px min target
      ├─ components/Badge.tsx                 archived / no-types markers
      ├─ components/Toast.tsx                 transient success confirmations
      ├─ components/Pagination.tsx            null below two pages
      ├─ routes/router.tsx                    route table
      ├─ routes/AdminLayout.tsx               sidebar desktop / drawer mobile
      ├─ routes/NotFoundPage.tsx
      ├─ features/auth/
      │  ├─ api.ts                            login, fetchMe, logout
      │  ├─ session.ts                        useSession, useLogin, useLogout
      │  ├─ RequireAdmin.tsx                  the admin gate
      │  └─ LoginPage.tsx
      ├─ features/categories/
      │  ├─ api.ts                            category and type calls
      │  ├─ queries.ts                        useCategories + mutation hooks
      │  ├─ CategoriesPage.tsx                header, states, list
      │  ├─ CategoryCard.tsx                  one category, its types, its dialogs
      │  ├─ CategoryFormDialog.tsx            create (with code) / edit (without)
      │  └─ CategoryTypeFormDialog.tsx
      ├─ features/products/
      │  ├─ api.ts                            product calls, archive and restore
      │  ├─ queries.ts                        useAdminProducts, useAdminProduct, mutations
      │  ├─ filters.ts                        URL <-> ProductFilters
      │  ├─ productForm.ts                    zod schema; price stays a string
      │  ├─ ProductFields.tsx                 the dependent category/type pair, shared
      │  ├─ ProductsPage.tsx                  header, filter bar, states, pagination
      │  ├─ ProductList.tsx                   one query, table above md and cards below
      │  ├─ NewProductPage.tsx
      │  ├─ ProductDetailPage.tsx             details / variants / images
      │  └─ ProductDetailsCard.tsx            dirty-field-only PATCH, archive, restore
      ├─ features/variants/
      │  ├─ api.ts                            variant calls and adjustStock
      │  ├─ queries.ts                        mutations + shared invalidation
      │  ├─ VariantsCard.tsx                  rows, badges, archive and restore
      │  ├─ VariantFormDialog.tsx             colour, size, opening balance on create only
      │  └─ StockDialog.tsx                   target quantity -> signed delta
      └─ features/resources/
         ├─ api.ts                            resource calls
         ├─ queries.ts                        mutations + shared invalidation
         ├─ ImagePreview.tsx                  renders the URL, admits failure
         ├─ AddImageForm.tsx                  URL, label, make-primary
         └─ ImagesCard.tsx                    the gallery and its actions
```

`variants` and `resources` are their own feature folders rather than files inside `products`. They own separate endpoints (`/api/admin/variants/{id}`, `/api/admin/resources/{id}`) and separate mutation sets; the only thing they borrow from `products` is its query key, so that invalidation refetches the parent. Both mount on `ProductDetailPage`.

Fourteen tasks. Tasks 1–5 build infrastructure and the shared parts every screen needs; from Task 6 onward every task ends with something you can click.

---

### Task 1: Toolchain, admin account, workspace skeleton

Nothing in this project can be tested until an `ADMIN` account exists, so the account is part of this task and its verification is the task's test. There is no application code here, so the TDD cycle is replaced by verify-fails / act / verify-passes on real commands.

**Files:**
- Modify: `e-commerce-backend/.env` (never committed)
- Modify: `.gitignore`
- Create: `web/.nvmrc`
- Create: `web/pnpm-workspace.yaml`
- Create: `web/package.json`
- Create: `web/tsconfig.base.json`
- Create: `web/README.md`

**Interfaces:**
- Consumes: nothing.
- Produces: a working `pnpm` in `web/`; `web/tsconfig.base.json` for both projects to `extends`; an admin account whose credentials live only in `e-commerce-backend/.env`; the root scripts `pnpm dev`, `pnpm build`, `pnpm test`, `pnpm typecheck`, `pnpm gen:api`.

- [ ] **Step 1: Verify no admin account exists yet**

```bash
cd "$(git rev-parse --show-toplevel)"
grep -E '^ADMIN_EMAIL=' e-commerce-backend/.env
```

Expected: `ADMIN_EMAIL=` with nothing after it. That blank is why `AdminBootstrap` logs "No ADMIN_EMAIL configured; skipping administrator bootstrap" and skips.

- [ ] **Step 2: Set the admin credentials**

Edit `e-commerce-backend/.env` and set both lines. The password **must be at least 12 characters** — with `ADMIN_EMAIL` set and a shorter password, `AdminBootstrap` throws `IllegalStateException` and the backend refuses to start.

```
ADMIN_EMAIL=admin@shopflow.local
ADMIN_PASSWORD=<a password of at least 12 characters>
```

Do not add these to `.env.example`, do not paste the password into a commit message, and do not `echo` it. `.env` and `.env.*` are already gitignored.

- [ ] **Step 3: Restart the backend and confirm the bootstrap ran**

```bash
cd e-commerce-backend
docker compose up -d
./mvnw spring-boot:run
```

Expected in the startup log: a line from `AdminBootstrap` reporting the created administrator, and **no** `IllegalStateException`. If you see "ADMIN_PASSWORD must be at least 12 characters when ADMIN_EMAIL is set", go back to Step 2. If you see "ADMIN role is missing; V2 seed did not run", Flyway did not migrate — check the database connection before continuing. `AdminBootstrap` is idempotent, so restarting again is harmless.

- [ ] **Step 4: Verify the account holds the ADMIN role**

Leave the backend running and use a second terminal. This reads the password from `.env` without printing it:

```bash
cd "$(git rev-parse --show-toplevel)"
( set -a; . ./e-commerce-backend/.env; set +a
  TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | jq -r .accessToken)
  curl -s http://localhost:8080/api/me -H "Authorization: Bearer $TOKEN" | jq '{email, roles}' )
```

Expected: `{"email": "admin@shopflow.local", "roles": ["ADMIN"]}`. A `null` token means login failed; `roles` without `ADMIN` means the account pre-existed as a customer, so pick a different `ADMIN_EMAIL` and repeat from Step 2.

- [ ] **Step 5: Install Node 24 and enable pnpm**

```bash
nvm install 24
nvm use 24
node -v
corepack enable pnpm
pnpm -v
```

Expected: `node -v` prints `v24.x` (24.19.0 is Latest LTS), `pnpm -v` prints `11.x`. Node 18 will fail later at install time because React Router 8 declares `node >=22.22.0` and Vitest 4 declares `>=24`.

- [ ] **Step 6: Create the workspace root files**

`web/.nvmrc`:

```
24
```

`web/pnpm-workspace.yaml`:

```yaml
packages:
  - 'packages/*'
  - 'apps/*'
```

`web/package.json`:

```json
{
  "name": "shopflow-web",
  "private": true,
  "type": "module",
  "engines": {
    "node": ">=24"
  },
  "scripts": {
    "dev": "pnpm --filter @shopflow/admin dev",
    "build": "pnpm --filter @shopflow/admin build",
    "test": "pnpm -r test",
    "typecheck": "pnpm -r typecheck",
    "gen:api": "pnpm --filter @shopflow/api-client gen:api",
    "e2e": "pnpm --filter @shopflow/admin e2e"
  },
  "devDependencies": {
    "typescript": "^7.0.2"
  }
}
```

`web/tsconfig.base.json`:

```json
{
  "compilerOptions": {
    "target": "ES2023",
    "lib": ["ES2023"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "noImplicitOverride": true,
    "verbatimModuleSyntax": true,
    "isolatedModules": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "resolveJsonModule": true,
    "useDefineForClassFields": true,
    "forceConsistentCasingInFileNames": true
  }
}
```

`web/README.md`:

```markdown
# ShopFlow Web

pnpm workspace for the ShopFlow frontends.

| Path | Contents |
|---|---|
| `packages/api-client` | Generated OpenAPI types, fetch wrapper, token store, refresh |
| `apps/admin` | Admin portal (catalogue administration) |

## Getting started

The backend must be running on `http://localhost:8080`, and an admin account must
exist (`ADMIN_EMAIL` / `ADMIN_PASSWORD` in `e-commerce-backend/.env`, password at
least 12 characters).

```bash
nvm use              # reads .nvmrc -> Node 24
corepack enable pnpm
pnpm install
pnpm dev             # admin portal on http://localhost:5173
```

The dev server proxies `/api` and `/auth` to `http://localhost:8080`. The backend has
no CORS configuration, so never point the app at port 8080 directly.

`pnpm gen:api` regenerates `packages/api-client/src/generated.ts` from the running
backend. The output is committed; never hand-edit it.
```

- [ ] **Step 7: Install and verify the workspace resolves**

```bash
cd web
pnpm install
pnpm exec tsc -v
```

Expected: install completes, and `tsc -v` prints `Version 7.0.2` or later. pnpm reporting no workspace projects yet is correct — `packages/*` and `apps/*` are still empty.

- [ ] **Step 8: Ignore build and dependency output**

Append to the repository-root `.gitignore`:

```gitignore
# web workspace
node_modules/
web/**/dist/
web/**/.vite/
web/apps/admin/test-results/
web/apps/admin/playwright-report/
```

- [ ] **Step 9: Confirm nothing secret is staged**

```bash
cd "$(git rev-parse --show-toplevel)"
git status --short
git check-ignore -v e-commerce-backend/.env web/node_modules
```

Expected: `git status` lists only `.gitignore` and the new `web/` files — **not** `e-commerce-backend/.env` and **not** anything under `node_modules`. `git check-ignore` must print a matching rule for both paths.

- [ ] **Step 10: Commit**

```bash
git add .gitignore web/.nvmrc web/pnpm-workspace.yaml web/package.json web/tsconfig.base.json web/README.md
git commit -m "chore(web): scaffold the pnpm workspace for the frontends"
```

---

### Task 2: `api-client` — generated types and the error model

**Files:**
- Create: `web/packages/api-client/package.json`
- Create: `web/packages/api-client/tsconfig.json`
- Create: `web/packages/api-client/vitest.config.ts`
- Create: `web/packages/api-client/src/generated.ts` (generated, committed, never hand-edited)
- Create: `web/packages/api-client/src/problem.ts`
- Create: `web/packages/api-client/src/schemas.ts`
- Create: `web/packages/api-client/src/index.ts`
- Test: `web/packages/api-client/src/problem.test.ts`

**Interfaces:**
- Consumes: `web/tsconfig.base.json` from Task 1; a running backend at `http://localhost:8080` for `pnpm gen:api`.
- Produces, all exported from `@shopflow/api-client`:
  - `type FieldError = { field: string; message: string }`
  - `type ProblemDetail = { type?, title?, status?, detail?, instance?, errors?: FieldError[] }`
  - `class ApiError extends Error { readonly status: number; readonly title?: string; readonly detail?: string; readonly fieldErrors: FieldError[] }`, constructor `(status: number, problem?: ProblemDetail)`
  - `class NetworkError extends ApiError { readonly networkCause: unknown }`, constructor `(networkCause: unknown)`, always `status === 0`
  - `function isApiError(value: unknown): value is ApiError`
  - `function toApiError(response: Response): Promise<ApiError>`
  - request types re-exported verbatim from the generated schemas: `LoginRequest`, `RefreshRequest`, `LogoutRequest`, `CreateCategoryRequest`, `UpdateCategoryRequest`, `CreateCategoryTypeRequest`, `UpdateCategoryTypeRequest`, `CreateProductRequest`, `UpdateProductRequest`, `CreateVariantRequest`, `UpdateVariantRequest`, `AdjustStockRequest`, `CreateResourceRequest`, `UpdateResourceRequest`
  - response view types with always-present fields narrowed to non-optional: `TokenPair`, `UserProfile`, `Category`, `CategoryType`, `AdminProductSummary`, `AdminProduct`, `AdminVariant`, `ProductResource`, `StockAdjustment`, `AdminProductPage`

- [ ] **Step 1: Create the package manifest and configs**

`web/packages/api-client/package.json`:

```json
{
  "name": "@shopflow/api-client",
  "version": "0.0.0",
  "private": true,
  "type": "module",
  "main": "./src/index.ts",
  "types": "./src/index.ts",
  "exports": {
    ".": "./src/index.ts"
  },
  "scripts": {
    "gen:api": "pnpm dlx --package=typescript@5.9.3 --package=openapi-typescript@7.13.0 openapi-typescript http://localhost:8080/v3/api-docs -o src/generated.ts",
    "test": "vitest run",
    "typecheck": "tsc --noEmit"
  },
  "devDependencies": {
    "jsdom": "^30.0.1",
    "typescript": "^7.0.2",
    "vitest": "^4.1.10"
  }
}
```

`web/packages/api-client/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "lib": ["ES2023", "DOM"],
    "types": ["vitest/globals"],
    "noEmit": true
  },
  "include": ["src"]
}
```

`DOM` is in `lib` for `fetch`, `Response` and `sessionStorage`. This package never imports React.

`web/packages/api-client/vitest.config.ts`:

```ts
import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    globals: true,
    environment: 'jsdom',
  },
})
```

jsdom rather than node, because Task 3 stores the refresh token in a real `sessionStorage` and dispatches a real `auth:expired` window event. Node has neither.

- [ ] **Step 2: Install and generate the types**

The backend from Task 1 must still be running.

```bash
cd web
pnpm install
pnpm gen:api
head -20 packages/api-client/src/generated.ts
grep -c 'CreateProductRequest' packages/api-client/src/generated.ts
```

Expected: `generated.ts` exists, starts with the "do not make direct changes" banner, and the grep count is at least 1. If `gen:api` fails to fetch, the backend is not running — restart it per Task 1 Step 3.

- [ ] **Step 3: Write the failing test**

`web/packages/api-client/src/problem.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { ApiError, NetworkError, isApiError, toApiError } from './problem'

function problemResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

describe('toApiError', () => {
  it('maps a ProblemDetail with errors[] onto fieldErrors', async () => {
    const error = await toApiError(
      problemResponse(400, {
        title: 'Validation failed',
        status: 400,
        detail: 'One or more fields are invalid',
        instance: '/api/admin/categories',
        errors: [
          { field: 'code', message: 'must be lower-case letters, digits and single hyphens' },
          { field: 'name', message: 'must not be blank' },
        ],
      }),
    )

    expect(error.status).toBe(400)
    expect(error.title).toBe('Validation failed')
    expect(error.detail).toBe('One or more fields are invalid')
    expect(error.message).toBe('One or more fields are invalid')
    expect(error.fieldErrors).toEqual([
      { field: 'code', message: 'must be lower-case letters, digits and single hyphens' },
      { field: 'name', message: 'must not be blank' },
    ])
    expect(isApiError(error)).toBe(true)
  })

  it('leaves fieldErrors empty when the body has no errors array', async () => {
    const error = await toApiError(
      problemResponse(409, { title: 'Conflict', status: 409, detail: 'Category code already exists' }),
    )

    expect(error.status).toBe(409)
    expect(error.fieldErrors).toEqual([])
    expect(error.message).toBe('Category code already exists')
  })

  it('survives a non-JSON body and falls back to a status message', async () => {
    const error = await toApiError(new Response('<html>502</html>', { status: 502 }))

    expect(error.status).toBe(502)
    expect(error.title).toBeUndefined()
    expect(error.fieldErrors).toEqual([])
    expect(error.message).toBe('Request failed with status 502')
  })

  it('discards malformed entries in errors[]', async () => {
    const error = await toApiError(
      problemResponse(400, {
        detail: 'bad',
        errors: [{ field: 'name', message: 'must not be blank' }, { field: 'price' }, 'nonsense'],
      }),
    )

    expect(error.fieldErrors).toEqual([{ field: 'name', message: 'must not be blank' }])
  })
})

describe('NetworkError', () => {
  it('reports status 0 and keeps the underlying cause', () => {
    const cause = new TypeError('Failed to fetch')
    const error = new NetworkError(cause)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(0)
    expect(error.networkCause).toBe(cause)
    expect(error.message).toContain('Could not reach the server')
  })
})
```

- [ ] **Step 4: Run the test to verify it fails**

```bash
cd web/packages/api-client
pnpm test
```

Expected: FAIL — `Failed to resolve import "./problem"`.

- [ ] **Step 5: Write the implementation**

`web/packages/api-client/src/problem.ts`:

```ts
export type FieldError = { field: string; message: string }

/**
 * RFC 7807 body as this backend emits it. `errors` is the backend's extension and is
 * what lets a server-side validation message land on the input that caused it.
 */
export type ProblemDetail = {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  errors?: unknown[]
}

function isFieldError(value: unknown): value is FieldError {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Partial<FieldError>
  return typeof candidate.field === 'string' && typeof candidate.message === 'string'
}

/** Every non-2xx answer becomes one of these, so callers have a single shape to handle. */
export class ApiError extends Error {
  readonly status: number
  readonly title?: string
  readonly detail?: string
  readonly fieldErrors: FieldError[]

  constructor(status: number, problem?: ProblemDetail) {
    super(problem?.detail ?? problem?.title ?? `Request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.title = problem?.title
    this.detail = problem?.detail
    this.fieldErrors = (problem?.errors ?? []).filter(isFieldError)
  }
}

/**
 * A request that never got an HTTP answer — offline, DNS, connection refused. Status 0
 * distinguishes it from any real response so the UI can word it differently.
 */
export class NetworkError extends ApiError {
  readonly networkCause: unknown

  constructor(networkCause: unknown) {
    super(0, {
      title: 'Network error',
      detail: 'Could not reach the server. Check your connection and try again.',
    })
    this.name = 'NetworkError'
    this.networkCause = networkCause
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError
}

/** Reads the body once. An empty or non-JSON body yields an ApiError carrying only the status. */
export async function toApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail | undefined
  try {
    const body: unknown = await response.json()
    if (typeof body === 'object' && body !== null) {
      problem = body as ProblemDetail
    }
  } catch {
    problem = undefined
  }
  return new ApiError(response.status, problem)
}
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
cd web/packages/api-client
pnpm test
```

Expected: PASS, 5 tests.

- [ ] **Step 7: Add the named schema types**

The generated response schemas mark every property optional, because the backend's records carry no `required` metadata. Writing `product.name!` at every call site would spread that lie through the app, so narrow the always-present fields here, once. `RequireKeys` keys are checked against the generated type, so an API rename still breaks the build.

`web/packages/api-client/src/schemas.ts`:

```ts
import type { components } from './generated'

type Schemas = components['schemas']

/** Makes the listed keys required and non-nullable, leaving the rest as generated. */
type RequireKeys<T, K extends keyof T> = Omit<T, K> & { [P in K]-?: NonNullable<T[P]> }

// Requests are used verbatim: their `required` arrays are already correct.
export type LoginRequest = Schemas['LoginRequest']
export type RefreshRequest = Schemas['RefreshRequest']
export type LogoutRequest = Schemas['LogoutRequest']
export type CreateCategoryRequest = Schemas['CreateCategoryRequest']
export type UpdateCategoryRequest = Schemas['UpdateCategoryRequest']
export type CreateCategoryTypeRequest = Schemas['CreateCategoryTypeRequest']
export type UpdateCategoryTypeRequest = Schemas['UpdateCategoryTypeRequest']
export type CreateProductRequest = Schemas['CreateProductRequest']
export type UpdateProductRequest = Schemas['UpdateProductRequest']
export type CreateVariantRequest = Schemas['CreateVariantRequest']
export type UpdateVariantRequest = Schemas['UpdateVariantRequest']
export type CreateResourceRequest = Schemas['CreateResourceRequest']
export type UpdateResourceRequest = Schemas['UpdateResourceRequest']

/**
 * `deltaNonZero` is an @AssertTrue validator leaking into the generated document. Omitting
 * it here makes sending it a type error rather than a puzzling 400.
 */
export type AdjustStockRequest = Omit<Schemas['AdjustStockRequest'], 'deltaNonZero'>

export type TokenPair = RequireKeys<
  Schemas['TokenPairResponse'],
  'accessToken' | 'refreshToken' | 'tokenType' | 'expiresIn'
>

export type UserProfile = RequireKeys<
  Schemas['UserProfileResponse'],
  'id' | 'email' | 'fullName' | 'emailVerified' | 'roles' | 'createdAt'
>

export type CategoryType = RequireKeys<Schemas['CategoryTypeResponse'], 'id' | 'code' | 'name'>

export type Category = RequireKeys<
  Omit<Schemas['CategoryResponse'], 'types'>,
  'id' | 'code' | 'name'
> & { types: CategoryType[] }

export type AdminVariant = RequireKeys<
  Schemas['AdminVariantResponse'],
  'id' | 'color' | 'size' | 'stockQuantity'
>

export type ProductResource = RequireKeys<Schemas['ProductResourceDto'], 'id' | 'url' | 'isPrimary'>

/** `variantCount` and `totalStock` cover unarchived variants only. */
export type AdminProductSummary = RequireKeys<
  Schemas['AdminProductSummaryResponse'],
  | 'id'
  | 'name'
  | 'price'
  | 'categoryId'
  | 'categoryName'
  | 'categoryTypeId'
  | 'categoryTypeName'
  | 'variantCount'
  | 'totalStock'
>

export type AdminProduct = RequireKeys<
  Omit<Schemas['AdminProductResponse'], 'variants' | 'resources'>,
  'id' | 'name' | 'price' | 'categoryId' | 'categoryName' | 'categoryTypeId' | 'categoryTypeName'
> & { variants: AdminVariant[]; resources: ProductResource[] }

export type StockAdjustment = RequireKeys<
  Schemas['StockAdjustmentResponse'],
  'variantId' | 'previousQuantity' | 'newQuantity' | 'delta' | 'reason'
>

export type AdminProductPage = RequireKeys<
  Omit<Schemas['PageResponseAdminProductSummaryResponse'], 'content'>,
  'page' | 'size' | 'totalElements' | 'totalPages' | 'first' | 'last'
> & { content: AdminProductSummary[] }
```

`web/packages/api-client/src/index.ts`:

```ts
export * from './problem'
export * from './schemas'
```

- [ ] **Step 8: Typecheck**

```bash
cd web/packages/api-client
pnpm typecheck
```

Expected: no output, exit 0. An error naming a key in a `RequireKeys` list means the API renamed that field — fix the type, do not edit `generated.ts`.

- [ ] **Step 9: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add web/packages/api-client web/pnpm-lock.yaml
git commit -m "feat(api-client): generated types and a single ApiError for every failure"
```

---

### Task 3: `api-client` — token store, single-flight refresh, request wrapper

This task delivers acceptance criteria 2 and 3. The backend rotates refresh tokens and implements reuse detection, so two concurrent refreshes would present the same token twice and could kill the session. Single-flight is a correctness requirement, not an optimisation.

**Files:**
- Create: `web/packages/api-client/src/config.ts`
- Create: `web/packages/api-client/src/tokens.ts`
- Create: `web/packages/api-client/src/refresh.ts`
- Create: `web/packages/api-client/src/http.ts`
- Modify: `web/packages/api-client/src/index.ts`
- Test: `web/packages/api-client/src/tokens.test.ts`
- Test: `web/packages/api-client/src/http.test.ts`

**Interfaces:**
- Consumes: `ApiError`, `NetworkError`, `toApiError` from `./problem`; `TokenPair` from `./schemas` (Task 2).
- Produces, all exported from `@shopflow/api-client`:
  - `function setBaseUrl(url: string): void` / `function getBaseUrl(): string`
  - `const AUTH_EXPIRED_EVENT = 'auth:expired'`
  - `function getAccessToken(): string | null`
  - `function getRefreshToken(): string | null`
  - `function setTokens(tokens: { accessToken: string; refreshToken: string }): void`
  - `function clearTokens(): void`
  - `function emitAuthExpired(): void`
  - `function ensureFresh(): Promise<string>` — resolves the new access token
  - `function restoreSession(): Promise<boolean>`
  - `function resetRefreshState(): void` — test seam only
  - `type RequestOptions = { method?: 'GET'|'POST'|'PATCH'|'PUT'|'DELETE'; body?: unknown; query?: Record<string, string|number|boolean|undefined|null>; auth?: boolean; signal?: AbortSignal }`
  - `function request<T>(path: string, options?: RequestOptions): Promise<T>` — resolves `undefined as T` for 204

- [ ] **Step 1: Write the failing token-store test**

`web/packages/api-client/src/tokens.test.ts`:

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AUTH_EXPIRED_EVENT,
  clearTokens,
  emitAuthExpired,
  getAccessToken,
  getRefreshToken,
  setTokens,
} from './tokens'

beforeEach(() => {
  clearTokens()
  sessionStorage.clear()
})

describe('token store', () => {
  it('keeps the access token in memory and the refresh token in sessionStorage', () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    expect(getAccessToken()).toBe('access-1')
    expect(getRefreshToken()).toBe('refresh-1')
    expect(sessionStorage.getItem('shopflow.refreshToken')).toBe('refresh-1')
    // The access token must never be persisted: a stored one would outlive the tab.
    expect(JSON.stringify(sessionStorage)).not.toContain('access-1')
    expect(JSON.stringify(localStorage)).not.toContain('refresh-1')
  })

  it('clears both stores', () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    clearTokens()

    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    expect(sessionStorage.getItem('shopflow.refreshToken')).toBeNull()
  })

  it('emitAuthExpired clears the tokens and dispatches the event once', () => {
    const listener = vi.fn()
    window.addEventListener(AUTH_EXPIRED_EVENT, listener)
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    emitAuthExpired()

    expect(listener).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    window.removeEventListener(AUTH_EXPIRED_EVENT, listener)
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd web/packages/api-client
pnpm test tokens
```

Expected: FAIL — `Failed to resolve import "./tokens"`.

- [ ] **Step 3: Write the config and token store**

`web/packages/api-client/src/config.ts`:

```ts
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
```

`web/packages/api-client/src/tokens.ts`:

```ts
const REFRESH_TOKEN_KEY = 'shopflow.refreshToken'

/** Dispatched on `window` when the session cannot be recovered. The app redirects to /login. */
export const AUTH_EXPIRED_EVENT = 'auth:expired'

/**
 * Module-level, never persisted. The API returns tokens in the response body so httpOnly
 * cookies are unavailable; keeping the access token out of storage limits the blast radius,
 * and keeping the refresh token in sessionStorage ends the session when the browser closes.
 */
let accessToken: string | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function getRefreshToken(): string | null {
  try {
    return sessionStorage.getItem(REFRESH_TOKEN_KEY)
  } catch {
    return null // private browsing can throw on access
  }
}

export function setTokens(tokens: { accessToken: string; refreshToken: string }): void {
  accessToken = tokens.accessToken
  try {
    sessionStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken)
  } catch {
    // Storage unavailable: the session simply will not survive a reload.
  }
}

export function clearTokens(): void {
  accessToken = null
  try {
    sessionStorage.removeItem(REFRESH_TOKEN_KEY)
  } catch {
    // Nothing to do.
  }
}

export function emitAuthExpired(): void {
  clearTokens()
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT))
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

```bash
cd web/packages/api-client
pnpm test tokens
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Write the failing request/refresh test**

`web/packages/api-client/src/http.test.ts`:

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setBaseUrl } from './config'
import { request } from './http'
import { ApiError, NetworkError } from './problem'
import { resetRefreshState } from './refresh'
import { AUTH_EXPIRED_EVENT, clearTokens, getAccessToken, getRefreshToken, setTokens } from './tokens'

const BASE = 'http://backend.test'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const TOKEN_PAIR = {
  accessToken: 'access-2',
  refreshToken: 'refresh-2',
  tokenType: 'Bearer',
  expiresIn: 900,
}

beforeEach(() => {
  setBaseUrl(BASE)
  clearTokens()
  sessionStorage.clear()
  resetRefreshState()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('request', () => {
  it('sends the bearer token, the query string and a JSON body', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(200, { id: 'p1' }))
    vi.stubGlobal('fetch', fetchMock)
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    const result = await request<{ id: string }>('/api/admin/products', {
      method: 'POST',
      body: { name: 'Abaya' },
      query: { archived: 'exclude', categoryId: undefined, q: '', page: 0 },
    })

    expect(result).toEqual({ id: 'p1' })
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe(`${BASE}/api/admin/products?archived=exclude&page=0`)
    expect(init.method).toBe('POST')
    expect(init.body).toBe('{"name":"Abaya"}')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer access-1')
  })

  it('omits the Authorization header when auth is false', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(200, TOKEN_PAIR))
    vi.stubGlobal('fetch', fetchMock)
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    await request('/auth/login', { method: 'POST', body: {}, auth: false })

    const [, init] = fetchMock.mock.calls[0]!
    expect(new Headers(init.headers).get('Authorization')).toBeNull()
  })

  it('resolves undefined for 204 No Content', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 204 })))
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    await expect(request<void>('/api/admin/products/p1', { method: 'DELETE' })).resolves.toBeUndefined()
  })

  it('throws an ApiError carrying fieldErrors on 400', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        jsonResponse(400, { detail: 'invalid', errors: [{ field: 'code', message: 'must be lower-case' }] }),
      ),
    )

    await expect(request('/api/admin/categories', { method: 'POST', body: {}, auth: false })).rejects.toSatisfy(
      (error: unknown) =>
        error instanceof ApiError &&
        error.status === 400 &&
        error.fieldErrors[0]?.field === 'code',
    )
  })

  it('turns a failed fetch into a NetworkError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))

    await expect(request('/api/categories', { auth: false })).rejects.toBeInstanceOf(NetworkError)
  })
})

describe('single-flight refresh', () => {
  it('collapses concurrent 401s into exactly one POST /auth/refresh and retries each request', async () => {
    const calls: string[] = []
    let refreshed = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        calls.push(url)
        if (url.endsWith('/auth/refresh')) {
          refreshed = true
          return jsonResponse(200, TOKEN_PAIR)
        }
        return refreshed ? jsonResponse(200, { ok: true }) : jsonResponse(401, { title: 'Unauthorized' })
      }),
    )
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    const results = await Promise.all([
      request<{ ok: boolean }>('/api/admin/products'),
      request<{ ok: boolean }>('/api/categories'),
      request<{ ok: boolean }>('/api/me'),
    ])

    expect(results).toEqual([{ ok: true }, { ok: true }, { ok: true }])
    expect(calls.filter((url) => url.endsWith('/auth/refresh'))).toHaveLength(1)
    expect(getAccessToken()).toBe('access-2')
    expect(getRefreshToken()).toBe('refresh-2')
  })

  it('retries a request only once, so a second 401 surfaces', async () => {
    const calls: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        calls.push(url)
        if (url.endsWith('/auth/refresh')) return jsonResponse(200, TOKEN_PAIR)
        return jsonResponse(401, { title: 'Unauthorized' })
      }),
    )
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    await expect(request('/api/admin/products')).rejects.toSatisfy(
      (error: unknown) => error instanceof ApiError && error.status === 401,
    )
    expect(calls.filter((url) => url.endsWith('/api/admin/products'))).toHaveLength(2)
    expect(calls.filter((url) => url.endsWith('/auth/refresh'))).toHaveLength(1)
  })

  it('purges the tokens and announces auth:expired when the refresh itself fails', async () => {
    const listener = vi.fn()
    window.addEventListener(AUTH_EXPIRED_EVENT, listener)
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) =>
        String(input).endsWith('/auth/refresh')
          ? jsonResponse(401, { title: 'Invalid refresh token' })
          : jsonResponse(401, { title: 'Unauthorized' }),
      ),
    )
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })

    await expect(request('/api/admin/products')).rejects.toBeInstanceOf(ApiError)
    expect(listener).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    window.removeEventListener(AUTH_EXPIRED_EVENT, listener)
  })

  it('does not attempt a refresh when no refresh token is stored', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(401, { title: 'Unauthorized' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(request('/api/admin/products')).rejects.toBeInstanceOf(ApiError)
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/auth/refresh'))).toHaveLength(0)
  })
})
```

- [ ] **Step 6: Run it to verify it fails**

```bash
cd web/packages/api-client
pnpm test http
```

Expected: FAIL — `Failed to resolve import "./http"`.

- [ ] **Step 7: Write the single-flight refresh**

`web/packages/api-client/src/refresh.ts`:

```ts
import { getBaseUrl } from './config'
import { ApiError, NetworkError, toApiError } from './problem'
import type { TokenPair } from './schemas'
import { emitAuthExpired, getAccessToken, getRefreshToken, setTokens } from './tokens'

/**
 * The one in-flight refresh, shared by every caller. The backend rotates refresh tokens and
 * detects reuse, so a second concurrent call would present an already-spent token and could
 * invalidate the whole session.
 */
let inFlight: Promise<string> | null = null

export function ensureFresh(): Promise<string> {
  inFlight ??= runRefresh().finally(() => {
    inFlight = null
  })
  return inFlight
}

async function runRefresh(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (refreshToken === null) {
    emitAuthExpired()
    throw new ApiError(401, { title: 'Session expired', detail: 'Please sign in again.' })
  }

  let response: Response
  try {
    response = await fetch(`${getBaseUrl()}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
  } catch (cause) {
    // A flaky network is not an expired session, so the tokens survive and the user can retry.
    throw new NetworkError(cause)
  }

  if (!response.ok) {
    const error = await toApiError(response)
    emitAuthExpired()
    throw error
  }

  const pair = (await response.json()) as TokenPair
  setTokens({ accessToken: pair.accessToken, refreshToken: pair.refreshToken })
  return pair.accessToken
}

/**
 * A stored refresh token with no access token means the page was reloaded. Exchanging it once
 * on startup is what keeps an admin signed in across a refresh.
 */
export async function restoreSession(): Promise<boolean> {
  if (getAccessToken() !== null) return true
  if (getRefreshToken() === null) return false
  try {
    await ensureFresh()
    return true
  } catch {
    return false
  }
}

/** Test seam: drops any remembered in-flight promise between cases. */
export function resetRefreshState(): void {
  inFlight = null
}
```

- [ ] **Step 8: Write the request wrapper**

`web/packages/api-client/src/http.ts`:

```ts
import { getBaseUrl } from './config'
import { NetworkError, toApiError } from './problem'
import { ensureFresh } from './refresh'
import { getAccessToken } from './tokens'

export type QueryValue = string | number | boolean | undefined | null

export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'

export type RequestOptions = {
  method?: HttpMethod
  body?: unknown
  query?: Record<string, QueryValue>
  /** false for /auth/login and /auth/refresh, which must neither carry nor renew a token. */
  auth?: boolean
  signal?: AbortSignal
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, auth = true, signal } = options
  const url = `${getBaseUrl()}${path}${buildQuery(query)}`

  let response = await send(url, method, body, auth ? getAccessToken() : null, signal)

  if (response.status === 401 && auth) {
    // Throws if the refresh fails, which is what surfaces the expired session to the caller.
    const accessToken = await ensureFresh()
    response = await send(url, method, body, accessToken, signal)
  }

  if (!response.ok) throw await toApiError(response)
  return await readBody<T>(response)
}

/** Drops undefined, null and empty values so the backend never sees `?q=` or `?categoryId=`. */
function buildQuery(query: Record<string, QueryValue> | undefined): string {
  if (query === undefined) return ''
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    params.set(key, String(value))
  }
  const search = params.toString()
  return search === '' ? '' : `?${search}`
}

async function send(
  url: string,
  method: HttpMethod,
  body: unknown,
  accessToken: string | null,
  signal: AbortSignal | undefined,
): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (accessToken !== null) headers.Authorization = `Bearer ${accessToken}`

  try {
    return await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (cause) {
    if (signal?.aborted === true) throw cause // an abort is not a network failure
    throw new NetworkError(cause)
  }
}

async function readBody<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T
  const text = await response.text()
  return (text === '' ? undefined : JSON.parse(text)) as T
}
```

- [ ] **Step 9: Extend the package surface**

`web/packages/api-client/src/index.ts`:

```ts
export * from './config'
export * from './http'
export * from './problem'
export * from './refresh'
export * from './schemas'
export * from './tokens'
```

- [ ] **Step 10: Run the whole package and typecheck**

```bash
cd web/packages/api-client
pnpm test
pnpm typecheck
```

Expected: PASS, 16 tests across three files; typecheck silent. The two counted-call assertions in `single-flight refresh` are acceptance criterion 3.

- [ ] **Step 11: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add web/packages/api-client web/pnpm-lock.yaml
git commit -m "feat(api-client): token store and single-flight refresh behind one request wrapper"
```

---

### Task 4: Admin app scaffold, dev proxy, test harness

The visible deliverable is a booting app whose not-found route works and whose dev server successfully proxies to the backend. From Task 5 the nav links point at routes that do not exist yet; until their task lands they resolve to the not-found page, which is expected.

**Files:**
- Create: `web/apps/admin/package.json`
- Create: `web/apps/admin/tsconfig.json`
- Create: `web/apps/admin/vite.config.ts`
- Create: `web/apps/admin/vitest.config.ts`
- Create: `web/apps/admin/index.html`
- Create: `web/apps/admin/src/main.tsx`
- Create: `web/apps/admin/src/App.tsx`
- Create: `web/apps/admin/src/index.css`
- Create: `web/apps/admin/src/lib/queryClient.ts`
- Create: `web/apps/admin/src/lib/format.ts`
- Create: `web/apps/admin/src/routes/router.tsx`
- Create: `web/apps/admin/src/routes/NotFoundPage.tsx`
- Create: `web/apps/admin/src/test/setup.ts`
- Create: `web/apps/admin/src/test/msw.ts`
- Create: `web/apps/admin/src/test/render.tsx`
- Test: `web/apps/admin/src/lib/format.test.ts`
- Test: `web/apps/admin/src/routes/NotFoundPage.test.tsx`

**Interfaces:**
- Consumes: `@shopflow/api-client` (`setBaseUrl`, `restoreSession`, `isApiError`) from Tasks 2–3.
- Produces:
  - `function createQueryClient(): QueryClient`
  - `function formatUsd(amount: number): string`
  - `function formatDateTime(iso: string): string`
  - `const router` — a `createBrowserRouter` route table that later tasks add entries to
  - `function NotFoundPage(): ReactElement`
  - `function App(): ReactElement`
  - test harness: `server` (MSW `setupServer`), `API` (`'http://backend.test'`), re-exported `http` and `HttpResponse`, `problemResponse(status, body)`, and `renderWithProviders(ui, { route?, path? })` returning `RenderResult & { queryClient: QueryClient }`

- [ ] **Step 1: Create the package manifest**

`web/apps/admin/package.json`:

```json
{
  "name": "@shopflow/admin",
  "version": "0.0.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "@shopflow/api-client": "workspace:*",
    "@tanstack/react-query": "^5.101.4",
    "react": "^19.2.8",
    "react-dom": "^19.2.8",
    "react-router": "^8.3.0"
  },
  "devDependencies": {
    "@tailwindcss/vite": "^4.3.3",
    "@testing-library/jest-dom": "^6.9.1",
    "@testing-library/react": "^16.3.2",
    "@testing-library/user-event": "^14.6.4",
    "@types/react": "^19.2.18",
    "@types/react-dom": "^19.2.4",
    "@vitejs/plugin-react": "^6.0.5",
    "jsdom": "^30.0.1",
    "msw": "^2.15.0",
    "tailwindcss": "^4.3.3",
    "typescript": "^7.0.2",
    "vite": "^8.2.1",
    "vitest": "^4.1.10"
  }
}
```

- [ ] **Step 2: Create the build and test configuration**

`web/apps/admin/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "lib": ["ES2023", "DOM", "DOM.Iterable"],
    "jsx": "react-jsx",
    "types": ["vitest/globals", "@testing-library/jest-dom/vitest"],
    "noEmit": true
  },
  "include": ["src", "e2e", "*.config.ts"]
}
```

`web/apps/admin/vite.config.ts`:

```ts
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const BACKEND = process.env.VITE_BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // The workspace client ships TypeScript source; pre-bundling it would skip the transform.
  optimizeDeps: { exclude: ['@shopflow/api-client'] },
  server: {
    port: 5173,
    // SecurityConfig.java never calls .cors(...), so the backend rejects cross-origin browser
    // calls. Proxying makes /api and /auth same-origin and needs no backend change.
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/auth': { target: BACKEND, changeOrigin: true },
    },
  },
})
```

`web/apps/admin/vitest.config.ts`:

```ts
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
```

`web/apps/admin/index.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>ShopFlow Admin</title>
  </head>
  <body class="bg-slate-50 text-slate-900 antialiased">
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

The viewport meta tag is what makes every later `md:` breakpoint mean anything on a phone. Without it a 390px device renders as a 980px desktop.

- [ ] **Step 3: Install**

```bash
cd web
pnpm install
```

Expected: both workspace projects are linked; `web/apps/admin/node_modules/@shopflow/api-client` is a symlink into `packages/api-client`.

- [ ] **Step 4: Write the failing tests**

`web/apps/admin/src/lib/format.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { formatDateTime, formatUsd } from './format'

describe('formatUsd', () => {
  it('formats catalogue prices as USD', () => {
    expect(formatUsd(49)).toBe('$49.00')
    expect(formatUsd(1299.5)).toBe('$1,299.50')
    expect(formatUsd(0)).toBe('$0.00')
  })
})

describe('formatDateTime', () => {
  it('renders an ISO instant in a readable form', () => {
    expect(formatDateTime('2026-08-17T09:30:00Z')).toMatch(/Aug 17, 2026/)
  })
})
```

`web/apps/admin/src/routes/NotFoundPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../test/render'
import { NotFoundPage } from './NotFoundPage'

describe('NotFoundPage', () => {
  it('explains the miss and offers a way back to the catalogue', () => {
    renderWithProviders(<NotFoundPage />, { route: '/nope' })

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to products' })).toHaveAttribute('href', '/products')
  })
})
```

- [ ] **Step 5: Run them to verify they fail**

```bash
cd web/apps/admin
pnpm test
```

Expected: FAIL — `Failed to resolve import "./format"` and `"../test/render"`.

- [ ] **Step 6: Write the test harness**

`web/apps/admin/src/test/msw.ts`:

```ts
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

/** Matches the absolute base URL the test setup gives the api-client. */
export const API = 'http://backend.test'

export const server = setupServer()

export { http, HttpResponse }

/** An RFC 7807 body shaped exactly like the backend's, for error-path tests. */
export function problemResponse(
  status: number,
  body: { title?: string; detail?: string; errors?: { field: string; message: string }[] },
): HttpResponse {
  return HttpResponse.json({ status, ...body }, { status })
}
```

`web/apps/admin/src/test/setup.ts`:

```ts
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
```

`web/apps/admin/src/test/render.tsx`:

```tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, type RenderResult } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'

export type RenderOptions = {
  /** The entry URL, including any search string the screen reads. */
  route?: string
  /** The route pattern the component sits at, so `useParams` resolves. */
  path?: string
  /** Extra routes, so a test can assert where a redirect landed. */
  extraRoutes?: { path: string; element: ReactElement }[]
}

export function renderWithProviders(
  ui: ReactElement,
  options: RenderOptions = {},
): RenderResult & { queryClient: QueryClient } {
  const { route = '/', path = '*', extraRoutes = [] } = options
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
      mutations: { retry: false },
    },
  })

  const result = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path={path} element={ui} />
          {extraRoutes.map((extra) => (
            <Route key={extra.path} path={extra.path} element={extra.element} />
          ))}
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )

  return { ...result, queryClient }
}
```

- [ ] **Step 7: Write the app entry, router and formatters**

`web/apps/admin/src/index.css`:

```css
@import "tailwindcss";
```

`web/apps/admin/src/lib/format.ts`:

```ts
const usdFormatter = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })
const dateTimeFormatter = new Intl.DateTimeFormat('en-US', {
  dateStyle: 'medium',
  timeStyle: 'short',
})

/**
 * Catalogue prices carry no currency of their own; USD is a hardcoded constant on the order
 * side (OrderService.java:54-57). There is deliberately no currency parameter here.
 */
export function formatUsd(amount: number): string {
  return usdFormatter.format(amount)
}

export function formatDateTime(iso: string): string {
  return dateTimeFormatter.format(new Date(iso))
}
```

`web/apps/admin/src/lib/queryClient.ts`:

```ts
import { isApiError } from '@shopflow/api-client'
import { QueryClient } from '@tanstack/react-query'

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        // Retry only what a retry can fix. A 400 or 404 is settled; status 0 is a NetworkError.
        retry: (failureCount, error) =>
          failureCount < 2 && isApiError(error) && (error.status === 0 || error.status >= 500),
      },
      mutations: { retry: false },
    },
  })
}
```

`web/apps/admin/src/routes/NotFoundPage.tsx`:

```tsx
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
```

`web/apps/admin/src/routes/router.tsx`:

```tsx
import { createBrowserRouter, Navigate } from 'react-router'
import { NotFoundPage } from './NotFoundPage'

/** Later tasks add /login, /products, /products/new, /products/:id and /categories. */
export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/products" replace /> },
  { path: '*', element: <NotFoundPage /> },
])
```

`web/apps/admin/src/App.tsx`:

```tsx
import { QueryClientProvider } from '@tanstack/react-query'
import type { ReactElement } from 'react'
import { RouterProvider } from 'react-router'
import { createQueryClient } from './lib/queryClient'
import { router } from './routes/router'

const queryClient = createQueryClient()

export function App(): ReactElement {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  )
}
```

`web/apps/admin/src/main.tsx`:

```tsx
import { restoreSession } from '@shopflow/api-client'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './index.css'

const rootElement = document.getElementById('root')
if (rootElement === null) throw new Error('#root is missing from index.html')

// A stored refresh token with no access token means the page was reloaded. Exchanging it
// before the first render is what keeps an admin signed in across a refresh, and it costs one
// request only when a session is actually there to restore.
await restoreSession()

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```

- [ ] **Step 8: Run the tests to verify they pass**

```bash
cd web/apps/admin
pnpm test
pnpm typecheck
```

Expected: PASS, 3 tests; typecheck silent.

- [ ] **Step 9: Verify the dev proxy actually reaches the backend**

This is the step that proves the CORS workaround. The backend from Task 1 must be running.

```bash
cd web/apps/admin
pnpm dev &
sleep 4
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:5173/
curl -s http://localhost:5173/api/categories
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:5173/auth/login \
  -H 'Content-Type: application/json' -d '{"email":"nobody@example.com","password":"wrong"}'
```

Expected: `200` for the app shell, `[]` from `/api/categories` (the catalogue is still empty), and `401` from `/auth/login` — a 404 there would mean the `/auth` proxy rule is missing. Stop the dev server afterwards with `kill %1`.

- [ ] **Step 10: Verify the build works**

```bash
cd web/apps/admin
pnpm build
```

Expected: a `dist/` directory. If Vite complains about top-level `await` in `main.tsx`, the browser target is too low — it must stay at Vite 8's default (`baseline-widely-available`), which supports it.

- [ ] **Step 11: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add web/apps/admin web/pnpm-lock.yaml
git commit -m "feat(admin): app scaffold with the dev proxy that closes the CORS gap"
```

---

### Task 5: Shared primitives — buttons, fields, query states, dialogs, slug, form errors

Every screen from Task 6 onward is assembled from these. Building them once, with tests, is what keeps the three explicit query states and the 44px touch targets from being re-invented differently on each page. This task adds `react-hook-form`, `zod` and `@hookform/resolvers`, because `applyApiErrorToForm` is tested against a real form.

**Files:**
- Modify: `web/apps/admin/package.json` (add form dependencies)
- Create: `web/apps/admin/src/lib/slug.ts`
- Create: `web/apps/admin/src/lib/errors.ts`
- Create: `web/apps/admin/src/lib/formErrors.ts`
- Create: `web/apps/admin/src/components/Button.tsx`
- Create: `web/apps/admin/src/components/Field.tsx`
- Create: `web/apps/admin/src/components/Badge.tsx`
- Create: `web/apps/admin/src/components/QueryStates.tsx`
- Create: `web/apps/admin/src/components/Dialog.tsx`
- Create: `web/apps/admin/src/components/ConfirmDialog.tsx`
- Create: `web/apps/admin/src/components/Toast.tsx`
- Modify: `web/apps/admin/src/test/render.tsx` (wrap the tree in `ToastProvider`)
- Test: `web/apps/admin/src/lib/slug.test.ts`
- Test: `web/apps/admin/src/lib/formErrors.test.tsx`
- Test: `web/apps/admin/src/components/QueryStates.test.tsx`
- Test: `web/apps/admin/src/components/Dialog.test.tsx`

**Interfaces:**
- Consumes: `isApiError`, `ApiError` from `@shopflow/api-client`; `renderWithProviders` from Task 4.
- Produces:
  - `function toCode(name: string): string` and `const CODE_PATTERN: RegExp`
  - `function describeError(error: unknown): { heading: string; body: string; retryable: boolean }`
  - `function applyApiErrorToForm<T extends FieldValues>(error: unknown, setError: UseFormSetError<T>, knownFields: readonly Path<T>[]): string | null` — `null` means every message landed on an input
  - `function Button(props: ButtonProps)` where `ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary'|'secondary'|'danger'|'ghost'; loading?: boolean }`
  - `function Field(props: { label: string; htmlFor: string; error?: string; hint?: ReactNode; required?: boolean; children: ReactNode })`
  - `function TextInput(props: InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean })`
  - `const inputClass: string` for `<select>` and `<textarea>` to match
  - `function Badge(props: { tone?: 'neutral'|'warning'|'danger'|'success'; children: ReactNode })`
  - `function Skeleton(props: { rows?: number; label?: string })`
  - `function ErrorPanel(props: { error: unknown; onRetry?: () => void })`
  - `function EmptyState(props: { title: string; description: string; action?: ReactNode })`
  - `function Dialog(props: { open: boolean; title: string; onClose: () => void; children: ReactNode; footer?: ReactNode })`
  - `function ConfirmDialog(props: { open: boolean; title: string; confirmLabel: string; destructive?: boolean; busy?: boolean; error?: string | null; onConfirm: () => void; onCancel: () => void; children: ReactNode })`
  - `function ToastProvider(props: { children: ReactNode })` and `function useToast(): (message: string) => void`

- [ ] **Step 1: Add the form dependencies**

Add to `dependencies` in `web/apps/admin/package.json`:

```json
    "@hookform/resolvers": "^5.9.1",
    "react-hook-form": "^7.85.0",
    "zod": "^4.4.3"
```

Then:

```bash
cd web
pnpm install
```

- [ ] **Step 2: Write the failing slug and form-error tests**

`web/apps/admin/src/lib/slug.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { CODE_PATTERN, toCode } from './slug'

describe('toCode', () => {
  it('produces the lower-case kebab-case code the backend pattern demands', () => {
    expect(toCode('Abaya')).toBe('abaya')
    expect(toCode('Hijabs')).toBe('hijabs')
    expect(toCode('Accessories')).toBe('accessories')
    expect(toCode('Head Scarves & Wraps')).toBe('head-scarves-wraps')
    expect(toCode('  Prayer   Sets  ')).toBe('prayer-sets')
    expect(toCode('Abaya_Open')).toBe('abaya-open')
  })

  it('strips diacritics rather than emitting characters the pattern rejects', () => {
    expect(toCode('Abayāt')).toBe('abayat')
  })

  it('returns an empty string when nothing usable is left', () => {
    expect(toCode('   ')).toBe('')
    expect(toCode('***')).toBe('')
  })

  it('truncates to 100 characters without leaving a trailing hyphen', () => {
    const code = toCode('a'.repeat(60) + ' ' + 'b'.repeat(60))
    expect(code.length).toBeLessThanOrEqual(100)
    expect(code.endsWith('-')).toBe(false)
    expect(CODE_PATTERN.test(code)).toBe(true)
  })

  it('every generated code satisfies CODE_PATTERN', () => {
    for (const name of ['Abaya', 'Head Scarves & Wraps', 'Abaya_Open', '  Prayer   Sets  ']) {
      expect(CODE_PATTERN.test(toCode(name))).toBe(true)
    }
  })
})
```

`web/apps/admin/src/lib/formErrors.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '@shopflow/api-client'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { describe, expect, it } from 'vitest'
import { applyApiErrorToForm } from './formErrors'

type Values = { name: string; code: string }
const KNOWN = ['name', 'code'] as const

function Harness({ error }: { error: unknown }) {
  const { setError, formState } = useForm<Values>({ defaultValues: { name: '', code: '' } })
  const [banner, setBanner] = useState<string | null>('untouched')

  return (
    <div>
      <button type="button" onClick={() => setBanner(applyApiErrorToForm<Values>(error, setError, KNOWN))}>
        apply
      </button>
      <p data-testid="banner">{banner ?? 'no-banner'}</p>
      <p data-testid="name-error">{formState.errors.name?.message ?? ''}</p>
      <p data-testid="code-error">{formState.errors.code?.message ?? ''}</p>
    </div>
  )
}

describe('applyApiErrorToForm', () => {
  it('places each server field message on its own input and shows no banner', async () => {
    render(
      <Harness
        error={
          new ApiError(400, {
            detail: 'Validation failed',
            errors: [
              { field: 'code', message: 'must be lower-case letters, digits and single hyphens' },
              { field: 'name', message: 'must not be blank' },
            ],
          })
        }
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'apply' }))

    expect(screen.getByTestId('code-error')).toHaveTextContent(
      'must be lower-case letters, digits and single hyphens',
    )
    expect(screen.getByTestId('name-error')).toHaveTextContent('must not be blank')
    expect(screen.getByTestId('banner')).toHaveTextContent('no-banner')
  })

  it('keeps a message the form has no input for, rather than dropping it', async () => {
    render(
      <Harness
        error={new ApiError(400, { detail: 'Validation failed', errors: [{ field: 'sku', message: 'is unknown' }] })}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'apply' }))

    expect(screen.getByTestId('banner')).toHaveTextContent('Validation failed')
    expect(screen.getByTestId('banner')).toHaveTextContent('sku: is unknown')
  })

  it('falls back to detail for an error with no field messages', async () => {
    render(<Harness error={new ApiError(409, { title: 'Conflict', detail: 'Category code already exists' })} />)

    await userEvent.click(screen.getByRole('button', { name: 'apply' }))

    expect(screen.getByTestId('banner')).toHaveTextContent('Category code already exists')
  })

  it('reports something generic for a non-ApiError', async () => {
    render(<Harness error={new Error('boom')} />)

    await userEvent.click(screen.getByRole('button', { name: 'apply' }))

    expect(screen.getByTestId('banner')).toHaveTextContent('An unexpected error occurred')
  })
})
```

- [ ] **Step 3: Run them to verify they fail**

```bash
cd web/apps/admin
pnpm test slug formErrors
```

Expected: FAIL — `Failed to resolve import "./slug"` and `"./formErrors"`.

- [ ] **Step 4: Write the slug and error helpers**

`web/apps/admin/src/lib/slug.ts`:

```ts
/** The backend's constraint on category and category-type codes, copied verbatim. */
export const CODE_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/

const MAX_CODE_LENGTH = 100

/**
 * Derives a code from a name. Codes must match CODE_PATTERN and are at most 100 characters,
 * so an upper-cased or underscored code is a 400 rather than a cosmetic difference. Diacritics
 * are folded rather than stripped to nothing, so "Abayāt" stays recognisable as "abayat".
 */
export function toCode(name: string): string {
  return name
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+/, '')
    .slice(0, MAX_CODE_LENGTH)
    .replace(/-+$/, '')
}
```

`web/apps/admin/src/lib/errors.ts`:

```ts
import { isApiError } from '@shopflow/api-client'

export type ErrorDescription = {
  heading: string
  body: string
  /** Whether offering Retry makes sense. A 403 or a 400 will answer the same way again. */
  retryable: boolean
}

export function describeError(error: unknown): ErrorDescription {
  if (!isApiError(error)) {
    return {
      heading: 'Something went wrong',
      body: 'An unexpected error occurred. Please try again.',
      retryable: true,
    }
  }

  if (error.status === 0) {
    return {
      heading: 'Cannot reach the server',
      body: error.detail ?? 'Check your connection and try again.',
      retryable: true,
    }
  }
  if (error.status === 403) {
    return {
      heading: 'Not permitted',
      body: 'You do not have permission for this action.',
      retryable: false,
    }
  }
  if (error.status === 404) {
    return {
      heading: 'Not found',
      body: error.detail ?? 'That record no longer exists.',
      retryable: false,
    }
  }
  if (error.status === 409) {
    // Conflicts here are meaningful — a duplicate code, stock that would go negative — so the
    // server's own wording is better than anything generic.
    return {
      heading: 'Conflict',
      body: error.detail ?? error.title ?? 'That change conflicts with the current state.',
      retryable: false,
    }
  }
  if (error.status >= 500) {
    return {
      heading: 'The server failed',
      body: error.detail ?? 'Something broke on the server. Try again in a moment.',
      retryable: true,
    }
  }
  return {
    heading: error.title ?? 'Request failed',
    body: error.detail ?? 'Check the values and try again.',
    retryable: false,
  }
}
```

`web/apps/admin/src/lib/formErrors.ts`:

```ts
import { isApiError } from '@shopflow/api-client'
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'

/**
 * Merges server-side validation into a form instead of replacing it. Each ProblemDetail entry
 * whose field the form owns lands on that input; anything else, plus the problem's `detail`,
 * comes back as a form-level message. Returns null when every message found an input, because
 * a banner repeating them would be noise.
 */
export function applyApiErrorToForm<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  knownFields: readonly Path<T>[],
): string | null {
  if (!isApiError(error)) return 'An unexpected error occurred. Please try again.'

  const unmatched: string[] = []
  for (const fieldError of error.fieldErrors) {
    const path = fieldError.field as Path<T>
    if (knownFields.includes(path)) {
      setError(path, { type: 'server', message: fieldError.message })
    } else {
      unmatched.push(`${fieldError.field}: ${fieldError.message}`)
    }
  }

  if (error.fieldErrors.length > 0 && unmatched.length === 0) return null

  const summary = error.detail ?? error.title ?? `Request failed with status ${error.status}`
  return [summary, ...unmatched].join(' ')
}
```

- [ ] **Step 5: Run them to verify they pass**

```bash
cd web/apps/admin
pnpm test slug formErrors
```

Expected: PASS, 9 tests.

- [ ] **Step 6: Write the failing component tests**

`web/apps/admin/src/components/QueryStates.test.tsx`:

```tsx
import { ApiError, NetworkError } from '@shopflow/api-client'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { EmptyState, ErrorPanel, Skeleton } from './QueryStates'

describe('Skeleton', () => {
  it('announces that something is loading', () => {
    render(<Skeleton rows={2} label="Loading products" />)

    expect(screen.getByRole('status')).toHaveTextContent('Loading products')
  })
})

describe('ErrorPanel', () => {
  it('offers Retry for a server failure', async () => {
    const onRetry = vi.fn()
    render(<ErrorPanel error={new ApiError(500, { detail: 'Internal error' })} onRetry={onRetry} />)

    expect(screen.getByRole('heading', { name: 'The server failed' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('words a network failure differently from a server failure', () => {
    render(<ErrorPanel error={new NetworkError(new TypeError('Failed to fetch'))} />)

    expect(screen.getByRole('heading', { name: 'Cannot reach the server' })).toBeInTheDocument()
  })

  it('does not offer Retry for a 403, which would answer the same way', () => {
    render(<ErrorPanel error={new ApiError(403, { title: 'Forbidden' })} onRetry={vi.fn()} />)

    expect(screen.getByText('You do not have permission for this action.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
  })
})

describe('EmptyState', () => {
  it('shows a heading, an explanation and the primary action', () => {
    render(
      <EmptyState
        title="No categories yet"
        description="A product needs a category and a type before it can exist."
        action={<button type="button">Create your first category</button>}
      />,
    )

    expect(screen.getByRole('heading', { name: 'No categories yet' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create your first category' })).toBeInTheDocument()
  })
})
```

`web/apps/admin/src/components/Dialog.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'
import { Dialog } from './Dialog'

describe('Dialog', () => {
  it('renders nothing while closed', () => {
    render(
      <Dialog open={false} title="Edit category" onClose={vi.fn()}>
        body
      </Dialog>,
    )

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('exposes a labelled modal and closes on Escape and on the close button', async () => {
    const onClose = vi.fn()
    render(
      <Dialog open title="Edit category" onClose={onClose}>
        body
      </Dialog>,
    )

    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleName('Edit category')

    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })
})

describe('ConfirmDialog', () => {
  it('runs the confirm action and surfaces a failure without closing', async () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(
      <ConfirmDialog
        open
        title="Delete Abaya?"
        confirmLabel="Delete category"
        destructive
        error="Category is in use"
        onConfirm={onConfirm}
        onCancel={onCancel}
      >
        This deletes 1 type with it. This cannot be undone.
      </ConfirmDialog>,
    )

    expect(screen.getByText('This deletes 1 type with it. This cannot be undone.')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Category is in use')

    await userEvent.click(screen.getByRole('button', { name: 'Delete category' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('blocks the confirm button while busy', () => {
    render(
      <ConfirmDialog open busy title="Delete Abaya?" confirmLabel="Delete category" onConfirm={vi.fn()} onCancel={vi.fn()}>
        body
      </ConfirmDialog>,
    )

    expect(screen.getByRole('button', { name: 'Delete category' })).toBeDisabled()
  })
})
```

- [ ] **Step 7: Run them to verify they fail**

```bash
cd web/apps/admin
pnpm test QueryStates Dialog
```

Expected: FAIL — unresolved imports for `./QueryStates`, `./Dialog`, `./ConfirmDialog`.

- [ ] **Step 8: Write the primitives**

`web/apps/admin/src/components/Button.tsx`:

```tsx
import type { ButtonHTMLAttributes, ReactElement } from 'react'

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost'

const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary: 'bg-slate-900 text-white hover:bg-slate-800 disabled:bg-slate-400',
  secondary: 'border border-slate-300 bg-white text-slate-900 hover:bg-slate-50 disabled:text-slate-400',
  danger: 'bg-red-600 text-white hover:bg-red-700 disabled:bg-red-300',
  ghost: 'text-slate-700 hover:bg-slate-100 disabled:text-slate-400',
}

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant
  loading?: boolean
}

export function Button({
  variant = 'primary',
  loading = false,
  className = '',
  disabled = false,
  children,
  ...rest
}: ButtonProps): ReactElement {
  return (
    <button
      // Defaulted before ...rest so a caller can still pass type="submit".
      type="button"
      // min-h-11 is the 44px minimum touch target the responsive rules require.
      className={`inline-flex min-h-11 items-center justify-center gap-2 rounded-md px-4 text-sm font-medium transition disabled:cursor-not-allowed ${VARIANT_CLASS[variant]} ${className}`}
      disabled={disabled || loading}
      aria-busy={loading}
      {...rest}
    >
      {children}
    </button>
  )
}
```

`web/apps/admin/src/components/Field.tsx`:

```tsx
import type { InputHTMLAttributes, ReactElement, ReactNode } from 'react'

/** Shared with <select> and <textarea> so every control lines up and clears 44px. */
export const inputClass =
  'min-h-11 w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-slate-900 focus:outline-none disabled:bg-slate-100 disabled:text-slate-500'

export type FieldProps = {
  label: string
  /** The id of the control inside, so the label and the error are wired to it. */
  htmlFor: string
  error?: string
  hint?: ReactNode
  required?: boolean
  children: ReactNode
}

export function Field({ label, htmlFor, error, hint, required = false, children }: FieldProps): ReactElement {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={htmlFor} className="text-sm font-medium text-slate-800">
        {label}
        {required ? <span className="text-red-600"> *</span> : null}
      </label>
      {children}
      {hint !== undefined ? (
        <p id={`${htmlFor}-hint`} className="text-xs text-slate-500">
          {hint}
        </p>
      ) : null}
      {error !== undefined ? (
        <p id={`${htmlFor}-error`} role="alert" className="text-sm text-red-700">
          {error}
        </p>
      ) : null}
    </div>
  )
}

export type TextInputProps = InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }

export function TextInput({ invalid = false, className = '', ...rest }: TextInputProps): ReactElement {
  return (
    <input
      className={`${inputClass} ${invalid ? 'border-red-500' : ''} ${className}`}
      aria-invalid={invalid}
      {...rest}
    />
  )
}
```

`web/apps/admin/src/components/Badge.tsx`:

```tsx
import type { ReactElement, ReactNode } from 'react'

export type BadgeTone = 'neutral' | 'warning' | 'danger' | 'success'

const TONE_CLASS: Record<BadgeTone, string> = {
  neutral: 'bg-slate-100 text-slate-700',
  warning: 'bg-amber-100 text-amber-800',
  danger: 'bg-red-100 text-red-800',
  success: 'bg-emerald-100 text-emerald-800',
}

export function Badge({
  tone = 'neutral',
  children,
}: {
  tone?: BadgeTone
  children: ReactNode
}): ReactElement {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${TONE_CLASS[tone]}`}
    >
      {children}
    </span>
  )
}
```

`web/apps/admin/src/components/QueryStates.tsx`:

```tsx
import type { ReactElement, ReactNode } from 'react'
import { describeError } from '../lib/errors'
import { Button } from './Button'

export function Skeleton({ rows = 3, label = 'Loading' }: { rows?: number; label?: string }): ReactElement {
  return (
    <div role="status" className="flex flex-col gap-3" aria-live="polite">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="h-16 animate-pulse rounded-lg bg-slate-200" />
      ))}
    </div>
  )
}

export function ErrorPanel({ error, onRetry }: { error: unknown; onRetry?: () => void }): ReactElement {
  const { heading, body, retryable } = describeError(error)

  return (
    <div className="rounded-lg border border-red-200 bg-red-50 p-4">
      <h2 className="text-base font-semibold text-red-900">{heading}</h2>
      <p className="mt-1 text-sm text-red-800">{body}</p>
      {retryable && onRetry !== undefined ? (
        <Button variant="secondary" className="mt-3" onClick={onRetry}>
          Retry
        </Button>
      ) : null}
    </div>
  )
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description: string
  action?: ReactNode
}): ReactElement {
  return (
    <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-slate-300 bg-white p-8 text-center">
      <h2 className="text-lg font-semibold text-slate-900">{title}</h2>
      <p className="max-w-sm text-sm text-slate-600">{description}</p>
      {action}
    </div>
  )
}
```

`web/apps/admin/src/components/Dialog.tsx`:

```tsx
import { useEffect, useId, useRef, type ReactElement, type ReactNode } from 'react'
import { Button } from './Button'

export type DialogProps = {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
}

/**
 * A full-width sheet anchored to the bottom on mobile and a centred modal from `md` up — the
 * same component, so the two presentations cannot drift apart.
 */
export function Dialog({ open, title, onClose, children, footer }: DialogProps): ReactElement | null {
  const titleId = useId()
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    panelRef.current?.focus()
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open, onClose])

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-slate-900/50 p-0 md:items-center md:p-4"
      onClick={onClose}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onClick={(event) => event.stopPropagation()}
        className="flex max-h-dvh w-full flex-col overflow-y-auto rounded-t-2xl bg-white p-5 outline-none md:max-h-[85vh] md:max-w-lg md:rounded-2xl"
      >
        <div className="flex items-start justify-between gap-4">
          <h2 id={titleId} className="text-lg font-semibold text-slate-900">
            {title}
          </h2>
          <Button variant="ghost" aria-label="Close" className="px-3" onClick={onClose}>
            <span aria-hidden="true">✕</span>
          </Button>
        </div>
        <div className="mt-4 flex-1">{children}</div>
        {footer !== undefined ? (
          <div className="mt-5 flex flex-col-reverse gap-2 md:flex-row md:justify-end">{footer}</div>
        ) : null}
      </div>
    </div>
  )
}
```

`web/apps/admin/src/components/ConfirmDialog.tsx`:

```tsx
import type { ReactElement, ReactNode } from 'react'
import { Button } from './Button'
import { Dialog } from './Dialog'

export type ConfirmDialogProps = {
  open: boolean
  title: string
  confirmLabel: string
  destructive?: boolean
  busy?: boolean
  /** A failed confirmation keeps the dialog open and shows this. */
  error?: string | null
  onConfirm: () => void
  onCancel: () => void
  children: ReactNode
}

export function ConfirmDialog({
  open,
  title,
  confirmLabel,
  destructive = false,
  busy = false,
  error = null,
  onConfirm,
  onCancel,
  children,
}: ConfirmDialogProps): ReactElement | null {
  return (
    <Dialog
      open={open}
      title={title}
      onClose={onCancel}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button variant={destructive ? 'danger' : 'primary'} onClick={onConfirm} loading={busy}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3 text-sm text-slate-700">
        <p>{children}</p>
        {error !== null ? (
          <p role="alert" className="rounded-md bg-red-50 p-3 text-red-800">
            {error}
          </p>
        ) : null}
      </div>
    </Dialog>
  )
}
```

`web/apps/admin/src/components/Toast.tsx`:

```tsx
import { createContext, useCallback, useContext, useRef, useState, type ReactElement, type ReactNode } from 'react'

type ToastEntry = { id: number; message: string }

const ToastContext = createContext<((message: string) => void) | null>(null)

const VISIBLE_MS = 4000

export function ToastProvider({ children }: { children: ReactNode }): ReactElement {
  const [toasts, setToasts] = useState<ToastEntry[]>([])
  const nextId = useRef(0)

  const showToast = useCallback((message: string) => {
    const id = nextId.current++
    setToasts((current) => [...current, { id, message }])
    setTimeout(() => setToasts((current) => current.filter((toast) => toast.id !== id)), VISIBLE_MS)
  }, [])

  return (
    <ToastContext.Provider value={showToast}>
      {children}
      <div
        role="status"
        aria-live="polite"
        className="pointer-events-none fixed inset-x-4 bottom-4 z-[60] flex flex-col gap-2 md:left-auto md:right-6 md:w-80"
      >
        {toasts.map((toast) => (
          <p key={toast.id} className="rounded-md bg-slate-900 px-4 py-3 text-sm text-white shadow-lg">
            {toast.message}
          </p>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

/** Brief confirmation after a successful mutation. Errors get panels, not toasts. */
export function useToast(): (message: string) => void {
  const showToast = useContext(ToastContext)
  if (showToast === null) throw new Error('useToast must be used inside a ToastProvider')
  return showToast
}
```

- [ ] **Step 9: Put the toast provider into the test harness**

`useToast` throws outside a `ToastProvider`, and from Task 7 on nearly every screen under test calls it. Wrap it once in the shared render helper rather than in each test.

In `web/apps/admin/src/test/render.tsx`, add the import:

```tsx
import { ToastProvider } from '../components/Toast'
```

and wrap the router, so the whole tree matches what `App` provides in production:

```tsx
  const result = render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route path={path} element={ui} />
            {extraRoutes.map((extra) => (
              <Route key={extra.path} path={extra.path} element={extra.element} />
            ))}
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
```

- [ ] **Step 10: Run the tests to verify they pass**

```bash
cd web/apps/admin
pnpm test
pnpm typecheck
```

Expected: PASS, 16 tests across six files; typecheck silent.

- [ ] **Step 11: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add web/apps/admin web/pnpm-lock.yaml
git commit -m "feat(admin): shared primitives for query states, dialogs and form errors"
```

---

### Task 6: Login, session, the admin gate and the responsive shell

Delivers acceptance criteria 1 and 2. After this task you can sign in, see the shell on a phone and a laptop, and sign out.

**Files:**
- Create: `web/apps/admin/src/features/auth/api.ts`
- Create: `web/apps/admin/src/features/auth/session.ts`
- Create: `web/apps/admin/src/features/auth/RequireAdmin.tsx`
- Create: `web/apps/admin/src/features/auth/LoginPage.tsx`
- Create: `web/apps/admin/src/routes/AdminLayout.tsx`
- Modify: `web/apps/admin/src/routes/router.tsx`
- Modify: `web/apps/admin/src/App.tsx`
- Test: `web/apps/admin/src/features/auth/LoginPage.test.tsx`
- Test: `web/apps/admin/src/features/auth/RequireAdmin.test.tsx`

**Interfaces:**
- Consumes: `request`, `setTokens`, `clearTokens`, `getAccessToken`, `getRefreshToken`, `AUTH_EXPIRED_EVENT`, `isApiError`, `LoginRequest`, `TokenPair`, `UserProfile` from `@shopflow/api-client`; `Button`, `Field`, `TextInput`, `ErrorPanel`, `ToastProvider` from Task 5; `renderWithProviders`, `server`, `http`, `HttpResponse`, `API`, `problemResponse` from Task 4.
- Produces:
  - `function login(body: LoginRequest): Promise<TokenPair>`, `function fetchMe(): Promise<UserProfile>`, `function logout(refreshToken: string): Promise<void>`
  - `const SESSION_QUERY_KEY = ['me']`
  - `function hasStoredSession(): boolean`
  - `function useSession(): UseQueryResult<UserProfile>`
  - `class NotAnAdminError extends Error`
  - `function useLogin(): UseMutationResult<UserProfile, unknown, LoginRequest>`
  - `function useLogout(): UseMutationResult<void, unknown, void>`
  - `function useAuthExpiredRedirect(): void`
  - `function RequireAdmin(props: { children: ReactNode }): ReactElement`
  - `function LoginPage(): ReactElement`
  - `function AdminLayout(): ReactElement` — renders `<Outlet />` inside the sidebar/drawer chrome

- [ ] **Step 1: Write the failing tests**

`web/apps/admin/src/features/auth/LoginPage.test.tsx`:

```tsx
import { clearTokens, getAccessToken, getRefreshToken } from '@shopflow/api-client'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { LoginPage } from './LoginPage'

const TOKEN_PAIR = { accessToken: 'access-1', refreshToken: 'refresh-1', tokenType: 'Bearer', expiresIn: 900 }

const ADMIN = {
  id: '11111111-1111-1111-1111-111111111111',
  email: 'admin@shopflow.local',
  fullName: 'Administrator',
  emailVerified: true,
  roles: ['ADMIN'],
  createdAt: '2026-08-17T09:00:00Z',
}

const CUSTOMER = { ...ADMIN, id: '22222222-2222-2222-2222-222222222222', email: 'shopper@example.com', roles: ['CUSTOMER'] }

function renderLogin() {
  return renderWithProviders(<LoginPage />, {
    route: '/login',
    path: '/login',
    extraRoutes: [{ path: '/products', element: <h1>Products</h1> }],
  })
}

async function submitCredentials(email: string, password: string): Promise<void> {
  await userEvent.type(screen.getByLabelText(/email/i), email)
  await userEvent.type(screen.getByLabelText(/password/i), password)
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
}

beforeEach(() => {
  clearTokens()
  sessionStorage.clear()
})

describe('LoginPage', () => {
  it('signs an admin in and lands on the products list', async () => {
    server.use(
      http.post(`${API}/auth/login`, () => HttpResponse.json(TOKEN_PAIR)),
      http.get(`${API}/api/me`, () => HttpResponse.json(ADMIN)),
    )
    renderLogin()

    await submitCredentials('admin@shopflow.local', 'correct-horse-battery')

    expect(await screen.findByRole('heading', { name: 'Products' })).toBeInTheDocument()
    expect(getAccessToken()).toBe('access-1')
    expect(getRefreshToken()).toBe('refresh-1')
  })

  it('rejects a valid non-admin login with an explanation and keeps no tokens', async () => {
    server.use(
      http.post(`${API}/auth/login`, () => HttpResponse.json(TOKEN_PAIR)),
      http.get(`${API}/api/me`, () => HttpResponse.json(CUSTOMER)),
    )
    renderLogin()

    await submitCredentials('shopper@example.com', 'correct-horse-battery')

    expect(await screen.findByRole('alert')).toHaveTextContent('does not have administrator access')
    expect(screen.queryByRole('heading', { name: 'Products' })).not.toBeInTheDocument()
    await waitFor(() => expect(getAccessToken()).toBeNull())
    expect(getRefreshToken()).toBeNull()
  })

  it('shows the server wording for bad credentials', async () => {
    server.use(
      http.post(`${API}/auth/login`, () =>
        problemResponse(401, { title: 'Unauthorized', detail: 'Invalid email or password' }),
      ),
    )
    renderLogin()

    await submitCredentials('admin@shopflow.local', 'wrong-password-here')

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
    expect(getRefreshToken()).toBeNull()
  })

  it('validates before reaching the network', async () => {
    renderLogin()

    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument()
    expect(screen.getByText('Password is required')).toBeInTheDocument()
  })
})
```

`web/apps/admin/src/features/auth/RequireAdmin.test.tsx`:

```tsx
import { clearTokens, setTokens } from '@shopflow/api-client'
import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { RequireAdmin } from './RequireAdmin'

const ADMIN = {
  id: '11111111-1111-1111-1111-111111111111',
  email: 'admin@shopflow.local',
  fullName: 'Administrator',
  emailVerified: true,
  roles: ['ADMIN'],
  createdAt: '2026-08-17T09:00:00Z',
}

function renderGate() {
  return renderWithProviders(
    <RequireAdmin>
      <h1>Catalogue</h1>
    </RequireAdmin>,
    {
      route: '/products',
      path: '/products',
      extraRoutes: [{ path: '/login', element: <h1>Sign in</h1> }],
    },
  )
}

beforeEach(() => {
  clearTokens()
  sessionStorage.clear()
})

describe('RequireAdmin', () => {
  it('sends a visitor with no session to the login screen without calling the API', async () => {
    renderGate()

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('shows a loading state and then the protected content for an admin', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    server.use(http.get(`${API}/api/me`, () => HttpResponse.json(ADMIN)))
    renderGate()

    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Catalogue' })).toBeInTheDocument()
  })

  it('explains the refusal for an authenticated non-admin instead of rendering the shell', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    server.use(http.get(`${API}/api/me`, () => HttpResponse.json({ ...ADMIN, roles: ['CUSTOMER'] })))
    renderGate()

    expect(await screen.findByText(/does not have administrator access/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Catalogue' })).not.toBeInTheDocument()
  })

  it('sends an expired session back to login', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    server.use(
      http.get(`${API}/api/me`, () => problemResponse(401, { title: 'Unauthorized' })),
      http.post(`${API}/auth/refresh`, () => problemResponse(401, { title: 'Invalid refresh token' })),
    )
    renderGate()

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('offers Retry when /api/me fails for a reason that is not about auth', async () => {
    setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' })
    server.use(http.get(`${API}/api/me`, () => problemResponse(500, { detail: 'Internal error' })))
    renderGate()

    expect(await screen.findByRole('heading', { name: 'The server failed' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

```bash
cd web/apps/admin
pnpm test auth
```

Expected: FAIL — unresolved imports for `./LoginPage` and `./RequireAdmin`.

- [ ] **Step 3: Write the auth API calls**

`web/apps/admin/src/features/auth/api.ts`:

```ts
import { request, type LoginRequest, type TokenPair, type UserProfile } from '@shopflow/api-client'

/** auth: false — a login must not send, or renew, an existing bearer token. */
export function login(body: LoginRequest): Promise<TokenPair> {
  return request<TokenPair>('/auth/login', { method: 'POST', body, auth: false })
}

export function fetchMe(): Promise<UserProfile> {
  return request<UserProfile>('/api/me')
}

/** Revokes the refresh token server-side. Authenticated, unlike login and refresh. */
export function logout(refreshToken: string): Promise<void> {
  return request<void>('/auth/logout', { method: 'POST', body: { refreshToken } })
}
```

- [ ] **Step 4: Write the session hooks**

`web/apps/admin/src/features/auth/session.ts`:

```ts
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
```

- [ ] **Step 5: Write the gate**

`web/apps/admin/src/features/auth/RequireAdmin.tsx`:

```tsx
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
```

- [ ] **Step 6: Write the login screen**

`web/apps/admin/src/features/auth/LoginPage.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { useLocation, useNavigate } from 'react-router'
import { z } from 'zod'
import { Button } from '../../components/Button'
import { Field, TextInput } from '../../components/Field'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { NotAnAdminError, useLogin } from './session'

const loginSchema = z.object({
  email: z.email('Enter a valid email address').max(255, 'Must be 255 characters or fewer'),
  password: z
    .string()
    .min(1, 'Password is required')
    .max(72, 'Must be 72 characters or fewer'),
})

type LoginValues = z.infer<typeof loginSchema>

const LOGIN_FIELDS = ['email', 'password'] as const

export function LoginPage(): ReactElement {
  const navigate = useNavigate()
  const location = useLocation()
  const [formMessage, setFormMessage] = useState<string | null>(null)
  const signIn = useLogin()

  const { register, handleSubmit, setError, formState } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
  })

  const from = (location.state as { from?: string } | null)?.from ?? '/products'

  const onSubmit = handleSubmit((values) => {
    setFormMessage(null)
    signIn.mutate(values, {
      onSuccess: () => navigate(from, { replace: true }),
      onError: (error) => {
        setFormMessage(
          error instanceof NotAnAdminError
            ? error.message
            : applyApiErrorToForm<LoginValues>(error, setError, LOGIN_FIELDS),
        )
      },
    })
  })

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-sm flex-col justify-center gap-6 p-6">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">ShopFlow Admin</h1>
        <p className="mt-1 text-sm text-slate-600">Sign in to manage the catalogue.</p>
      </div>

      <form className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <Field label="Email" htmlFor="email" required error={formState.errors.email?.message}>
          <TextInput
            id="email"
            type="email"
            autoComplete="username"
            invalid={formState.errors.email !== undefined}
            aria-describedby={formState.errors.email !== undefined ? 'email-error' : undefined}
            {...register('email')}
          />
        </Field>

        <Field label="Password" htmlFor="password" required error={formState.errors.password?.message}>
          <TextInput
            id="password"
            type="password"
            autoComplete="current-password"
            invalid={formState.errors.password !== undefined}
            aria-describedby={formState.errors.password !== undefined ? 'password-error' : undefined}
            {...register('password')}
          />
        </Field>

        {formMessage !== null ? (
          <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
            {formMessage}
          </p>
        ) : null}

        <Button type="submit" loading={signIn.isPending} className="w-full">
          Sign in
        </Button>
      </form>
    </main>
  )
}
```

- [ ] **Step 7: Write the responsive shell**

`web/apps/admin/src/routes/AdminLayout.tsx`:

```tsx
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
```

- [ ] **Step 8: Wire the routes and the toast provider**

`web/apps/admin/src/routes/router.tsx`:

```tsx
import { createBrowserRouter, Navigate } from 'react-router'
import { LoginPage } from '../features/auth/LoginPage'
import { RequireAdmin } from '../features/auth/RequireAdmin'
import { AdminLayout } from './AdminLayout'
import { NotFoundPage } from './NotFoundPage'

/** Later tasks add /products, /products/new, /products/:id and /categories as children. */
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: (
      <RequireAdmin>
        <AdminLayout />
      </RequireAdmin>
    ),
    children: [{ path: '/', element: <Navigate to="/products" replace /> }],
  },
  { path: '*', element: <NotFoundPage /> },
])
```

`web/apps/admin/src/App.tsx`:

```tsx
import { QueryClientProvider } from '@tanstack/react-query'
import type { ReactElement } from 'react'
import { RouterProvider } from 'react-router'
import { ToastProvider } from './components/Toast'
import { createQueryClient } from './lib/queryClient'
import { router } from './routes/router'

const queryClient = createQueryClient()

export function App(): ReactElement {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>
  )
}
```

- [ ] **Step 9: Run the tests to verify they pass**

```bash
cd web/apps/admin
pnpm test
pnpm typecheck
```

Expected: PASS, 25 tests. The non-admin case is acceptance criterion 1.

- [ ] **Step 10: Sign in by hand and confirm the session survives a reload**

With the backend running:

```bash
cd web/apps/admin
pnpm dev
```

Open `http://localhost:5173/login`, sign in with the `ADMIN_EMAIL` from Task 1, and check:

1. You land on `/products`, which shows the not-found page — that route arrives in Task 9.
2. The sidebar shows your email on a wide window; narrowing below 768px swaps it for the top bar and the ☰ drawer.
3. Reloading keeps you signed in (acceptance criterion 2, the `restoreSession()` path in `main.tsx`).
4. In DevTools, Application → Session Storage holds `shopflow.refreshToken` and **no** access token; Local Storage is empty.
5. Sign out returns you to `/login`, and reloading then keeps you there.

- [ ] **Step 11: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git commit -am "feat(admin): login, the ADMIN gate and the responsive shell"
```

---

### Task 7: Categories — list, empty state, create

Delivers acceptance criteria 4 and 5, and the empty state that is the very first screen this app will ever show against a fresh database.

**Files:**
- Create: `web/apps/admin/src/features/categories/api.ts`
- Create: `web/apps/admin/src/features/categories/queries.ts`
- Create: `web/apps/admin/src/features/categories/CategoriesPage.tsx`
- Create: `web/apps/admin/src/features/categories/CategoryFormDialog.tsx`
- Modify: `web/apps/admin/src/routes/router.tsx`
- Test: `web/apps/admin/src/features/categories/CategoriesPage.test.tsx`

**Interfaces:**
- Consumes: `request`, `Category`, `CreateCategoryRequest` from `@shopflow/api-client`; `Button`, `Badge`, `Field`, `TextInput`, `inputClass`, `Dialog`, `Skeleton`, `ErrorPanel`, `EmptyState`, `useToast` from Task 5; `toCode`, `CODE_PATTERN` from Task 5; `applyApiErrorToForm` from Task 5.
- Produces:
  - `function fetchCategories(): Promise<Category[]>`
  - `function createCategory(body: CreateCategoryRequest): Promise<Category>`
  - `const CATEGORIES_QUERY_KEY = ['categories']`
  - `function useCategories(): UseQueryResult<Category[]>`
  - `function useCreateCategory(): UseMutationResult<Category, unknown, CreateCategoryRequest>`
  - `function CategoriesPage(): ReactElement`
  - `function CategoryCard(props: { category: Category }): ReactElement` — exported from `CategoriesPage.tsx`; Task 8 adds its action buttons
  - `function CategoryFormDialog(props: { open: boolean; onClose: () => void }): ReactElement | null` — Task 8 adds an optional `category` prop for edit mode

- [ ] **Step 1: Write the failing test**

`web/apps/admin/src/features/categories/CategoriesPage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { CategoriesPage } from './CategoriesPage'

const ABAYA = {
  id: '33333333-3333-3333-3333-333333333333',
  code: 'abaya',
  name: 'Abaya',
  description: 'Outerwear',
  types: [{ id: '44444444-4444-4444-4444-444444444444', code: 'abaya', name: 'Abaya' }],
}

const TYPELESS = {
  id: '55555555-5555-5555-5555-555555555555',
  code: 'accessories',
  name: 'Accessories',
  types: [],
}

function renderPage() {
  return renderWithProviders(<CategoriesPage />, { route: '/categories', path: '/categories' })
}

describe('CategoriesPage', () => {
  it('offers a first action when the catalogue is empty', async () => {
    server.use(http.get(`${API}/api/categories`, () => HttpResponse.json([])))
    renderPage()

    expect(await screen.findByRole('heading', { name: 'No categories yet' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create your first category' })).toBeInTheDocument()
  })

  it('shows a Retry when the list cannot be loaded', async () => {
    server.use(http.get(`${API}/api/categories`, () => problemResponse(500, { detail: 'Internal error' })))
    renderPage()

    expect(await screen.findByRole('heading', { name: 'The server failed' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('marks a category with no types as unable to hold products', async () => {
    server.use(http.get(`${API}/api/categories`, () => HttpResponse.json([ABAYA, TYPELESS])))
    renderPage()

    const typeless = await screen.findByRole('listitem', { name: 'Accessories' })
    expect(within(typeless).getByText('No types — cannot hold products')).toBeInTheDocument()

    const abaya = screen.getByRole('listitem', { name: 'Abaya' })
    expect(within(abaya).queryByText('No types — cannot hold products')).not.toBeInTheDocument()
    expect(within(abaya).getByText('Abaya (abaya)')).toBeInTheDocument()
  })

  it('derives a lower-case kebab-case code from the name and creates the category', async () => {
    const posted: unknown[] = []
    let created = false
    server.use(
      http.get(`${API}/api/categories`, () => HttpResponse.json(created ? [ABAYA] : [])),
      http.post(`${API}/api/admin/categories`, async ({ request }) => {
        posted.push(await request.json())
        created = true
        return HttpResponse.json(ABAYA, { status: 201 })
      }),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Create your first category' }))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Abaya')

    expect(screen.getByLabelText(/^code/i)).toHaveValue('abaya')

    await userEvent.click(screen.getByRole('button', { name: 'Create category' }))

    await waitFor(() => expect(posted).toEqual([{ name: 'Abaya', code: 'abaya' }]))
    expect(await screen.findByRole('listitem', { name: 'Abaya' })).toBeInTheDocument()
  })

  it('stops editing the code once the operator types their own', async () => {
    server.use(http.get(`${API}/api/categories`, () => HttpResponse.json([])))
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Create your first category' }))
    await userEvent.type(screen.getByLabelText(/^code/i), 'outer-wear')
    await userEvent.type(screen.getByLabelText(/^name/i), 'Abaya')

    expect(screen.getByLabelText(/^code/i)).toHaveValue('outer-wear')
  })

  it('rejects a code the pattern forbids before any request', async () => {
    server.use(http.get(`${API}/api/categories`, () => HttpResponse.json([])))
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Create your first category' }))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Abaya')
    await userEvent.clear(screen.getByLabelText(/^code/i))
    await userEvent.type(screen.getByLabelText(/^code/i), 'ABAYA_1')
    await userEvent.click(screen.getByRole('button', { name: 'Create category' }))

    expect(
      await screen.findByText('Lower-case letters, digits and single hyphens only'),
    ).toBeInTheDocument()
  })

  it('shows a duplicate code on the code field rather than as a bare banner', async () => {
    server.use(
      http.get(`${API}/api/categories`, () => HttpResponse.json([])),
      http.post(`${API}/api/admin/categories`, () =>
        problemResponse(409, { title: 'Conflict', detail: 'A category with code abaya already exists' }),
      ),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Create your first category' }))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Abaya')
    await userEvent.click(screen.getByRole('button', { name: 'Create category' }))

    const codeField = screen.getByLabelText(/^code/i)
    expect(await screen.findByText('A category with code abaya already exists')).toBeInTheDocument()
    expect(codeField).toHaveAttribute('aria-invalid', 'true')
    // The dialog stays open with the values intact so the operator can fix the code.
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByLabelText(/^name/i)).toHaveValue('Abaya')
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd web/apps/admin
pnpm test CategoriesPage
```

Expected: FAIL — `Failed to resolve import "./CategoriesPage"`.

- [ ] **Step 3: Write the API calls and query hooks**

`web/apps/admin/src/features/categories/api.ts`:

```ts
import { request, type Category, type CreateCategoryRequest } from '@shopflow/api-client'

/** The public endpoint: it already nests types one level, which is all this screen needs. */
export function fetchCategories(): Promise<Category[]> {
  return request<Category[]>('/api/categories')
}

export function createCategory(body: CreateCategoryRequest): Promise<Category> {
  return request<Category>('/api/admin/categories', { method: 'POST', body })
}
```

`web/apps/admin/src/features/categories/queries.ts`:

```ts
import type { Category, CreateCategoryRequest } from '@shopflow/api-client'
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { createCategory, fetchCategories } from './api'

export const CATEGORIES_QUERY_KEY = ['categories']

export function useCategories(): UseQueryResult<Category[]> {
  return useQuery({
    queryKey: CATEGORIES_QUERY_KEY,
    queryFn: fetchCategories,
    // Changes rarely and feeds every product form's dropdowns.
    staleTime: 5 * 60_000,
  })
}

/**
 * Invalidate rather than patch: for a catalogue tool the server's truth beats a guess, and the
 * refetch is one small request.
 */
export function useCreateCategory(): UseMutationResult<Category, unknown, CreateCategoryRequest> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: createCategory,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_QUERY_KEY }),
  })
}
```

- [ ] **Step 4: Write the create dialog**

`web/apps/admin/src/features/categories/CategoryFormDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { isApiError } from '@shopflow/api-client'
import { useEffect, useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Field, TextInput, inputClass } from '../../components/Field'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { CODE_PATTERN, toCode } from '../../lib/slug'
import { useCreateCategory } from './queries'

const categorySchema = z.object({
  name: z.string().min(1, 'Name is required').max(255, 'Must be 255 characters or fewer'),
  code: z
    .string()
    .min(1, 'Code is required')
    .max(100, 'Must be 100 characters or fewer')
    .regex(CODE_PATTERN, 'Lower-case letters, digits and single hyphens only'),
  description: z.string().max(2000, 'Must be 2000 characters or fewer'),
})

type CategoryValues = z.infer<typeof categorySchema>

const CATEGORY_FIELDS = ['name', 'code', 'description'] as const

export function CategoryFormDialog({
  open,
  onClose,
}: {
  open: boolean
  onClose: () => void
}): ReactElement | null {
  const [formMessage, setFormMessage] = useState<string | null>(null)
  const [codeEdited, setCodeEdited] = useState(false)
  const create = useCreateCategory()
  const showToast = useToast()

  const { register, handleSubmit, setError, setValue, reset, watch, formState } =
    useForm<CategoryValues>({
      resolver: zodResolver(categorySchema),
      defaultValues: { name: '', code: '', description: '' },
    })

  const name = watch('name')

  // The code tracks the name until the operator takes it over, then it is theirs.
  useEffect(() => {
    if (!codeEdited) setValue('code', toCode(name))
  }, [name, codeEdited, setValue])

  // Reopening starts clean rather than inheriting a failed attempt.
  useEffect(() => {
    if (open) {
      reset({ name: '', code: '', description: '' })
      setCodeEdited(false)
      setFormMessage(null)
    }
  }, [open, reset])

  const codeRegistration = register('code')

  const onSubmit = handleSubmit((values) => {
    setFormMessage(null)
    create.mutate(
      {
        name: values.name,
        code: values.code,
        description: values.description === '' ? undefined : values.description,
      },
      {
        onSuccess: () => {
          showToast(`Category "${values.name}" created`)
          onClose()
        },
        onError: (error) => {
          // `code` is the only unique field on a category, so a 409 can only be about it. The
          // backend sends no errors[] for a conflict, so the mapping happens here.
          if (isApiError(error) && error.status === 409) {
            setError('code', {
              type: 'server',
              message: error.detail ?? 'That code is already used',
            })
            return
          }
          setFormMessage(applyApiErrorToForm<CategoryValues>(error, setError, CATEGORY_FIELDS))
        },
      },
    )
  })

  return (
    <Dialog
      open={open}
      title="New category"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={create.isPending}>
            Cancel
          </Button>
          <Button type="submit" form="category-form" loading={create.isPending}>
            Create category
          </Button>
        </>
      }
    >
      <form id="category-form" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <Field label="Name" htmlFor="category-name" required error={formState.errors.name?.message}>
          <TextInput
            id="category-name"
            invalid={formState.errors.name !== undefined}
            {...register('name')}
          />
        </Field>

        <Field
          label="Code"
          htmlFor="category-code"
          required
          error={formState.errors.code?.message}
          hint="Lower-case letters, digits and single hyphens. Permanent once created."
        >
          <TextInput
            id="category-code"
            invalid={formState.errors.code !== undefined}
            {...codeRegistration}
            onChange={(event) => {
              setCodeEdited(true)
              void codeRegistration.onChange(event)
            }}
          />
        </Field>

        <Field label="Description" htmlFor="category-description" error={formState.errors.description?.message}>
          <textarea
            id="category-description"
            rows={3}
            className={inputClass}
            {...register('description')}
          />
        </Field>

        {formMessage !== null ? (
          <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
            {formMessage}
          </p>
        ) : null}
      </form>
    </Dialog>
  )
}
```

- [ ] **Step 5: Write the page**

`web/apps/admin/src/features/categories/CategoriesPage.tsx`:

```tsx
import type { Category } from '@shopflow/api-client'
import { useState, type ReactElement } from 'react'
import { Badge } from '../../components/Badge'
import { Button } from '../../components/Button'
import { EmptyState, ErrorPanel, Skeleton } from '../../components/QueryStates'
import { CategoryFormDialog } from './CategoryFormDialog'
import { useCategories } from './queries'

export function CategoriesPage(): ReactElement {
  const categories = useCategories()
  const [createOpen, setCreateOpen] = useState(false)

  return (
    <section className="flex flex-col gap-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold text-slate-900">Categories</h1>
        <Button onClick={() => setCreateOpen(true)}>New category</Button>
      </header>

      {categories.isPending ? <Skeleton rows={3} label="Loading categories" /> : null}

      {categories.isError ? (
        <ErrorPanel error={categories.error} onRetry={() => void categories.refetch()} />
      ) : null}

      {categories.isSuccess && categories.data.length === 0 ? (
        <EmptyState
          title="No categories yet"
          description="Every product needs a category and a type, so the catalogue starts here."
          action={<Button onClick={() => setCreateOpen(true)}>Create your first category</Button>}
        />
      ) : null}

      {categories.isSuccess && categories.data.length > 0 ? (
        <ul className="flex flex-col gap-3">
          {categories.data.map((category) => (
            <CategoryCard key={category.id} category={category} />
          ))}
        </ul>
      ) : null}

      <CategoryFormDialog open={createOpen} onClose={() => setCreateOpen(false)} />
    </section>
  )
}

/** Task 8 adds the edit, delete and add-type actions to this card. */
export function CategoryCard({ category }: { category: Category }): ReactElement {
  return (
    <li aria-label={category.name} className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="font-semibold text-slate-900">{category.name}</h2>
          <p className="text-xs text-slate-500">{category.code}</p>
        </div>
        {category.types.length === 0 ? (
          <Badge tone="warning">No types — cannot hold products</Badge>
        ) : null}
      </div>

      {category.description !== undefined ? (
        <p className="mt-2 text-sm text-slate-600">{category.description}</p>
      ) : null}

      {category.types.length > 0 ? (
        <ul className="mt-3 flex flex-wrap gap-2">
          {category.types.map((type) => (
            <li key={type.id}>
              <Badge>{`${type.name} (${type.code})`}</Badge>
            </li>
          ))}
        </ul>
      ) : null}
    </li>
  )
}
```

- [ ] **Step 6: Add the route**

In `web/apps/admin/src/routes/router.tsx`, add the import and the child route:

```tsx
import { CategoriesPage } from '../features/categories/CategoriesPage'
```

```tsx
    children: [
      { path: '/', element: <Navigate to="/products" replace /> },
      { path: '/categories', element: <CategoriesPage /> },
    ],
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
cd web/apps/admin
pnpm test
pnpm typecheck
```

Expected: PASS, 32 tests.

- [ ] **Step 8: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add web/apps/admin
git commit -m "feat(admin): categories list with the typeless warning and a slugged create form"
```

---

### Task 8: Category edit, cascade delete, and category types

Delivers acceptance criterion 11 (the delete confirmation names what the cascade takes and the delete is refused, not silently swallowed, while products remain) and completes the category-type field the spec keeps visible.

Note the asymmetric routing, which is easy to get wrong: a type is **created** under its category (`POST /api/admin/categories/{id}/types`) but **updated and deleted by its own id** (`PATCH`/`DELETE /api/admin/category-types/{id}`). `AdminCategoryController` is the source of truth for this.

`CategoryCard` moves out of `CategoriesPage.tsx` into its own file in this task: it grows three dialogs and per-type actions, and a card that owns its own mutations is easier to hold in your head than a page that owns everything.

**Files:**
- Modify: `web/apps/admin/src/features/categories/api.ts`
- Modify: `web/apps/admin/src/features/categories/queries.ts`
- Create: `web/apps/admin/src/features/categories/CategoryCard.tsx` (moved out of `CategoriesPage.tsx`)
- Modify: `web/apps/admin/src/features/categories/CategoriesPage.tsx`
- Modify: `web/apps/admin/src/features/categories/CategoryFormDialog.tsx`
- Create: `web/apps/admin/src/features/categories/CategoryTypeFormDialog.tsx`
- Test: `web/apps/admin/src/features/categories/CategoryCard.test.tsx`

**Interfaces:**
- Consumes: everything Task 7 produced; `ConfirmDialog` from Task 5; `UpdateCategoryRequest`, `CreateCategoryTypeRequest`, `UpdateCategoryTypeRequest`, `CategoryType` from `@shopflow/api-client`; `describeError` from Task 5.
- Produces:
  - `function updateCategory(id: string, body: UpdateCategoryRequest): Promise<Category>`
  - `function deleteCategory(id: string): Promise<void>`
  - `function createCategoryType(categoryId: string, body: CreateCategoryTypeRequest): Promise<CategoryType>`
  - `function updateCategoryType(typeId: string, body: UpdateCategoryTypeRequest): Promise<CategoryType>`
  - `function deleteCategoryType(typeId: string): Promise<void>`
  - `function useUpdateCategory(): UseMutationResult<Category, unknown, { id: string; body: UpdateCategoryRequest }>`
  - `function useDeleteCategory(): UseMutationResult<void, unknown, string>`
  - `function useCreateCategoryType(): UseMutationResult<CategoryType, unknown, { categoryId: string; body: CreateCategoryTypeRequest }>`
  - `function useUpdateCategoryType(): UseMutationResult<CategoryType, unknown, { typeId: string; body: UpdateCategoryTypeRequest }>`
  - `function useDeleteCategoryType(): UseMutationResult<void, unknown, string>`
  - `function CategoryCard(props: { category: Category }): ReactElement` — now in `CategoryCard.tsx`
  - `function CategoryFormDialog(props: { open: boolean; onClose: () => void; category?: Category }): ReactElement | null`
  - `function CategoryTypeFormDialog(props: { open: boolean; onClose: () => void; categoryId: string; categoryName: string; type?: CategoryType }): ReactElement | null`

- [ ] **Step 1: Write the failing test**

`web/apps/admin/src/features/categories/CategoryCard.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { CategoryCard } from './CategoryCard'

const CATEGORY_ID = '33333333-3333-3333-3333-333333333333'
const TYPE_ID = '44444444-4444-4444-4444-444444444444'

const ABAYA = {
  id: CATEGORY_ID,
  code: 'abaya',
  name: 'Abaya',
  description: 'Outerwear',
  types: [
    { id: TYPE_ID, code: 'abaya', name: 'Abaya', description: 'Everyday' },
    { id: '66666666-6666-6666-6666-666666666666', code: 'kimono', name: 'Kimono' },
  ],
}

/** The card invalidates the list query, so every test needs the list endpoint answered. */
function renderCard(category = ABAYA) {
  server.use(http.get(`${API}/api/categories`, () => HttpResponse.json([category])))
  return renderWithProviders(
    <ul>
      <CategoryCard category={category} />
    </ul>,
    { route: '/categories', path: '/categories' },
  )
}

describe('CategoryCard', () => {
  it('edits the name and description but never the code', async () => {
    const patched: unknown[] = []
    server.use(
      http.patch(`${API}/api/admin/categories/${CATEGORY_ID}`, async ({ request }) => {
        patched.push(await request.json())
        return HttpResponse.json({ ...ABAYA, name: 'Abayas' })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Edit Abaya' }))

    const code = screen.getByLabelText(/^code/i)
    expect(code).toHaveValue('abaya')
    expect(code).toBeDisabled()

    await userEvent.clear(screen.getByLabelText(/^name/i))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Abayas')
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() =>
      expect(patched).toEqual([{ name: 'Abayas', description: 'Outerwear' }]),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('names the types the cascade will take before deleting a category', async () => {
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Delete Abaya' }))

    const dialog = screen.getByRole('dialog')
    expect(
      within(dialog).getByText(
        /Deleting "Abaya" also deletes its 2 types \(Abaya, Kimono\)\. This cannot be undone\./,
      ),
    ).toBeInTheDocument()
  })

  it('says so plainly when a category still has products', async () => {
    server.use(
      http.delete(`${API}/api/admin/categories/${CATEGORY_ID}`, () =>
        problemResponse(409, {
          title: 'Conflict',
          detail: 'Category Abaya still has 3 product(s) and cannot be deleted',
        }),
      ),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Delete Abaya' }))
    await userEvent.click(screen.getByRole('button', { name: 'Delete category' }))

    expect(
      await screen.findByText('Category Abaya still has 3 product(s) and cannot be deleted'),
    ).toBeInTheDocument()
    // Still open, so the operator can read the reason and cancel deliberately.
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('deletes a category the backend accepts', async () => {
    let deleted: string | null = null
    server.use(
      http.delete(`${API}/api/admin/categories/${CATEGORY_ID}`, () => {
        deleted = CATEGORY_ID
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Delete Abaya' }))
    await userEvent.click(screen.getByRole('button', { name: 'Delete category' }))

    await waitFor(() => expect(deleted).toBe(CATEGORY_ID))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('adds a type under its category with a slugged code', async () => {
    const posted: unknown[] = []
    server.use(
      http.post(`${API}/api/admin/categories/${CATEGORY_ID}/types`, async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json({ id: 'new', code: 'open-abaya', name: 'Open Abaya' }, { status: 201 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Add type to Abaya' }))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Open Abaya')

    expect(screen.getByLabelText(/^code/i)).toHaveValue('open-abaya')

    await userEvent.click(screen.getByRole('button', { name: 'Add type' }))

    await waitFor(() => expect(posted).toEqual([{ name: 'Open Abaya', code: 'open-abaya' }]))
  })

  it('puts a duplicate type code on the code field', async () => {
    server.use(
      http.post(`${API}/api/admin/categories/${CATEGORY_ID}/types`, () =>
        problemResponse(409, { title: 'Conflict', detail: 'Type code kimono already exists in this category' }),
      ),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Add type to Abaya' }))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Kimono')
    await userEvent.click(screen.getByRole('button', { name: 'Add type' }))

    expect(
      await screen.findByText('Type code kimono already exists in this category'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText(/^code/i)).toHaveAttribute('aria-invalid', 'true')
  })

  it('deletes a type by its own id, not through its category', async () => {
    const calls: string[] = []
    server.use(
      http.delete(`${API}/api/admin/category-types/${TYPE_ID}`, ({ request }) => {
        calls.push(new URL(request.url).pathname)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Remove type Abaya' }))
    expect(
      screen.getByText(/Remove the type "Abaya" from "Abaya"\? This cannot be undone\./),
    ).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Remove type' }))

    await waitFor(() => expect(calls).toEqual([`/api/admin/category-types/${TYPE_ID}`]))
  })

  it('edits a type by its own id', async () => {
    const patched: unknown[] = []
    server.use(
      http.patch(`${API}/api/admin/category-types/${TYPE_ID}`, async ({ request }) => {
        patched.push(await request.json())
        return HttpResponse.json({ id: TYPE_ID, code: 'abaya', name: 'Classic Abaya' })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Edit type Abaya' }))
    await userEvent.clear(screen.getByLabelText(/^name/i))
    await userEvent.type(screen.getByLabelText(/^name/i), 'Classic Abaya')
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() =>
      expect(patched).toEqual([{ name: 'Classic Abaya', description: 'Everyday' }]),
    )
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd web/apps/admin
pnpm test CategoryCard
```

Expected: FAIL — `Failed to resolve import "./CategoryCard"`.

- [ ] **Step 3: Add the API calls**

Append to `web/apps/admin/src/features/categories/api.ts` and widen the import:

```ts
import {
  request,
  type Category,
  type CategoryType,
  type CreateCategoryRequest,
  type CreateCategoryTypeRequest,
  type UpdateCategoryRequest,
  type UpdateCategoryTypeRequest,
} from '@shopflow/api-client'
```

```ts
export function updateCategory(id: string, body: UpdateCategoryRequest): Promise<Category> {
  return request<Category>(`/api/admin/categories/${id}`, { method: 'PATCH', body })
}

/** 204 on success; 409 while any product still names this category. */
export function deleteCategory(id: string): Promise<void> {
  return request<void>(`/api/admin/categories/${id}`, { method: 'DELETE' })
}

/** Created under the parent category… */
export function createCategoryType(
  categoryId: string,
  body: CreateCategoryTypeRequest,
): Promise<CategoryType> {
  return request<CategoryType>(`/api/admin/categories/${categoryId}/types`, { method: 'POST', body })
}

/** …but updated and deleted by its own id. The routing really is asymmetric. */
export function updateCategoryType(
  typeId: string,
  body: UpdateCategoryTypeRequest,
): Promise<CategoryType> {
  return request<CategoryType>(`/api/admin/category-types/${typeId}`, { method: 'PATCH', body })
}

export function deleteCategoryType(typeId: string): Promise<void> {
  return request<void>(`/api/admin/category-types/${typeId}`, { method: 'DELETE' })
}
```

- [ ] **Step 4: Add the mutation hooks**

Append to `web/apps/admin/src/features/categories/queries.ts` and widen both imports:

```ts
import type {
  Category,
  CategoryType,
  CreateCategoryRequest,
  CreateCategoryTypeRequest,
  UpdateCategoryRequest,
  UpdateCategoryTypeRequest,
} from '@shopflow/api-client'
```

```ts
import {
  createCategory,
  createCategoryType,
  deleteCategory,
  deleteCategoryType,
  fetchCategories,
  updateCategory,
  updateCategoryType,
} from './api'
```

```ts
export function useUpdateCategory(): UseMutationResult<
  Category,
  unknown,
  { id: string; body: UpdateCategoryRequest }
> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ id, body }) => updateCategory(id, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_QUERY_KEY }),
  })
}

export function useDeleteCategory(): UseMutationResult<void, unknown, string> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: deleteCategory,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_QUERY_KEY }),
  })
}

export function useCreateCategoryType(): UseMutationResult<
  CategoryType,
  unknown,
  { categoryId: string; body: CreateCategoryTypeRequest }
> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ categoryId, body }) => createCategoryType(categoryId, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_QUERY_KEY }),
  })
}

export function useUpdateCategoryType(): UseMutationResult<
  CategoryType,
  unknown,
  { typeId: string; body: UpdateCategoryTypeRequest }
> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ typeId, body }) => updateCategoryType(typeId, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_QUERY_KEY }),
  })
}

export function useDeleteCategoryType(): UseMutationResult<void, unknown, string> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: deleteCategoryType,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CATEGORIES_QUERY_KEY }),
  })
}
```

- [ ] **Step 5: Teach the category dialog to edit**

Replace `web/apps/admin/src/features/categories/CategoryFormDialog.tsx` with the version below. The create path is unchanged; edit mode drops `code` from the payload entirely because the backend has no field for it, and shows it disabled so nobody hunts for a way to change it.

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { isApiError, type Category } from '@shopflow/api-client'
import { useEffect, useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Field, TextInput, inputClass } from '../../components/Field'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { CODE_PATTERN, toCode } from '../../lib/slug'
import { useCreateCategory, useUpdateCategory } from './queries'

const categorySchema = z.object({
  name: z.string().min(1, 'Name is required').max(255, 'Must be 255 characters or fewer'),
  code: z
    .string()
    .min(1, 'Code is required')
    .max(100, 'Must be 100 characters or fewer')
    .regex(CODE_PATTERN, 'Lower-case letters, digits and single hyphens only'),
  description: z.string().max(2000, 'Must be 2000 characters or fewer'),
})

type CategoryValues = z.infer<typeof categorySchema>

const CATEGORY_FIELDS = ['name', 'code', 'description'] as const

export function CategoryFormDialog({
  open,
  onClose,
  category,
}: {
  open: boolean
  onClose: () => void
  category?: Category
}): ReactElement | null {
  const editing = category !== undefined
  const [formMessage, setFormMessage] = useState<string | null>(null)
  const [codeEdited, setCodeEdited] = useState(false)
  const create = useCreateCategory()
  const update = useUpdateCategory()
  const showToast = useToast()
  const pending = create.isPending || update.isPending

  const { register, handleSubmit, setError, setValue, reset, watch, formState } =
    useForm<CategoryValues>({
      resolver: zodResolver(categorySchema),
      defaultValues: { name: '', code: '', description: '' },
    })

  const name = watch('name')

  // The code tracks the name until the operator takes it over — and never in edit mode, where the
  // code is fixed and re-slugging it would be a lie.
  useEffect(() => {
    if (!editing && !codeEdited) setValue('code', toCode(name))
  }, [name, codeEdited, editing, setValue])

  // Reopening starts from the record, not from a failed attempt.
  useEffect(() => {
    if (!open) return
    reset({
      name: category?.name ?? '',
      code: category?.code ?? '',
      description: category?.description ?? '',
    })
    setCodeEdited(false)
    setFormMessage(null)
  }, [open, category, reset])

  const codeRegistration = register('code')

  const onSubmit = handleSubmit((values) => {
    setFormMessage(null)
    const description = values.description === '' ? undefined : values.description

    const onError = (error: unknown): void => {
      // `code` is a category's only unique field, so a 409 can only be about it. The backend sends
      // no errors[] for a conflict, so the mapping happens here.
      if (isApiError(error) && error.status === 409) {
        setError('code', { type: 'server', message: error.detail ?? 'That code is already used' })
        return
      }
      setFormMessage(applyApiErrorToForm<CategoryValues>(error, setError, CATEGORY_FIELDS))
    }

    if (category !== undefined) {
      // Send description even when empty: the backend treats null as "leave alone" and '' as
      // "clear it", so '' is how the operator removes a description.
      update.mutate(
        { id: category.id, body: { name: values.name, description: values.description } },
        {
          onSuccess: () => {
            showToast(`Category "${values.name}" updated`)
            onClose()
          },
          onError,
        },
      )
      return
    }

    create.mutate(
      { name: values.name, code: values.code, description },
      {
        onSuccess: () => {
          showToast(`Category "${values.name}" created`)
          onClose()
        },
        onError,
      },
    )
  })

  return (
    <Dialog
      open={open}
      title={editing ? `Edit ${category.name}` : 'New category'}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button type="submit" form="category-form" loading={pending}>
            {editing ? 'Save changes' : 'Create category'}
          </Button>
        </>
      }
    >
      <form id="category-form" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <Field label="Name" htmlFor="category-name" required error={formState.errors.name?.message}>
          <TextInput
            id="category-name"
            invalid={formState.errors.name !== undefined}
            {...register('name')}
          />
        </Field>

        <Field
          label="Code"
          htmlFor="category-code"
          required
          error={formState.errors.code?.message}
          hint={
            editing
              ? 'Permanent. Create a new category if you need a different code.'
              : 'Lower-case letters, digits and single hyphens. Permanent once created.'
          }
        >
          <TextInput
            id="category-code"
            disabled={editing}
            invalid={formState.errors.code !== undefined}
            {...codeRegistration}
            onChange={(event) => {
              setCodeEdited(true)
              void codeRegistration.onChange(event)
            }}
          />
        </Field>

        <Field
          label="Description"
          htmlFor="category-description"
          error={formState.errors.description?.message}
        >
          <textarea
            id="category-description"
            rows={3}
            className={inputClass}
            {...register('description')}
          />
        </Field>

        {formMessage !== null ? (
          <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
            {formMessage}
          </p>
        ) : null}
      </form>
    </Dialog>
  )
}
```

- [ ] **Step 6: Write the type dialog**

`web/apps/admin/src/features/categories/CategoryTypeFormDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { isApiError, type CategoryType } from '@shopflow/api-client'
import { useEffect, useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Field, TextInput, inputClass } from '../../components/Field'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { CODE_PATTERN, toCode } from '../../lib/slug'
import { useCreateCategoryType, useUpdateCategoryType } from './queries'

const typeSchema = z.object({
  name: z.string().min(1, 'Name is required').max(255, 'Must be 255 characters or fewer'),
  code: z
    .string()
    .min(1, 'Code is required')
    .max(100, 'Must be 100 characters or fewer')
    .regex(CODE_PATTERN, 'Lower-case letters, digits and single hyphens only'),
  description: z.string().max(2000, 'Must be 2000 characters or fewer'),
})

type TypeValues = z.infer<typeof typeSchema>

const TYPE_FIELDS = ['name', 'code', 'description'] as const

export function CategoryTypeFormDialog({
  open,
  onClose,
  categoryId,
  categoryName,
  type,
}: {
  open: boolean
  onClose: () => void
  categoryId: string
  categoryName: string
  type?: CategoryType
}): ReactElement | null {
  const editing = type !== undefined
  const [formMessage, setFormMessage] = useState<string | null>(null)
  const [codeEdited, setCodeEdited] = useState(false)
  const create = useCreateCategoryType()
  const update = useUpdateCategoryType()
  const showToast = useToast()
  const pending = create.isPending || update.isPending

  const { register, handleSubmit, setError, setValue, reset, watch, formState } = useForm<TypeValues>({
    resolver: zodResolver(typeSchema),
    defaultValues: { name: '', code: '', description: '' },
  })

  const name = watch('name')

  useEffect(() => {
    if (!editing && !codeEdited) setValue('code', toCode(name))
  }, [name, codeEdited, editing, setValue])

  useEffect(() => {
    if (!open) return
    reset({ name: type?.name ?? '', code: type?.code ?? '', description: type?.description ?? '' })
    setCodeEdited(false)
    setFormMessage(null)
  }, [open, type, reset])

  const codeRegistration = register('code')

  const onSubmit = handleSubmit((values) => {
    setFormMessage(null)

    const onError = (error: unknown): void => {
      // A type code is unique within its category, and that is the only conflict this call has.
      if (isApiError(error) && error.status === 409) {
        setError('code', {
          type: 'server',
          message: error.detail ?? 'That code is already used in this category',
        })
        return
      }
      setFormMessage(applyApiErrorToForm<TypeValues>(error, setError, TYPE_FIELDS))
    }

    if (type !== undefined) {
      update.mutate(
        { typeId: type.id, body: { name: values.name, description: values.description } },
        {
          onSuccess: () => {
            showToast(`Type "${values.name}" updated`)
            onClose()
          },
          onError,
        },
      )
      return
    }

    create.mutate(
      {
        categoryId,
        body: {
          name: values.name,
          code: values.code,
          description: values.description === '' ? undefined : values.description,
        },
      },
      {
        onSuccess: () => {
          showToast(`Type "${values.name}" added to ${categoryName}`)
          onClose()
        },
        onError,
      },
    )
  })

  return (
    <Dialog
      open={open}
      title={editing ? `Edit type ${type.name}` : `New type in ${categoryName}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button type="submit" form="category-type-form" loading={pending}>
            {editing ? 'Save changes' : 'Add type'}
          </Button>
        </>
      }
    >
      <form id="category-type-form" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <Field label="Name" htmlFor="type-name" required error={formState.errors.name?.message}>
          <TextInput id="type-name" invalid={formState.errors.name !== undefined} {...register('name')} />
        </Field>

        <Field
          label="Code"
          htmlFor="type-code"
          required
          error={formState.errors.code?.message}
          hint={
            editing
              ? 'Permanent. Add a new type if you need a different code.'
              : 'Lower-case letters, digits and single hyphens. Unique within this category.'
          }
        >
          <TextInput
            id="type-code"
            disabled={editing}
            invalid={formState.errors.code !== undefined}
            {...codeRegistration}
            onChange={(event) => {
              setCodeEdited(true)
              void codeRegistration.onChange(event)
            }}
          />
        </Field>

        <Field label="Description" htmlFor="type-description" error={formState.errors.description?.message}>
          <textarea id="type-description" rows={3} className={inputClass} {...register('description')} />
        </Field>

        {formMessage !== null ? (
          <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
            {formMessage}
          </p>
        ) : null}
      </form>
    </Dialog>
  )
}
```

- [ ] **Step 7: Write the card with its actions**

`web/apps/admin/src/features/categories/CategoryCard.tsx`:

```tsx
import type { Category, CategoryType } from '@shopflow/api-client'
import { useState, type ReactElement } from 'react'
import { Badge } from '../../components/Badge'
import { Button } from '../../components/Button'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { useToast } from '../../components/Toast'
import { describeError } from '../../lib/errors'
import { CategoryFormDialog } from './CategoryFormDialog'
import { CategoryTypeFormDialog } from './CategoryTypeFormDialog'
import { useDeleteCategory, useDeleteCategoryType } from './queries'

type OpenDialog =
  | { kind: 'none' }
  | { kind: 'edit-category' }
  | { kind: 'delete-category' }
  | { kind: 'add-type' }
  | { kind: 'edit-type'; type: CategoryType }
  | { kind: 'delete-type'; type: CategoryType }

const CLOSED: OpenDialog = { kind: 'none' }

export function CategoryCard({ category }: { category: Category }): ReactElement {
  const [dialog, setDialog] = useState<OpenDialog>(CLOSED)
  const [failure, setFailure] = useState<string | null>(null)
  const deleteCategory = useDeleteCategory()
  const deleteType = useDeleteCategoryType()
  const showToast = useToast()

  const close = (): void => {
    setDialog(CLOSED)
    setFailure(null)
  }

  const onDeleteCategory = (): void => {
    setFailure(null)
    deleteCategory.mutate(category.id, {
      onSuccess: () => {
        showToast(`Category "${category.name}" deleted`)
        close()
      },
      // Almost always the 409 for a category that still has products. Keeping the dialog open with
      // the server's own sentence is more use than a toast that vanishes.
      onError: (error) => setFailure(describeError(error).body),
    })
  }

  const onDeleteType = (type: CategoryType): void => {
    setFailure(null)
    deleteType.mutate(type.id, {
      onSuccess: () => {
        showToast(`Type "${type.name}" removed`)
        close()
      },
      onError: (error) => setFailure(describeError(error).body),
    })
  }

  const typeNames = category.types.map((type) => type.name).join(', ')

  return (
    <li aria-label={category.name} className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="font-semibold text-slate-900">{category.name}</h2>
          <p className="text-xs text-slate-500">{category.code}</p>
        </div>
        {category.types.length === 0 ? (
          <Badge tone="warning">No types — cannot hold products</Badge>
        ) : null}
      </div>

      {category.description !== undefined ? (
        <p className="mt-2 text-sm text-slate-600">{category.description}</p>
      ) : null}

      {category.types.length > 0 ? (
        <ul className="mt-3 flex flex-col gap-2">
          {category.types.map((type) => (
            <li key={type.id} className="flex flex-wrap items-center gap-2">
              <Badge>{`${type.name} (${type.code})`}</Badge>
              <Button
                variant="ghost"
                className="px-2 text-xs"
                aria-label={`Edit type ${type.name}`}
                onClick={() => setDialog({ kind: 'edit-type', type })}
              >
                Edit
              </Button>
              <Button
                variant="ghost"
                className="px-2 text-xs text-red-700"
                aria-label={`Remove type ${type.name}`}
                onClick={() => setDialog({ kind: 'delete-type', type })}
              >
                Remove
              </Button>
            </li>
          ))}
        </ul>
      ) : null}

      {/* Wraps to a column on a narrow phone; each button keeps its 44px target. */}
      <div className="mt-4 flex flex-wrap gap-2">
        <Button
          variant="secondary"
          aria-label={`Add type to ${category.name}`}
          onClick={() => setDialog({ kind: 'add-type' })}
        >
          Add type
        </Button>
        <Button
          variant="secondary"
          aria-label={`Edit ${category.name}`}
          onClick={() => setDialog({ kind: 'edit-category' })}
        >
          Edit
        </Button>
        <Button
          variant="danger"
          aria-label={`Delete ${category.name}`}
          onClick={() => setDialog({ kind: 'delete-category' })}
        >
          Delete
        </Button>
      </div>

      <CategoryFormDialog
        open={dialog.kind === 'edit-category'}
        category={category}
        onClose={close}
      />

      <CategoryTypeFormDialog
        open={dialog.kind === 'add-type'}
        categoryId={category.id}
        categoryName={category.name}
        onClose={close}
      />

      {dialog.kind === 'edit-type' ? (
        <CategoryTypeFormDialog
          open
          categoryId={category.id}
          categoryName={category.name}
          type={dialog.type}
          onClose={close}
        />
      ) : null}

      <ConfirmDialog
        open={dialog.kind === 'delete-category'}
        title={`Delete ${category.name}?`}
        confirmLabel="Delete category"
        destructive
        busy={deleteCategory.isPending}
        error={failure}
        onConfirm={onDeleteCategory}
        onCancel={close}
      >
        {category.types.length === 0
          ? `Deleting "${category.name}" is permanent. This cannot be undone.`
          : `Deleting "${category.name}" also deletes its ${category.types.length} ` +
            `type${category.types.length === 1 ? '' : 's'} (${typeNames}). This cannot be undone.`}
      </ConfirmDialog>

      {dialog.kind === 'delete-type' ? (
        <ConfirmDialog
          open
          title={`Remove ${dialog.type.name}?`}
          confirmLabel="Remove type"
          destructive
          busy={deleteType.isPending}
          error={failure}
          onConfirm={() => onDeleteType(dialog.type)}
          onCancel={close}
        >
          {`Remove the type "${dialog.type.name}" from "${category.name}"? This cannot be undone.`}
        </ConfirmDialog>
      ) : null}
    </li>
  )
}
```

- [ ] **Step 8: Point the page at the moved card**

In `web/apps/admin/src/features/categories/CategoriesPage.tsx`, delete the whole `CategoryCard` function and its now-unused `Badge` and `Category` imports, and import the card instead:

```tsx
import { CategoryCard } from './CategoryCard'
```

The file's remaining imports are:

```tsx
import { useState, type ReactElement } from 'react'
import { Button } from '../../components/Button'
import { EmptyState, ErrorPanel, Skeleton } from '../../components/QueryStates'
import { CategoryCard } from './CategoryCard'
import { CategoryFormDialog } from './CategoryFormDialog'
import { useCategories } from './queries'
```

- [ ] **Step 9: Run the tests to verify they pass**

```bash
cd web/apps/admin
pnpm test
pnpm typecheck
pnpm lint
```

Expected: PASS, 40 tests. The cascade wording in `names the types the cascade will take` is acceptance criterion 11.

- [ ] **Step 10: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add web/apps/admin
git commit -m "feat(admin): category edit, cascade-aware delete and category types"
```

---

### Task 9: Products list — URL-backed filters, responsive rows, pagination

The screen the operator lives on. Every filter lives in the query string, so a filtered view is a shareable, reloadable, back-buttonable URL — and every value is re-whitelisted on the way out of the URL, because `GET /api/admin/products` answers `400` to anything outside `exclude|only|all`, `name|price|createdAt` and `asc|desc`.

Two presentations of the same rows: a real table from `md` up, stacked cards below. Both come from one `content` array, so they cannot disagree.

**Files:**
- Create: `web/apps/admin/src/features/products/filters.ts`
- Create: `web/apps/admin/src/features/products/api.ts`
- Create: `web/apps/admin/src/features/products/queries.ts`
- Create: `web/apps/admin/src/features/products/ProductList.tsx`
- Create: `web/apps/admin/src/features/products/ProductsPage.tsx`
- Create: `web/apps/admin/src/components/Pagination.tsx`
- Modify: `web/apps/admin/src/routes/router.tsx`
- Test: `web/apps/admin/src/features/products/filters.test.ts`
- Test: `web/apps/admin/src/features/products/ProductsPage.test.tsx`

**Interfaces:**
- Consumes: `request`, `AdminProductPage`, `AdminProductSummary` from `@shopflow/api-client`; `formatUsd` from Task 4; `Badge`, `Button`, `Field`, `inputClass`, `Skeleton`, `ErrorPanel`, `EmptyState` from Task 5; `useCategories` from Task 7.
- Produces:
  - `type ArchivedFilter = 'exclude' | 'only' | 'all'`
  - `type ProductSort = 'name' | 'price' | 'createdAt'`
  - `type SortDirection = 'asc' | 'desc'`
  - `type ProductFilters = { q: string; categoryId: string; archived: ArchivedFilter; sort: ProductSort; direction: SortDirection; page: number; size: number }`
  - `const DEFAULT_PAGE_SIZE = 20`
  - `const DEFAULT_FILTERS: ProductFilters`
  - `function parseFilters(params: URLSearchParams): ProductFilters`
  - `function filtersToSearchParams(filters: ProductFilters): URLSearchParams`
  - `function useProductFilters(): { filters: ProductFilters; setFilters: (patch: Partial<ProductFilters>) => void }`
  - `function fetchProducts(filters: ProductFilters): Promise<AdminProductPage>`
  - `const PRODUCTS_QUERY_KEY = 'products'`
  - `function useProducts(filters: ProductFilters): UseQueryResult<AdminProductPage>`
  - `function ProductList(props: { products: AdminProductSummary[] }): ReactElement`
  - `function ProductsPage(): ReactElement`
  - `function Pagination(props: { page: number; totalPages: number; totalElements: number; onPageChange: (page: number) => void }): ReactElement | null`

- [ ] **Step 1: Write the failing filter test**

`web/apps/admin/src/features/products/filters.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { DEFAULT_FILTERS, filtersToSearchParams, parseFilters } from './filters'

describe('parseFilters', () => {
  it('falls back to the defaults for an empty query string', () => {
    expect(parseFilters(new URLSearchParams())).toEqual(DEFAULT_FILTERS)
  })

  it('reads every supported value', () => {
    expect(
      parseFilters(
        new URLSearchParams(
          'q=abaya&categoryId=c1&archived=only&sort=price&direction=desc&page=2&size=50',
        ),
      ),
    ).toEqual({
      q: 'abaya',
      categoryId: 'c1',
      archived: 'only',
      sort: 'price',
      direction: 'desc',
      page: 2,
      size: 50,
    })
  })

  it('drops values the backend would reject rather than sending a 400', () => {
    const filters = parseFilters(
      new URLSearchParams('archived=deleted&sort=stock&direction=sideways&page=-3&size=999'),
    )

    expect(filters.archived).toBe('exclude')
    expect(filters.sort).toBe('name')
    expect(filters.direction).toBe('asc')
    expect(filters.page).toBe(0)
    expect(filters.size).toBe(100)
  })

  it('treats a non-numeric page or size as absent', () => {
    const filters = parseFilters(new URLSearchParams('page=abc&size='))

    expect(filters.page).toBe(0)
    expect(filters.size).toBe(20)
  })
})

describe('filtersToSearchParams', () => {
  it('writes nothing for the default view, so /products stays clean', () => {
    expect(filtersToSearchParams(DEFAULT_FILTERS).toString()).toBe('')
  })

  it('writes only what differs from the default', () => {
    expect(
      filtersToSearchParams({ ...DEFAULT_FILTERS, q: 'abaya', archived: 'all', page: 3 }).toString(),
    ).toBe('q=abaya&archived=all&page=3')
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd web/apps/admin
pnpm test filters
```

Expected: FAIL — `Failed to resolve import "./filters"`.

- [ ] **Step 3: Write the filters module**

`web/apps/admin/src/features/products/filters.ts`:

```ts
import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router'

const ARCHIVED_VALUES = ['exclude', 'only', 'all'] as const
const SORT_VALUES = ['name', 'price', 'createdAt'] as const
const DIRECTION_VALUES = ['asc', 'desc'] as const

export type ArchivedFilter = (typeof ARCHIVED_VALUES)[number]
export type ProductSort = (typeof SORT_VALUES)[number]
export type SortDirection = (typeof DIRECTION_VALUES)[number]

export const DEFAULT_PAGE_SIZE = 20
const MAX_PAGE_SIZE = 100

export type ProductFilters = {
  q: string
  categoryId: string
  archived: ArchivedFilter
  sort: ProductSort
  direction: SortDirection
  page: number
  size: number
}

export const DEFAULT_FILTERS: ProductFilters = {
  q: '',
  categoryId: '',
  archived: 'exclude',
  sort: 'name',
  direction: 'asc',
  page: 0,
  size: DEFAULT_PAGE_SIZE,
}

/** A hand-edited URL must never reach the backend as a 400, so unknown values become defaults. */
function oneOf<T extends string>(values: readonly T[], raw: string | null, fallback: T): T {
  return raw !== null && (values as readonly string[]).includes(raw) ? (raw as T) : fallback
}

function clampedInt(raw: string | null, fallback: number, min: number, max: number): number {
  if (raw === null || raw.trim() === '') return fallback
  const parsed = Number.parseInt(raw, 10)
  if (Number.isNaN(parsed)) return fallback
  return Math.min(Math.max(parsed, min), max)
}

export function parseFilters(params: URLSearchParams): ProductFilters {
  return {
    q: params.get('q') ?? '',
    categoryId: params.get('categoryId') ?? '',
    archived: oneOf(ARCHIVED_VALUES, params.get('archived'), 'exclude'),
    sort: oneOf(SORT_VALUES, params.get('sort'), 'name'),
    direction: oneOf(DIRECTION_VALUES, params.get('direction'), 'asc'),
    page: clampedInt(params.get('page'), 0, 0, Number.MAX_SAFE_INTEGER),
    size: clampedInt(params.get('size'), DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE),
  }
}

/** Only non-defaults are written, so the plain list URL is just `/products`. */
export function filtersToSearchParams(filters: ProductFilters): URLSearchParams {
  const params = new URLSearchParams()
  if (filters.q !== '') params.set('q', filters.q)
  if (filters.categoryId !== '') params.set('categoryId', filters.categoryId)
  if (filters.archived !== DEFAULT_FILTERS.archived) params.set('archived', filters.archived)
  if (filters.sort !== DEFAULT_FILTERS.sort) params.set('sort', filters.sort)
  if (filters.direction !== DEFAULT_FILTERS.direction) params.set('direction', filters.direction)
  if (filters.page !== 0) params.set('page', String(filters.page))
  if (filters.size !== DEFAULT_PAGE_SIZE) params.set('size', String(filters.size))
  return params
}

/**
 * The query string is the state. Changing any filter other than the page resets to page 0 —
 * otherwise a narrower filter can leave you stranded on a page that no longer exists.
 */
export function useProductFilters(): {
  filters: ProductFilters
  setFilters: (patch: Partial<ProductFilters>) => void
} {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = useMemo(() => parseFilters(searchParams), [searchParams])

  const setFilters = useCallback(
    (patch: Partial<ProductFilters>): void => {
      const next: ProductFilters = { ...filters, ...patch }
      if (patch.page === undefined) next.page = 0
      // replace: typing in the search box must not bury the previous screen under history entries.
      setSearchParams(filtersToSearchParams(next), { replace: true })
    },
    [filters, setSearchParams],
  )

  return { filters, setFilters }
}
```

- [ ] **Step 4: Run it to verify it passes**

```bash
cd web/apps/admin
pnpm test filters
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Write the failing page test**

`web/apps/admin/src/features/products/ProductsPage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { ProductsPage } from './ProductsPage'

const CATEGORY_ID = '33333333-3333-3333-3333-333333333333'

const ABAYA = {
  id: '77777777-7777-7777-7777-777777777777',
  name: 'Classic Black Abaya',
  price: 129.5,
  categoryId: CATEGORY_ID,
  categoryName: 'Abaya',
  categoryTypeId: '44444444-4444-4444-4444-444444444444',
  categoryTypeName: 'Abaya',
  variantCount: 3,
  totalStock: 12,
}

const SOLD_OUT = {
  ...ABAYA,
  id: '88888888-8888-8888-8888-888888888888',
  name: 'Sand Abaya',
  variantCount: 2,
  totalStock: 0,
}

function page(content: unknown[], overrides: Record<string, unknown> = {}) {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: 1,
    first: true,
    last: true,
    ...overrides,
  }
}

/** Every request the page makes, so the tests can assert on what actually went out. */
let sent: URLSearchParams[] = []

beforeEach(() => {
  sent = []
  server.use(
    http.get(`${API}/api/categories`, () =>
      HttpResponse.json([{ id: CATEGORY_ID, code: 'abaya', name: 'Abaya', types: [] }]),
    ),
    http.get(`${API}/api/admin/products`, ({ request }) => {
      sent.push(new URL(request.url).searchParams)
      return HttpResponse.json(page([ABAYA, SOLD_OUT]))
    }),
  )
})

function renderPage(search = '') {
  return renderWithProviders(<ProductsPage />, {
    route: `/products${search}`,
    path: '/products',
  })
}

describe('ProductsPage', () => {
  it('asks for live products, page 0, name ascending by default and sends no empty params', async () => {
    renderPage()

    await waitFor(() => expect(sent).toHaveLength(1))
    const query = sent[0]!
    expect(Object.fromEntries(query)).toEqual({
      archived: 'exclude',
      sort: 'name',
      direction: 'asc',
      page: '0',
      size: '20',
    })
  })

  it('shows each product with its category, price and live counts', async () => {
    renderPage()

    const row = await screen.findByRole('row', { name: /Classic Black Abaya/ })
    expect(within(row).getByText('$129.50')).toBeInTheDocument()
    expect(within(row).getByText('Abaya / Abaya')).toBeInTheDocument()
    expect(within(row).getByText('3')).toBeInTheDocument()
    expect(within(row).getByText('12')).toBeInTheDocument()

    // The headers say "live" because the backend counts unarchived variants only.
    expect(screen.getByRole('columnheader', { name: 'Live variants' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Live stock' })).toBeInTheDocument()
  })

  it('marks a product whose live stock is zero', async () => {
    renderPage()

    const row = await screen.findByRole('row', { name: /Sand Abaya/ })
    expect(within(row).getByText('Out of stock')).toBeInTheDocument()

    const stocked = screen.getByRole('row', { name: /Classic Black Abaya/ })
    expect(within(stocked).queryByText('Out of stock')).not.toBeInTheDocument()
  })

  it('links each product to its detail page', async () => {
    renderPage()

    const link = await screen.findByRole('link', { name: 'Classic Black Abaya' })
    expect(link).toHaveAttribute('href', `/products/${ABAYA.id}`)
  })

  it('debounces the search box into one request', async () => {
    renderPage()
    await waitFor(() => expect(sent).toHaveLength(1))

    await userEvent.type(screen.getByLabelText('Search products'), 'abaya')

    await waitFor(() => expect(sent.filter((query) => query.get('q') === 'abaya')).toHaveLength(1))
    // Five keystrokes must not be five requests.
    expect(sent).toHaveLength(2)
  })

  it('filters by category and returns to the first page', async () => {
    renderPage('?page=4')
    await waitFor(() => expect(sent).toHaveLength(1))

    await userEvent.selectOptions(screen.getByLabelText('Category'), CATEGORY_ID)

    await waitFor(() => expect(sent).toHaveLength(2))
    const query = sent[1]!
    expect(query.get('categoryId')).toBe(CATEGORY_ID)
    expect(query.get('page')).toBe('0')
  })

  it('switches to archived only and labels the archived rows', async () => {
    renderPage()
    await waitFor(() => expect(sent).toHaveLength(1))

    server.use(
      http.get(`${API}/api/admin/products`, ({ request }) => {
        sent.push(new URL(request.url).searchParams)
        return HttpResponse.json(page([{ ...ABAYA, archivedAt: '2026-08-01T10:00:00Z' }]))
      }),
    )
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'only')

    await waitFor(() => expect(sent[1]?.get('archived')).toBe('only'))
    const row = await screen.findByRole('row', { name: /Classic Black Abaya/ })
    expect(within(row).getByText('Archived')).toBeInTheDocument()
  })

  it('sorts by a whitelisted field and direction', async () => {
    renderPage()
    await waitFor(() => expect(sent).toHaveLength(1))

    await userEvent.selectOptions(screen.getByLabelText('Sort by'), 'price')
    await waitFor(() => expect(sent[1]?.get('sort')).toBe('price'))

    await userEvent.selectOptions(screen.getByLabelText('Direction'), 'desc')
    await waitFor(() => expect(sent[2]?.get('direction')).toBe('desc'))
    expect(sent[2]?.get('sort')).toBe('price')
  })

  it('ignores query-string values the backend would reject', async () => {
    renderPage('?sort=stock&direction=sideways&archived=deleted&page=-2')

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(Object.fromEntries(sent[0]!)).toEqual({
      archived: 'exclude',
      sort: 'name',
      direction: 'asc',
      page: '0',
      size: '20',
    })
  })

  it('pages forward and disables Previous on the first page', async () => {
    server.use(
      http.get(`${API}/api/admin/products`, ({ request }) => {
        const query = new URL(request.url).searchParams
        sent.push(query)
        return HttpResponse.json(
          page([ABAYA], {
            page: Number(query.get('page')),
            totalElements: 42,
            totalPages: 3,
            first: query.get('page') === '0',
            last: query.get('page') === '2',
          }),
        )
      }),
    )
    renderPage()

    expect(await screen.findByText('Page 1 of 3 · 42 products')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))

    await waitFor(() => expect(sent[1]?.get('page')).toBe('1'))
    expect(await screen.findByText('Page 2 of 3 · 42 products')).toBeInTheDocument()
  })

  it('explains an empty result differently when a filter is on', async () => {
    server.use(
      http.get(`${API}/api/admin/products`, ({ request }) => {
        sent.push(new URL(request.url).searchParams)
        return HttpResponse.json(page([], { totalPages: 0 }))
      }),
    )
    renderPage('?q=nothing')

    expect(await screen.findByRole('heading', { name: 'No matching products' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument()
  })

  it('offers a retry when the list fails', async () => {
    server.use(
      http.get(`${API}/api/admin/products`, () => problemResponse(500, { detail: 'Internal error' })),
    )
    renderPage()

    expect(await screen.findByRole('heading', { name: 'The server failed' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
```

- [ ] **Step 6: Run it to verify it fails**

```bash
cd web/apps/admin
pnpm test ProductsPage
```

Expected: FAIL — `Failed to resolve import "./ProductsPage"`.

- [ ] **Step 7: Write the API call and the query hook**

`web/apps/admin/src/features/products/api.ts`:

```ts
import { request, type AdminProductPage } from '@shopflow/api-client'
import type { ProductFilters } from './filters'

/** `request` drops empty values, so a blank `q` or `categoryId` never reaches the backend. */
export function fetchProducts(filters: ProductFilters): Promise<AdminProductPage> {
  return request<AdminProductPage>('/api/admin/products', {
    query: {
      q: filters.q,
      categoryId: filters.categoryId,
      archived: filters.archived,
      sort: filters.sort,
      direction: filters.direction,
      page: filters.page,
      size: filters.size,
    },
  })
}
```

`web/apps/admin/src/features/products/queries.ts`:

```ts
import type { AdminProductPage } from '@shopflow/api-client'
import { keepPreviousData, useQuery, type UseQueryResult } from '@tanstack/react-query'
import { fetchProducts } from './api'
import type { ProductFilters } from './filters'

export const PRODUCTS_QUERY_KEY = 'products'

export function useProducts(filters: ProductFilters): UseQueryResult<AdminProductPage> {
  return useQuery({
    queryKey: [PRODUCTS_QUERY_KEY, filters],
    queryFn: () => fetchProducts(filters),
    // Keeps the current rows on screen while the next page loads instead of flashing skeletons.
    placeholderData: keepPreviousData,
  })
}
```

- [ ] **Step 8: Write the pagination control**

`web/apps/admin/src/components/Pagination.tsx`:

```tsx
import type { ReactElement } from 'react'
import { Button } from './Button'

export function Pagination({
  page,
  totalPages,
  totalElements,
  onPageChange,
}: {
  page: number
  totalPages: number
  totalElements: number
  onPageChange: (page: number) => void
}): ReactElement | null {
  if (totalPages <= 1) return null

  return (
    <nav
      aria-label="Pagination"
      className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 pt-3"
    >
      <p className="text-sm text-slate-600">
        {`Page ${page + 1} of ${totalPages} · ${totalElements} product${totalElements === 1 ? '' : 's'}`}
      </p>
      <div className="flex gap-2">
        <Button
          variant="secondary"
          aria-label="Previous page"
          disabled={page === 0}
          onClick={() => onPageChange(page - 1)}
        >
          Previous
        </Button>
        <Button
          variant="secondary"
          aria-label="Next page"
          disabled={page >= totalPages - 1}
          onClick={() => onPageChange(page + 1)}
        >
          Next
        </Button>
      </div>
    </nav>
  )
}
```

- [ ] **Step 9: Write the responsive list**

`web/apps/admin/src/features/products/ProductList.tsx`:

```tsx
import type { AdminProductSummary } from '@shopflow/api-client'
import type { ReactElement } from 'react'
import { Link } from 'react-router'
import { Badge } from '../../components/Badge'
import { formatUsd } from '../../lib/format'

/**
 * One data source, two layouts: a table from `md` up, cards below. Anything derived lives in
 * `StatusBadges` so the two can never disagree about what a row means.
 */
export function ProductList({ products }: { products: AdminProductSummary[] }): ReactElement {
  return (
    <>
      <table className="hidden w-full border-collapse text-sm md:table">
        <thead>
          <tr className="border-b border-slate-200 text-left text-slate-600">
            <th scope="col" className="py-2 pr-3 font-medium">
              Product
            </th>
            <th scope="col" className="py-2 pr-3 font-medium">
              Category
            </th>
            <th scope="col" className="py-2 pr-3 text-right font-medium">
              Price
            </th>
            <th scope="col" className="py-2 pr-3 text-right font-medium">
              Live variants
            </th>
            <th scope="col" className="py-2 pr-3 text-right font-medium">
              Live stock
            </th>
            <th scope="col" className="py-2 font-medium">
              Status
            </th>
          </tr>
        </thead>
        <tbody>
          {products.map((product) => (
            <tr key={product.id} className="border-b border-slate-100">
              <td className="py-3 pr-3">
                <Link
                  to={`/products/${product.id}`}
                  className="font-medium text-slate-900 underline-offset-2 hover:underline"
                >
                  {product.name}
                </Link>
              </td>
              <td className="py-3 pr-3 text-slate-600">
                {`${product.categoryName} / ${product.categoryTypeName}`}
              </td>
              <td className="py-3 pr-3 text-right tabular-nums">{formatUsd(product.price)}</td>
              <td className="py-3 pr-3 text-right tabular-nums">{product.variantCount}</td>
              <td className="py-3 pr-3 text-right tabular-nums">{product.totalStock}</td>
              <td className="py-3">
                <div className="flex flex-wrap gap-1">
                  <StatusBadges product={product} />
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <ul className="flex flex-col gap-3 md:hidden">
        {products.map((product) => (
          <li key={product.id} className="rounded-lg border border-slate-200 bg-white p-4">
            <div className="flex items-start justify-between gap-3">
              <Link to={`/products/${product.id}`} className="font-medium text-slate-900">
                {product.name}
              </Link>
              <span className="shrink-0 tabular-nums">{formatUsd(product.price)}</span>
            </div>
            <p className="mt-1 text-xs text-slate-500">
              {`${product.categoryName} / ${product.categoryTypeName}`}
            </p>
            <p className="mt-2 text-xs text-slate-600">
              {`${product.variantCount} live variant${product.variantCount === 1 ? '' : 's'} · ${product.totalStock} in stock`}
            </p>
            <div className="mt-2 flex flex-wrap gap-1">
              <StatusBadges product={product} />
            </div>
          </li>
        ))}
      </ul>
    </>
  )
}

function StatusBadges({ product }: { product: AdminProductSummary }): ReactElement {
  return (
    <>
      {product.archivedAt !== undefined ? <Badge tone="neutral">Archived</Badge> : null}
      {product.variantCount === 0 ? <Badge tone="warning">No variants</Badge> : null}
      {product.variantCount > 0 && product.totalStock === 0 ? (
        <Badge tone="danger">Out of stock</Badge>
      ) : null}
    </>
  )
}
```

- [ ] **Step 10: Write the page**

`web/apps/admin/src/features/products/ProductsPage.tsx`:

```tsx
import { useEffect, useState, type ReactElement } from 'react'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { Field, TextInput, inputClass } from '../../components/Field'
import { Pagination } from '../../components/Pagination'
import { EmptyState, ErrorPanel, Skeleton } from '../../components/QueryStates'
import { useCategories } from '../categories/queries'
import { ProductList } from './ProductList'
import {
  DEFAULT_FILTERS,
  useProductFilters,
  type ArchivedFilter,
  type ProductSort,
  type SortDirection,
} from './filters'
import { useProducts } from './queries'

const SEARCH_DEBOUNCE_MS = 300

export function ProductsPage(): ReactElement {
  const { filters, setFilters } = useProductFilters()
  const products = useProducts(filters)
  const categories = useCategories()

  // The box is typed into far faster than the backend should be asked, so the input keeps its own
  // value and only the settled value reaches the URL.
  const [searchDraft, setSearchDraft] = useState(filters.q)

  // A Back navigation or Clear filters changes `filters.q` from outside; follow it.
  useEffect(() => setSearchDraft(filters.q), [filters.q])

  useEffect(() => {
    if (searchDraft === filters.q) return
    const timer = setTimeout(() => setFilters({ q: searchDraft }), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [searchDraft, filters.q, setFilters])

  const filtered =
    filters.q !== '' || filters.categoryId !== '' || filters.archived !== DEFAULT_FILTERS.archived

  return (
    <section className="flex flex-col gap-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold text-slate-900">Products</h1>
        {/* A Link, not a Button: Button renders a <button> and cannot navigate. */}
        <Link
          to="/products/new"
          className="inline-flex min-h-11 items-center rounded-md bg-slate-900 px-4 text-sm font-medium text-white"
        >
          New product
        </Link>
      </header>

      {/* Stacks on a phone, one row from md up. */}
      <div className="grid gap-3 md:grid-cols-4">
        <Field label="Search products" htmlFor="product-search">
          <TextInput
            id="product-search"
            type="search"
            placeholder="Name or description"
            value={searchDraft}
            onChange={(event) => setSearchDraft(event.target.value)}
          />
        </Field>

        <Field label="Category" htmlFor="product-category">
          <select
            id="product-category"
            className={inputClass}
            value={filters.categoryId}
            onChange={(event) => setFilters({ categoryId: event.target.value })}
          >
            <option value="">All categories</option>
            {(categories.data ?? []).map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Status" htmlFor="product-archived">
          <select
            id="product-archived"
            className={inputClass}
            value={filters.archived}
            onChange={(event) => setFilters({ archived: event.target.value as ArchivedFilter })}
          >
            <option value="exclude">Live</option>
            <option value="only">Archived</option>
            <option value="all">All</option>
          </select>
        </Field>

        <div className="grid grid-cols-2 gap-3">
          <Field label="Sort by" htmlFor="product-sort">
            <select
              id="product-sort"
              className={inputClass}
              value={filters.sort}
              onChange={(event) => setFilters({ sort: event.target.value as ProductSort })}
            >
              <option value="name">Name</option>
              <option value="price">Price</option>
              <option value="createdAt">Created</option>
            </select>
          </Field>

          <Field label="Direction" htmlFor="product-direction">
            <select
              id="product-direction"
              className={inputClass}
              value={filters.direction}
              onChange={(event) => setFilters({ direction: event.target.value as SortDirection })}
            >
              <option value="asc">Ascending</option>
              <option value="desc">Descending</option>
            </select>
          </Field>
        </div>
      </div>

      {products.isPending ? <Skeleton rows={5} label="Loading products" /> : null}

      {products.isError ? (
        <ErrorPanel error={products.error} onRetry={() => void products.refetch()} />
      ) : null}

      {products.data !== undefined && products.data.content.length === 0 ? (
        filtered ? (
          <EmptyState
            title="No matching products"
            description="Nothing matches these filters. Widen the search or clear them."
            action={<Button variant="secondary" onClick={() => setFilters(DEFAULT_FILTERS)}>Clear filters</Button>}
          />
        ) : (
          <EmptyState
            title="No products yet"
            description="Add your first product to start filling the catalogue."
            action={
              <Link
                to="/products/new"
                className="inline-flex min-h-11 items-center rounded-md bg-slate-900 px-4 text-sm font-medium text-white"
              >
                New product
              </Link>
            }
          />
        )
      ) : null}

      {products.data !== undefined && products.data.content.length > 0 ? (
        <>
          <div aria-busy={products.isFetching}>
            <ProductList products={products.data.content} />
          </div>
          <Pagination
            page={products.data.page}
            totalPages={products.data.totalPages}
            totalElements={products.data.totalElements}
            onPageChange={(page) => setFilters({ page })}
          />
        </>
      ) : null}
    </section>
  )
}
```

- [ ] **Step 11: Add the route**

In `web/apps/admin/src/routes/router.tsx`:

```tsx
import { ProductsPage } from '../features/products/ProductsPage'
```

```tsx
    children: [
      { path: '/', element: <Navigate to="/products" replace /> },
      { path: '/products', element: <ProductsPage /> },
      { path: '/categories', element: <CategoriesPage /> },
    ],
```

- [ ] **Step 12: Run the tests to verify they pass**

```bash
cd web/apps/admin
pnpm test
pnpm typecheck
pnpm lint
```

Expected: PASS, 58 tests.

- [ ] **Step 13: See it on both screen sizes**

With the backend and `pnpm dev` running, open `http://localhost:5173/products`, sign in, and check:
1. At a 390px viewport the rows are cards; from 768px they are a table with the `Live variants` and `Live stock` columns.
2. Typing in the search box updates the URL once, not per keystroke, and reloading that URL restores the same view.
3. `http://localhost:5173/products?sort=stock` still loads, sorted by name — no `400`.

- [ ] **Step 14: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add web/apps/admin
git commit -m "feat(admin): product list with URL-backed filters and a table/card pair"
```

---

### Task 10: Product create — dependent selects that cannot form a 404

Delivers acceptance criterion 6. `POST /api/admin/products` answers `404` when `categoryTypeId` belongs to a different category than `categoryId`, so the type select is populated *from* the chosen category and cleared whenever the category changes. The invalid pair is not validated — it is unreachable.

The form fields live in their own component because Task 11 edits the same five fields on the detail screen; one component means the price rule and the category/type coupling cannot drift between create and edit.

**Files:**
- Create: `web/apps/admin/src/features/products/productForm.ts`
- Create: `web/apps/admin/src/features/products/ProductFields.tsx`
- Create: `web/apps/admin/src/features/products/NewProductPage.tsx`
- Modify: `web/apps/admin/src/features/products/api.ts`
- Modify: `web/apps/admin/src/features/products/queries.ts`
- Modify: `web/apps/admin/src/routes/router.tsx`
- Test: `web/apps/admin/src/features/products/NewProductPage.test.tsx`

**Interfaces:**
- Consumes: `CreateProductRequest`, `AdminProduct` from `@shopflow/api-client`; `useCategories` from Task 7; `Field`, `TextInput`, `inputClass`, `Button`, `Skeleton`, `ErrorPanel`, `useToast` from Tasks 5 and 7; `applyApiErrorToForm` from Task 5.
- Produces:
  - `const productSchema: ZodObject` and `type ProductFormValues = { name: string; description: string; price: string; categoryId: string; categoryTypeId: string }`
  - `const PRODUCT_FIELDS: readonly (keyof ProductFormValues)[]`
  - `function priceToNumber(price: string): number`
  - `function ProductFields(props: { form: UseFormReturn<ProductFormValues>; categories: Category[] }): ReactElement`
  - `function NewProductPage(): ReactElement`
  - `function createProduct(body: CreateProductRequest): Promise<AdminProduct>`
  - `function useCreateProduct(): UseMutationResult<AdminProduct, unknown, CreateProductRequest>`

- [ ] **Step 1: Write the failing test**

`web/apps/admin/src/features/products/NewProductPage.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { NewProductPage } from './NewProductPage'

const ABAYA_ID = '33333333-3333-3333-3333-333333333333'
const ABAYA_TYPE_ID = '44444444-4444-4444-4444-444444444444'
const KIMONO_TYPE_ID = '66666666-6666-6666-6666-666666666666'
const HIJAB_ID = '99999999-9999-9999-9999-999999999999'
const HIJAB_TYPE_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const NEW_PRODUCT_ID = '77777777-7777-7777-7777-777777777777'

const CATEGORIES = [
  {
    id: ABAYA_ID,
    code: 'abaya',
    name: 'Abaya',
    types: [
      { id: ABAYA_TYPE_ID, code: 'abaya', name: 'Abaya' },
      { id: KIMONO_TYPE_ID, code: 'kimono', name: 'Kimono' },
    ],
  },
  { id: HIJAB_ID, code: 'hijabs', name: 'Hijabs', types: [{ id: HIJAB_TYPE_ID, code: 'hijabs', name: 'Hijabs' }] },
  { id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', code: 'accessories', name: 'Accessories', types: [] },
]

function renderPage() {
  server.use(http.get(`${API}/api/categories`, () => HttpResponse.json(CATEGORIES)))
  return renderWithProviders(<NewProductPage />, {
    route: '/products/new',
    path: '/products/new',
    extraRoutes: [{ path: '/products/:id', element: <p>Product detail</p> }],
  })
}

describe('NewProductPage', () => {
  it('keeps the type select unusable until a category is chosen', async () => {
    renderPage()

    const type = await screen.findByLabelText(/^type/i)
    expect(type).toBeDisabled()
    expect(screen.getByText('Choose a category first')).toBeInTheDocument()
  })

  it('offers only the chosen category’s types, so the 404 pair cannot be built', async () => {
    renderPage()

    await userEvent.selectOptions(await screen.findByLabelText('Category'), ABAYA_ID)

    const type = screen.getByLabelText(/^type/i)
    expect(type).toBeEnabled()
    const values = Array.from(type.querySelectorAll('option')).map((option) => option.value)
    expect(values).toEqual(['', ABAYA_TYPE_ID, KIMONO_TYPE_ID])
    expect(values).not.toContain(HIJAB_TYPE_ID)
  })

  it('clears the chosen type when the category changes', async () => {
    renderPage()

    await userEvent.selectOptions(await screen.findByLabelText('Category'), ABAYA_ID)
    await userEvent.selectOptions(screen.getByLabelText(/^type/i), KIMONO_TYPE_ID)
    expect(screen.getByLabelText(/^type/i)).toHaveValue(KIMONO_TYPE_ID)

    await userEvent.selectOptions(screen.getByLabelText('Category'), HIJAB_ID)

    expect(screen.getByLabelText(/^type/i)).toHaveValue('')
  })

  it('sends the operator to the categories screen when a category has no types', async () => {
    renderPage()

    await userEvent.selectOptions(
      await screen.findByLabelText('Category'),
      'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    )

    expect(screen.getByText(/“Accessories” has no types yet/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Add a type' })).toHaveAttribute('href', '/categories')
    expect(screen.getByLabelText(/^type/i)).toBeDisabled()
  })

  it('creates the product and opens it', async () => {
    const posted: unknown[] = []
    server.use(
      http.post(`${API}/api/admin/products`, async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json(
          { id: NEW_PRODUCT_ID, name: 'Classic Black Abaya', price: 129.5, variants: [], resources: [] },
          { status: 201 },
        )
      }),
    )
    renderPage()

    await userEvent.type(await screen.findByLabelText(/^name/i), 'Classic Black Abaya')
    await userEvent.type(screen.getByLabelText(/^price/i), '129.50')
    await userEvent.selectOptions(screen.getByLabelText('Category'), ABAYA_ID)
    await userEvent.selectOptions(screen.getByLabelText(/^type/i), ABAYA_TYPE_ID)
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }))

    await waitFor(() =>
      expect(posted).toEqual([
        {
          name: 'Classic Black Abaya',
          price: 129.5,
          categoryId: ABAYA_ID,
          categoryTypeId: ABAYA_TYPE_ID,
        },
      ]),
    )
    expect(await screen.findByText('Product detail')).toBeInTheDocument()
  })

  it('rejects a price the numeric(12,2) column cannot hold, before any request', async () => {
    renderPage()

    await userEvent.type(await screen.findByLabelText(/^name/i), 'Classic Black Abaya')
    await userEvent.type(screen.getByLabelText(/^price/i), '10.999')
    await userEvent.selectOptions(screen.getByLabelText('Category'), ABAYA_ID)
    await userEvent.selectOptions(screen.getByLabelText(/^type/i), ABAYA_TYPE_ID)
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }))

    expect(
      await screen.findByText('Use up to ten digits and at most two decimal places'),
    ).toBeInTheDocument()
  })

  it('will not submit without a category and a type', async () => {
    renderPage()

    await userEvent.type(await screen.findByLabelText(/^name/i), 'Classic Black Abaya')
    await userEvent.type(screen.getByLabelText(/^price/i), '129.50')
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }))

    expect(await screen.findByText('Category is required')).toBeInTheDocument()
    expect(screen.getByText('Type is required')).toBeInTheDocument()
  })

  it('puts a server validation message on the field it names', async () => {
    server.use(
      http.post(`${API}/api/admin/products`, () =>
        problemResponse(400, {
          title: 'Validation failed',
          detail: 'Request validation failed',
          errors: [{ field: 'name', message: 'must not be blank' }],
        }),
      ),
    )
    renderPage()

    await userEvent.type(await screen.findByLabelText(/^name/i), 'x')
    await userEvent.type(screen.getByLabelText(/^price/i), '1.00')
    await userEvent.selectOptions(screen.getByLabelText('Category'), ABAYA_ID)
    await userEvent.selectOptions(screen.getByLabelText(/^type/i), ABAYA_TYPE_ID)
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }))

    expect(await screen.findByText('must not be blank')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd web/apps/admin
pnpm test NewProductPage
```

Expected: FAIL — `Failed to resolve import "./NewProductPage"`.

- [ ] **Step 3: Write the shared schema**

`web/apps/admin/src/features/products/productForm.ts`:

```ts
import { z } from 'zod'

/**
 * Price stays a string in the form. The column is `numeric(12,2)` and the backend enforces
 * `@Digits(integer = 10, fraction = 2)`, which is a rule about the *written* value — a number
 * would have already lost the distinction between `10.99` and `10.999`.
 */
const PRICE_PATTERN = /^\d{1,10}(\.\d{1,2})?$/

export const productSchema = z.object({
  name: z.string().min(1, 'Name is required').max(255, 'Must be 255 characters or fewer'),
  description: z.string().max(2000, 'Must be 2000 characters or fewer'),
  price: z
    .string()
    .min(1, 'Price is required')
    .regex(PRICE_PATTERN, 'Use up to ten digits and at most two decimal places'),
  categoryId: z.string().min(1, 'Category is required'),
  categoryTypeId: z.string().min(1, 'Type is required'),
})

export type ProductFormValues = z.infer<typeof productSchema>

export const PRODUCT_FIELDS = [
  'name',
  'description',
  'price',
  'categoryId',
  'categoryTypeId',
] as const

export function priceToNumber(price: string): number {
  return Number(price)
}
```

- [ ] **Step 4: Write the shared fields**

`web/apps/admin/src/features/products/ProductFields.tsx`:

```tsx
import type { Category } from '@shopflow/api-client'
import type { ReactElement } from 'react'
import type { UseFormReturn } from 'react-hook-form'
import { Link } from 'react-router'
import { Field, TextInput, inputClass } from '../../components/Field'
import type { ProductFormValues } from './productForm'

/**
 * The five fields both the create page and the detail page edit. The type select is derived from
 * the chosen category, which is what makes the 404 pair (a type from another category)
 * unrepresentable rather than merely validated.
 */
export function ProductFields({
  form,
  categories,
}: {
  form: UseFormReturn<ProductFormValues>
  categories: Category[]
}): ReactElement {
  const { register, watch, setValue, formState } = form
  const categoryId = watch('categoryId')
  const selected = categories.find((category) => category.id === categoryId)
  const types = selected?.types ?? []

  const categoryRegistration = register('categoryId')

  return (
    <div className="flex flex-col gap-4">
      <Field label="Name" htmlFor="product-name" required error={formState.errors.name?.message}>
        <TextInput
          id="product-name"
          invalid={formState.errors.name !== undefined}
          {...register('name')}
        />
      </Field>

      <Field
        label="Price"
        htmlFor="product-price"
        required
        error={formState.errors.price?.message}
        hint="US dollars, e.g. 129.50"
      >
        <TextInput
          id="product-price"
          inputMode="decimal"
          autoComplete="off"
          invalid={formState.errors.price !== undefined}
          {...register('price')}
        />
      </Field>

      <Field
        label="Category"
        htmlFor="product-category"
        required
        error={formState.errors.categoryId?.message}
      >
        <select
          id="product-category"
          className={inputClass}
          aria-invalid={formState.errors.categoryId !== undefined}
          {...categoryRegistration}
          onChange={(event) => {
            // A type from the old category would be a 404, so it goes the moment the category does.
            setValue('categoryTypeId', '', { shouldValidate: false })
            void categoryRegistration.onChange(event)
          }}
        >
          <option value="">Choose a category</option>
          {categories.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </select>
      </Field>

      <Field
        label="Type"
        htmlFor="product-type"
        required
        error={formState.errors.categoryTypeId?.message}
        hint={
          selected === undefined ? (
            'Choose a category first'
          ) : types.length === 0 ? (
            <>
              {`“${selected.name}” has no types yet. `}
              <Link to="/categories" className="font-medium underline">
                Add a type
              </Link>
              {' before creating a product here.'}
            </>
          ) : undefined
        }
      >
        <select
          id="product-type"
          className={inputClass}
          disabled={types.length === 0}
          aria-invalid={formState.errors.categoryTypeId !== undefined}
          {...register('categoryTypeId')}
        >
          <option value="">Choose a type</option>
          {types.map((type) => (
            <option key={type.id} value={type.id}>
              {type.name}
            </option>
          ))}
        </select>
      </Field>

      <Field
        label="Description"
        htmlFor="product-description"
        error={formState.errors.description?.message}
      >
        <textarea
          id="product-description"
          rows={4}
          className={inputClass}
          {...register('description')}
        />
      </Field>
    </div>
  )
}
```

- [ ] **Step 5: Add the create call and hook**

Append to `web/apps/admin/src/features/products/api.ts`, widening the import to
`{ request, type AdminProduct, type AdminProductPage, type CreateProductRequest }`:

```ts
/** 404 if `categoryTypeId` does not belong to `categoryId`; the form makes that unreachable. */
export function createProduct(body: CreateProductRequest): Promise<AdminProduct> {
  return request<AdminProduct>('/api/admin/products', { method: 'POST', body })
}
```

Append to `web/apps/admin/src/features/products/queries.ts`, widening the imports to include
`useMutation`, `useQueryClient`, `type UseMutationResult`, `type AdminProduct`,
`type CreateProductRequest` and `createProduct`:

```ts
export function useCreateProduct(): UseMutationResult<AdminProduct, unknown, CreateProductRequest> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: createProduct,
    // Every filtered list is now stale; the key prefix invalidates them all.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [PRODUCTS_QUERY_KEY] }),
  })
}
```

- [ ] **Step 6: Write the page**

`web/apps/admin/src/features/products/NewProductPage.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { Button } from '../../components/Button'
import { ErrorPanel, Skeleton } from '../../components/QueryStates'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { useCategories } from '../categories/queries'
import { ProductFields } from './ProductFields'
import { PRODUCT_FIELDS, priceToNumber, productSchema, type ProductFormValues } from './productForm'
import { useCreateProduct } from './queries'

export function NewProductPage(): ReactElement {
  const categories = useCategories()
  const create = useCreateProduct()
  const navigate = useNavigate()
  const showToast = useToast()
  const [formMessage, setFormMessage] = useState<string | null>(null)

  const form = useForm<ProductFormValues>({
    resolver: zodResolver(productSchema),
    defaultValues: { name: '', description: '', price: '', categoryId: '', categoryTypeId: '' },
  })

  const onSubmit = form.handleSubmit((values) => {
    setFormMessage(null)
    create.mutate(
      {
        name: values.name,
        description: values.description === '' ? undefined : values.description,
        price: priceToNumber(values.price),
        categoryId: values.categoryId,
        categoryTypeId: values.categoryTypeId,
      },
      {
        onSuccess: (product) => {
          showToast(`Product "${product.name}" created`)
          // Straight to the detail screen: variants and images are added there, and a product
          // with no variants cannot be sold.
          void navigate(`/products/${product.id}`, { replace: true })
        },
        onError: (error) =>
          setFormMessage(applyApiErrorToForm<ProductFormValues>(error, form.setError, PRODUCT_FIELDS)),
      },
    )
  })

  return (
    <section className="flex max-w-2xl flex-col gap-4">
      <header className="flex flex-col gap-1">
        <Link to="/products" className="text-sm text-slate-600 underline-offset-2 hover:underline">
          ← Back to products
        </Link>
        <h1 className="text-xl font-semibold text-slate-900">New product</h1>
        <p className="text-sm text-slate-600">
          Variants and images come next, on the product’s own page.
        </p>
      </header>

      {categories.isPending ? <Skeleton rows={4} label="Loading categories" /> : null}

      {categories.isError ? (
        <ErrorPanel error={categories.error} onRetry={() => void categories.refetch()} />
      ) : null}

      {categories.isSuccess ? (
        <form className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
          <ProductFields form={form} categories={categories.data} />

          {formMessage !== null ? (
            <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
              {formMessage}
            </p>
          ) : null}

          <div className="flex flex-col-reverse gap-2 md:flex-row md:justify-end">
            <Button variant="secondary" onClick={() => void navigate('/products')}>
              Cancel
            </Button>
            <Button type="submit" loading={create.isPending}>
              Create product
            </Button>
          </div>
        </form>
      ) : null}
    </section>
  )
}
```

- [ ] **Step 7: Add the route**

In `web/apps/admin/src/routes/router.tsx`, above the `/products` route is fine — the paths are literal, so order does not matter, but keep `new` next to its list:

```tsx
import { NewProductPage } from '../features/products/NewProductPage'
```

```tsx
      { path: '/products', element: <ProductsPage /> },
      { path: '/products/new', element: <NewProductPage /> },
```

- [ ] **Step 8: Run the tests to verify they pass**

```bash
cd web/apps/admin
pnpm test
pnpm typecheck
pnpm lint
```

Expected: PASS, 66 tests. `/products/:id` does not exist yet, so after a real create the app lands on the Not-found page until Task 11 — the test covers the redirect with a stub route.

- [ ] **Step 9: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add web/apps/admin
git commit -m "feat(admin): create a product with category-scoped type selection"
```

---

### Task 11: Product detail — edit the details, archive and restore

Delivers acceptance criterion 10. Three backend facts drive this screen:

1. `GET /api/admin/products/{id}` answers **200 for an archived product** (unlike the public endpoint), so the screen must render one and offer Restore rather than hiding it.
2. `DELETE /api/admin/products/{id}` **archives** — it sets `archivedAt`, keeps the row, and is idempotent. The button must never say "Delete".
3. `POST /api/admin/products/{id}/restore` **does not resurrect variants archived separately**. The confirmation says so, because an operator who assumes otherwise will publish a product with nothing buyable.

Task 12 adds the variants section to this page and Task 13 the images section; this task ends with a page whose Details card saves.

**Files:**
- Modify: `web/apps/admin/src/features/products/api.ts`
- Modify: `web/apps/admin/src/features/products/queries.ts`
- Create: `web/apps/admin/src/features/products/ProductDetailsCard.tsx`
- Create: `web/apps/admin/src/features/products/ProductDetailPage.tsx`
- Modify: `web/apps/admin/src/routes/router.tsx`
- Test: `web/apps/admin/src/features/products/ProductDetailPage.test.tsx`

**Interfaces:**
- Consumes: `AdminProduct`, `UpdateProductRequest` from `@shopflow/api-client`; `ProductFields`, `productSchema`, `PRODUCT_FIELDS`, `priceToNumber` from Task 10; `useCategories` from Task 7; `ConfirmDialog`, `Badge`, `Button`, `Skeleton`, `ErrorPanel`, `useToast`, `describeError`, `applyApiErrorToForm` from Task 5.
- Produces:
  - `function fetchProduct(id: string): Promise<AdminProduct>`
  - `function updateProduct(id: string, body: UpdateProductRequest): Promise<AdminProduct>`
  - `function archiveProduct(id: string): Promise<void>`
  - `function restoreProduct(id: string): Promise<AdminProduct>`
  - `function productQueryKey(id: string): unknown[]` — `[PRODUCTS_QUERY_KEY, 'detail', id]`
  - `function useProduct(id: string): UseQueryResult<AdminProduct>`
  - `function useUpdateProduct(id: string): UseMutationResult<AdminProduct, unknown, UpdateProductRequest>`
  - `function useArchiveProduct(id: string): UseMutationResult<void, unknown, void>`
  - `function useRestoreProduct(id: string): UseMutationResult<AdminProduct, unknown, void>`
  - `function ProductDetailsCard(props: { product: AdminProduct }): ReactElement`
  - `function ProductDetailPage(): ReactElement`

- [ ] **Step 1: Write the failing test**

`web/apps/admin/src/features/products/ProductDetailPage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { ProductDetailPage } from './ProductDetailPage'

const PRODUCT_ID = '77777777-7777-7777-7777-777777777777'
const ABAYA_ID = '33333333-3333-3333-3333-333333333333'
const ABAYA_TYPE_ID = '44444444-4444-4444-4444-444444444444'
const KIMONO_TYPE_ID = '66666666-6666-6666-6666-666666666666'

const CATEGORIES = [
  {
    id: ABAYA_ID,
    code: 'abaya',
    name: 'Abaya',
    types: [
      { id: ABAYA_TYPE_ID, code: 'abaya', name: 'Abaya' },
      { id: KIMONO_TYPE_ID, code: 'kimono', name: 'Kimono' },
    ],
  },
]

const PRODUCT = {
  id: PRODUCT_ID,
  name: 'Classic Black Abaya',
  description: 'Crepe, full length',
  price: 129.5,
  categoryId: ABAYA_ID,
  categoryName: 'Abaya',
  categoryTypeId: ABAYA_TYPE_ID,
  categoryTypeName: 'Abaya',
  variants: [],
  resources: [],
}

beforeEach(() => {
  server.use(
    http.get(`${API}/api/categories`, () => HttpResponse.json(CATEGORIES)),
    http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () => HttpResponse.json(PRODUCT)),
  )
})

function renderPage() {
  return renderWithProviders(<ProductDetailPage />, {
    route: `/products/${PRODUCT_ID}`,
    path: '/products/:id',
    extraRoutes: [{ path: '/products', element: <p>Product list</p> }],
  })
}

describe('ProductDetailPage', () => {
  it('loads the product into an editable Details card', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { level: 1, name: 'Classic Black Abaya' })).toBeInTheDocument()
    expect(screen.getByLabelText(/^name/i)).toHaveValue('Classic Black Abaya')
    expect(screen.getByLabelText(/^price/i)).toHaveValue('129.50')
    expect(screen.getByLabelText('Category')).toHaveValue(ABAYA_ID)
    expect(screen.getByLabelText(/^type/i)).toHaveValue(ABAYA_TYPE_ID)
    expect(screen.getByLabelText(/^description/i)).toHaveValue('Crepe, full length')
  })

  it('patches only the fields that changed', async () => {
    const patched: unknown[] = []
    server.use(
      http.patch(`${API}/api/admin/products/${PRODUCT_ID}`, async ({ request }) => {
        patched.push(await request.json())
        return HttpResponse.json({ ...PRODUCT, name: 'Classic Abaya' })
      }),
    )
    renderPage()

    const name = await screen.findByLabelText(/^name/i)
    await userEvent.clear(name)
    await userEvent.type(name, 'Classic Abaya')
    await userEvent.click(screen.getByRole('button', { name: 'Save details' }))

    await waitFor(() => expect(patched).toEqual([{ name: 'Classic Abaya' }]))
    expect(await screen.findByText('Product updated')).toBeInTheDocument()
  })

  it('sends the category and type together when either changes', async () => {
    const patched: unknown[] = []
    server.use(
      http.patch(`${API}/api/admin/products/${PRODUCT_ID}`, async ({ request }) => {
        patched.push(await request.json())
        return HttpResponse.json(PRODUCT)
      }),
    )
    renderPage()

    await userEvent.selectOptions(await screen.findByLabelText(/^type/i), KIMONO_TYPE_ID)
    await userEvent.click(screen.getByRole('button', { name: 'Save details' }))

    await waitFor(() =>
      expect(patched).toEqual([{ categoryId: ABAYA_ID, categoryTypeId: KIMONO_TYPE_ID }]),
    )
  })

  it('archives rather than deletes, and says so', async () => {
    let archived = false
    server.use(
      http.delete(`${API}/api/admin/products/${PRODUCT_ID}`, () => {
        archived = true
        return new HttpResponse(null, { status: 204 })
      }),
      http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () =>
        HttpResponse.json(archived ? { ...PRODUCT, archivedAt: '2026-08-17T09:00:00Z' } : PRODUCT),
      ),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Archive product' }))

    const dialog = screen.getByRole('dialog')
    expect(
      within(dialog).getByText(
        /Archiving hides "Classic Black Abaya" from customers\. Nothing is deleted and you can restore it later\./,
      ),
    ).toBeInTheDocument()

    await userEvent.click(within(dialog).getByRole('button', { name: 'Archive' }))

    await waitFor(() => expect(archived).toBe(true))
    expect(await screen.findByText('Archived')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Restore product' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Archive product' })).not.toBeInTheDocument()
  })

  it('warns that restoring does not bring separately archived variants back', async () => {
    server.use(
      http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () =>
        HttpResponse.json({ ...PRODUCT, archivedAt: '2026-08-17T09:00:00Z' }),
      ),
      http.post(`${API}/api/admin/products/${PRODUCT_ID}/restore`, () => HttpResponse.json(PRODUCT)),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Restore product' }))

    expect(
      screen.getByText(
        /Restoring shows "Classic Black Abaya" to customers again\. Variants archived separately stay archived\./,
      ),
    ).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Restore' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText('Product restored')).toBeInTheDocument()
  })

  it('explains a missing product instead of an empty form', async () => {
    server.use(
      http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () =>
        problemResponse(404, { title: 'Not Found', detail: 'Product not found' }),
      ),
    )
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Not found' })).toBeInTheDocument()
    expect(screen.getByText('Product not found')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to products' })).toHaveAttribute('href', '/products')
    // No retry for a 404 and no form to mislead anyone.
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/^name/i)).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd web/apps/admin
pnpm test ProductDetailPage
```

Expected: FAIL — `Failed to resolve import "./ProductDetailPage"`.

- [ ] **Step 3: Add the API calls**

Append to `web/apps/admin/src/features/products/api.ts`, widening the import to include
`type UpdateProductRequest`:

```ts
/** Answers 200 for an archived product and includes archived variants. */
export function fetchProduct(id: string): Promise<AdminProduct> {
  return request<AdminProduct>(`/api/admin/products/${id}`)
}

export function updateProduct(id: string, body: UpdateProductRequest): Promise<AdminProduct> {
  return request<AdminProduct>(`/api/admin/products/${id}`, { method: 'PATCH', body })
}

/** Archives: sets archivedAt, keeps the row, 204, idempotent. Not a delete. */
export function archiveProduct(id: string): Promise<void> {
  return request<void>(`/api/admin/products/${id}`, { method: 'DELETE' })
}

/** Does not resurrect variants archived separately. */
export function restoreProduct(id: string): Promise<AdminProduct> {
  return request<AdminProduct>(`/api/admin/products/${id}/restore`, { method: 'POST' })
}
```

- [ ] **Step 4: Add the detail query and its mutations**

Append to `web/apps/admin/src/features/products/queries.ts`, widening the imports to include
`archiveProduct`, `fetchProduct`, `restoreProduct`, `updateProduct` and `type UpdateProductRequest`:

```ts
export function productQueryKey(id: string): unknown[] {
  return [PRODUCTS_QUERY_KEY, 'detail', id]
}

export function useProduct(id: string): UseQueryResult<AdminProduct> {
  return useQuery({
    queryKey: productQueryKey(id),
    queryFn: () => fetchProduct(id),
  })
}

/**
 * Each of these writes the server's response straight into the detail cache and invalidates the
 * lists. Nothing is patched optimistically: `variantCount` and `totalStock` are computed
 * server-side, so a guess here would be wrong as often as right.
 */
export function useUpdateProduct(
  id: string,
): UseMutationResult<AdminProduct, unknown, UpdateProductRequest> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (body: UpdateProductRequest) => updateProduct(id, body),
    onSuccess: (product) => {
      queryClient.setQueryData(productQueryKey(id), product)
      void queryClient.invalidateQueries({ queryKey: [PRODUCTS_QUERY_KEY] })
    },
  })
}

export function useArchiveProduct(id: string): UseMutationResult<void, unknown, void> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: () => archiveProduct(id),
    // 204 carries no body, so refetch rather than invent an archivedAt.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [PRODUCTS_QUERY_KEY] }),
  })
}

export function useRestoreProduct(id: string): UseMutationResult<AdminProduct, unknown, void> {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: () => restoreProduct(id),
    onSuccess: (product) => {
      queryClient.setQueryData(productQueryKey(id), product)
      void queryClient.invalidateQueries({ queryKey: [PRODUCTS_QUERY_KEY] })
    },
  })
}
```

- [ ] **Step 5: Write the Details card**

`web/apps/admin/src/features/products/ProductDetailsCard.tsx`:

```tsx
import type { AdminProduct, UpdateProductRequest } from '@shopflow/api-client'
import { useEffect, useState, type ReactElement } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Button } from '../../components/Button'
import { ErrorPanel, Skeleton } from '../../components/QueryStates'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { useCategories } from '../categories/queries'
import { ProductFields } from './ProductFields'
import { PRODUCT_FIELDS, priceToNumber, productSchema, type ProductFormValues } from './productForm'
import { useUpdateProduct } from './queries'

/** The form holds price as a string, and this is the only place the number becomes one. */
function toFormValues(product: AdminProduct): ProductFormValues {
  return {
    name: product.name,
    description: product.description ?? '',
    price: product.price.toFixed(2),
    categoryId: product.categoryId,
    categoryTypeId: product.categoryTypeId,
  }
}

export function ProductDetailsCard({ product }: { product: AdminProduct }): ReactElement {
  const categories = useCategories()
  const update = useUpdateProduct(product.id)
  const showToast = useToast()
  const [formMessage, setFormMessage] = useState<string | null>(null)

  const form = useForm<ProductFormValues>({
    resolver: zodResolver(productSchema),
    defaultValues: toFormValues(product),
  })

  // A refetch or a restore replaces the product; the form follows unless the operator is mid-edit.
  useEffect(() => {
    if (!form.formState.isDirty) form.reset(toFormValues(product))
  }, [product, form])

  const onSubmit = form.handleSubmit((values) => {
    setFormMessage(null)
    const dirty = form.formState.dirtyFields
    const body: UpdateProductRequest = {}

    if (dirty.name === true) body.name = values.name
    // '' is meaningful: the backend leaves a null field alone and applies an empty string, so this
    // is how a description is cleared.
    if (dirty.description === true) body.description = values.description
    if (dirty.price === true) body.price = priceToNumber(values.price)
    // The pair is validated together server-side; sending one alone would validate the new value
    // against the stored other one, which is exactly the 404 we designed out.
    if (dirty.categoryId === true || dirty.categoryTypeId === true) {
      body.categoryId = values.categoryId
      body.categoryTypeId = values.categoryTypeId
    }

    if (Object.keys(body).length === 0) {
      showToast('Nothing to save')
      return
    }

    update.mutate(body, {
      onSuccess: (updated) => {
        showToast('Product updated')
        form.reset(toFormValues(updated))
      },
      onError: (error) =>
        setFormMessage(applyApiErrorToForm<ProductFormValues>(error, form.setError, PRODUCT_FIELDS)),
    })
  })

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-lg font-semibold text-slate-900">Details</h2>

      {categories.isPending ? <Skeleton rows={4} label="Loading categories" /> : null}

      {categories.isError ? (
        <ErrorPanel error={categories.error} onRetry={() => void categories.refetch()} />
      ) : null}

      {categories.isSuccess ? (
        <form className="mt-4 flex flex-col gap-4" onSubmit={onSubmit} noValidate>
          <ProductFields form={form} categories={categories.data} />

          {formMessage !== null ? (
            <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
              {formMessage}
            </p>
          ) : null}

          <div className="flex justify-end">
            <Button type="submit" loading={update.isPending}>
              Save details
            </Button>
          </div>
        </form>
      ) : null}
    </section>
  )
}
```

- [ ] **Step 6: Write the page**

`web/apps/admin/src/features/products/ProductDetailPage.tsx`:

```tsx
import { useState, type ReactElement } from 'react'
import { Link, useParams } from 'react-router'
import { Badge } from '../../components/Badge'
import { Button } from '../../components/Button'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { ErrorPanel, Skeleton } from '../../components/QueryStates'
import { useToast } from '../../components/Toast'
import { describeError } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { ProductDetailsCard } from './ProductDetailsCard'
import { useArchiveProduct, useProduct, useRestoreProduct } from './queries'

export function ProductDetailPage(): ReactElement {
  const { id = '' } = useParams<{ id: string }>()
  const product = useProduct(id)
  const archive = useArchiveProduct(id)
  const restore = useRestoreProduct(id)
  const showToast = useToast()
  const [confirming, setConfirming] = useState<'archive' | 'restore' | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  const close = (): void => {
    setConfirming(null)
    setFailure(null)
  }

  if (product.isPending) return <Skeleton rows={6} label="Loading product" />

  if (product.isError) {
    return (
      <div className="flex flex-col gap-3">
        <ErrorPanel
          error={product.error}
          onRetry={describeError(product.error).retryable ? () => void product.refetch() : undefined}
        />
        <Link to="/products" className="text-sm font-medium text-slate-700 underline">
          Back to products
        </Link>
      </div>
    )
  }

  const archivedAt = product.data.archivedAt

  const onArchive = (): void => {
    setFailure(null)
    archive.mutate(undefined, {
      onSuccess: () => {
        showToast('Product archived')
        close()
      },
      onError: (error) => setFailure(describeError(error).body),
    })
  }

  const onRestore = (): void => {
    setFailure(null)
    restore.mutate(undefined, {
      onSuccess: () => {
        showToast('Product restored')
        close()
      },
      onError: (error) => setFailure(describeError(error).body),
    })
  }

  return (
    <section className="flex max-w-3xl flex-col gap-4">
      <Link to="/products" className="text-sm text-slate-600 underline-offset-2 hover:underline">
        ← Back to products
      </Link>

      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold text-slate-900">{product.data.name}</h1>
          <p className="mt-1 text-sm text-slate-600">
            {`${product.data.categoryName} / ${product.data.categoryTypeName}`}
          </p>
          {archivedAt !== undefined ? (
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <Badge tone="neutral">Archived</Badge>
              <span className="text-xs text-slate-500">{`since ${formatDateTime(archivedAt)}`}</span>
            </div>
          ) : null}
        </div>

        {archivedAt !== undefined ? (
          <Button variant="secondary" onClick={() => setConfirming('restore')}>
            Restore product
          </Button>
        ) : (
          <Button variant="danger" onClick={() => setConfirming('archive')}>
            Archive product
          </Button>
        )}
      </header>

      <ProductDetailsCard product={product.data} />

      {/* Task 12 adds the variants section here and Task 13 the images section. */}

      <ConfirmDialog
        open={confirming === 'archive'}
        title="Archive this product?"
        confirmLabel="Archive"
        destructive
        busy={archive.isPending}
        error={failure}
        onConfirm={onArchive}
        onCancel={close}
      >
        {`Archiving hides "${product.data.name}" from customers. Nothing is deleted and you can restore it later.`}
      </ConfirmDialog>

      <ConfirmDialog
        open={confirming === 'restore'}
        title="Restore this product?"
        confirmLabel="Restore"
        busy={restore.isPending}
        error={failure}
        onConfirm={onRestore}
        onCancel={close}
      >
        {`Restoring shows "${product.data.name}" to customers again. Variants archived separately stay archived.`}
      </ConfirmDialog>
    </section>
  )
}
```

- [ ] **Step 7: Add the route**

In `web/apps/admin/src/routes/router.tsx`:

```tsx
import { ProductDetailPage } from '../features/products/ProductDetailPage'
```

```tsx
      { path: '/products', element: <ProductsPage /> },
      { path: '/products/new', element: <NewProductPage /> },
      { path: '/products/:id', element: <ProductDetailPage /> },
```

- [ ] **Step 8: Run the tests to verify they pass**

```bash
cd web/apps/admin
pnpm test
pnpm typecheck
pnpm lint
```

Expected: PASS, 72 tests.

- [ ] **Step 9: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add web/apps/admin
git commit -m "feat(admin): product detail with dirty-field PATCH and archive/restore"
```

---

### Task 12: Variants and the delta-only stock dialog

Delivers acceptance criteria 7 and 8. This is the task where the backend's design is most easily fought against, so hold these four lines:

1. `CreateVariantRequest.stockQuantity` is an **opening balance only**.
2. `UpdateVariantRequest` carries **colour and size, nothing else** — there is no `stockQuantity` field to send.
3. Every later change goes through `POST /api/admin/variants/{id}/stock` as a **signed delta with a mandatory reason**. The operator thinks in target quantities, so the dialog asks for a new quantity and computes `delta = target − current` itself.
4. `delta === 0` is rejected **before any request**: the backend's `AssertTrue` would answer 400, and a round trip to learn "nothing changed" is a worse experience than saying so instantly. The spurious `deltaNonZero` property in the generated schema is never sent — `AdjustStockRequest` in `@shopflow/api-client` omits it.

A negative result comes back as `409 Insufficient stock` with the server's arithmetic in `detail` ("Variant … holds 4, so a change of -9 would leave -5"), which is more useful than anything the UI could phrase, so it is shown verbatim.

Variants live at `/api/admin/variants/{id}` — like category types, they are created under their parent and then addressed by their own id.

**Files:**
- Create: `web/apps/admin/src/features/variants/api.ts`
- Create: `web/apps/admin/src/features/variants/queries.ts`
- Create: `web/apps/admin/src/features/variants/VariantFormDialog.tsx`
- Create: `web/apps/admin/src/features/variants/StockDialog.tsx`
- Create: `web/apps/admin/src/features/variants/VariantsCard.tsx`
- Modify: `web/apps/admin/src/features/products/ProductDetailPage.tsx`
- Test: `web/apps/admin/src/features/variants/StockDialog.test.tsx`
- Test: `web/apps/admin/src/features/variants/VariantsCard.test.tsx`

**Interfaces:**
- Consumes: `AdminVariant`, `AdjustStockRequest`, `CreateVariantRequest`, `UpdateVariantRequest`, `StockAdjustment` from `@shopflow/api-client`; `productQueryKey`, `PRODUCTS_QUERY_KEY` from Task 11; `Dialog`, `ConfirmDialog`, `Badge`, `Button`, `Field`, `TextInput`, `useToast`, `describeError`, `applyApiErrorToForm` from Task 5.
- Produces:
  - `function createVariant(productId: string, body: CreateVariantRequest): Promise<AdminVariant>`
  - `function updateVariant(variantId: string, body: UpdateVariantRequest): Promise<AdminVariant>`
  - `function archiveVariant(variantId: string): Promise<void>`
  - `function restoreVariant(variantId: string): Promise<AdminVariant>`
  - `function adjustStock(variantId: string, body: AdjustStockRequest): Promise<StockAdjustment>`
  - `function useCreateVariant(productId: string)`, `useUpdateVariant(productId: string)`, `useArchiveVariant(productId: string)`, `useRestoreVariant(productId: string)`, `useAdjustStock(productId: string)` — every one invalidates `productQueryKey(productId)` and the list prefix
  - `function VariantFormDialog(props: { open: boolean; onClose: () => void; productId: string; variant?: AdminVariant }): ReactElement | null`
  - `function StockDialog(props: { open: boolean; onClose: () => void; productId: string; variant: AdminVariant }): ReactElement | null`
  - `function VariantsCard(props: { product: AdminProduct }): ReactElement`

- [ ] **Step 1: Write the failing stock-dialog test**

`web/apps/admin/src/features/variants/StockDialog.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { API, HttpResponse, http, problemResponse, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { StockDialog } from './StockDialog'

const PRODUCT_ID = '77777777-7777-7777-7777-777777777777'
const VARIANT_ID = 'cccccccc-cccc-cccc-cccc-cccccccccccc'

const VARIANT = { id: VARIANT_ID, color: 'Black', size: 'M', stockQuantity: 4 }

function renderDialog() {
  return renderWithProviders(
    <StockDialog open onClose={() => {}} productId={PRODUCT_ID} variant={VARIANT} />,
    { route: `/products/${PRODUCT_ID}`, path: '/products/:id' },
  )
}

describe('StockDialog', () => {
  it('turns a target quantity into a signed delta', async () => {
    const posted: unknown[] = []
    server.use(
      http.post(`${API}/api/admin/variants/${VARIANT_ID}/stock`, async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json({
          variantId: VARIANT_ID,
          previousQuantity: 4,
          newQuantity: 10,
          delta: 6,
          reason: 'Stock take',
        })
      }),
      http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () =>
        HttpResponse.json({ id: PRODUCT_ID, variants: [], resources: [] }),
      ),
    )
    renderDialog()

    await userEvent.clear(screen.getByLabelText('New quantity'))
    await userEvent.type(screen.getByLabelText('New quantity'), '10')

    // The arithmetic is shown before it is sent, so nobody has to trust it blindly.
    expect(screen.getByText('Adds 6 (4 → 10)')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Reason'), 'Stock take')
    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock' }))

    await waitFor(() => expect(posted).toEqual([{ delta: 6, reason: 'Stock take' }]))
    // No `deltaNonZero`: the generated schema exposes it, the request type omits it.
    expect(Object.keys(posted[0] as object)).toEqual(['delta', 'reason'])
  })

  it('sends a negative delta when the count went down', async () => {
    const posted: unknown[] = []
    server.use(
      http.post(`${API}/api/admin/variants/${VARIANT_ID}/stock`, async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json({
          variantId: VARIANT_ID,
          previousQuantity: 4,
          newQuantity: 1,
          delta: -3,
          reason: 'Damaged',
        })
      }),
      http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () =>
        HttpResponse.json({ id: PRODUCT_ID, variants: [], resources: [] }),
      ),
    )
    renderDialog()

    await userEvent.clear(screen.getByLabelText('New quantity'))
    await userEvent.type(screen.getByLabelText('New quantity'), '1')
    expect(screen.getByText('Removes 3 (4 → 1)')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Reason'), 'Damaged')
    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock' }))

    await waitFor(() => expect(posted).toEqual([{ delta: -3, reason: 'Damaged' }]))
  })

  it('refuses a zero delta without touching the network', async () => {
    // No stock handler is registered; onUnhandledRequest: 'error' makes any request a failure.
    renderDialog()

    await userEvent.type(screen.getByLabelText('Reason'), 'No change')
    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock' }))

    expect(
      await screen.findByText('That is the current quantity — nothing to adjust'),
    ).toBeInTheDocument()
  })

  it('requires a reason, because it is the only record of why stock moved', async () => {
    renderDialog()

    await userEvent.clear(screen.getByLabelText('New quantity'))
    await userEvent.type(screen.getByLabelText('New quantity'), '10')
    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock' }))

    expect(await screen.findByText('A reason is required')).toBeInTheDocument()
  })

  it('shows the server’s own arithmetic when the result would go negative', async () => {
    server.use(
      http.post(`${API}/api/admin/variants/${VARIANT_ID}/stock`, () =>
        problemResponse(409, {
          title: 'Insufficient stock',
          detail: 'Variant holds 4, so a change of -9 would leave -5',
        }),
      ),
    )
    renderDialog()

    await userEvent.clear(screen.getByLabelText('New quantity'))
    await userEvent.type(screen.getByLabelText('New quantity'), '0')
    await userEvent.type(screen.getByLabelText('Reason'), 'Write-off')
    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock' }))

    expect(
      await screen.findByText('Variant holds 4, so a change of -9 would leave -5'),
    ).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Write the failing variants-card test**

`web/apps/admin/src/features/variants/VariantsCard.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { API, HttpResponse, http, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { VariantsCard } from './VariantsCard'

const PRODUCT_ID = '77777777-7777-7777-7777-777777777777'
const LIVE_ID = 'cccccccc-cccc-cccc-cccc-cccccccccccc'
const ARCHIVED_ID = 'dddddddd-dddd-dddd-dddd-dddddddddddd'

const PRODUCT = {
  id: PRODUCT_ID,
  name: 'Classic Black Abaya',
  price: 129.5,
  categoryId: '33333333-3333-3333-3333-333333333333',
  categoryName: 'Abaya',
  categoryTypeId: '44444444-4444-4444-4444-444444444444',
  categoryTypeName: 'Abaya',
  variants: [
    { id: LIVE_ID, color: 'Black', size: 'M', stockQuantity: 4 },
    { id: ARCHIVED_ID, color: 'Sand', size: 'L', stockQuantity: 0, archivedAt: '2026-08-01T10:00:00Z' },
  ],
  resources: [],
}

beforeEach(() => {
  server.use(
    http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () => HttpResponse.json(PRODUCT)),
  )
})

function renderCard(product = PRODUCT) {
  return renderWithProviders(<VariantsCard product={product} />, {
    route: `/products/${PRODUCT_ID}`,
    path: '/products/:id',
  })
}

describe('VariantsCard', () => {
  it('warns that a product with no variants cannot be bought', () => {
    renderCard({ ...PRODUCT, variants: [] })

    expect(screen.getByText('No variants yet — customers cannot buy this product')).toBeInTheDocument()
  })

  it('lists live and archived variants and labels the archived one', () => {
    renderCard()

    const live = screen.getByRole('listitem', { name: 'Black / M' })
    expect(within(live).getByText('4 in stock')).toBeInTheDocument()
    expect(within(live).queryByText('Archived')).not.toBeInTheDocument()

    const archived = screen.getByRole('listitem', { name: 'Sand / L' })
    expect(within(archived).getByText('Archived')).toBeInTheDocument()
    // Stock cannot move on an archived variant, so the control is not offered.
    expect(within(archived).queryByRole('button', { name: 'Adjust stock for Sand / L' })).not.toBeInTheDocument()
    expect(within(archived).getByRole('button', { name: 'Restore Sand / L' })).toBeInTheDocument()
  })

  it('creates a variant with an opening balance', async () => {
    const posted: unknown[] = []
    server.use(
      http.post(`${API}/api/admin/products/${PRODUCT_ID}/variants`, async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json({ id: 'new', color: 'Navy', size: 'S', stockQuantity: 7 }, { status: 201 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Add variant' }))
    await userEvent.type(screen.getByLabelText(/^colour/i), 'Navy')
    await userEvent.type(screen.getByLabelText(/^size/i), 'S')
    await userEvent.clear(screen.getByLabelText(/^opening stock/i))
    await userEvent.type(screen.getByLabelText(/^opening stock/i), '7')
    // The card's trigger and the dialog's submit share a label, so scope the click to the dialog.
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Add variant' }),
    )

    await waitFor(() => expect(posted).toEqual([{ color: 'Navy', size: 'S', stockQuantity: 7 }]))
  })

  it('edits only colour and size — there is no stock field to send', async () => {
    const patched: unknown[] = []
    server.use(
      http.patch(`${API}/api/admin/variants/${LIVE_ID}`, async ({ request }) => {
        patched.push(await request.json())
        return HttpResponse.json({ id: LIVE_ID, color: 'Jet Black', size: 'M', stockQuantity: 4 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Edit Black / M' }))

    expect(screen.queryByLabelText(/stock/i)).not.toBeInTheDocument()

    await userEvent.clear(screen.getByLabelText(/^colour/i))
    await userEvent.type(screen.getByLabelText(/^colour/i), 'Jet Black')
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(patched).toEqual([{ color: 'Jet Black', size: 'M' }]))
  })

  it('archives a variant and reports the change', async () => {
    let archived = false
    server.use(
      http.delete(`${API}/api/admin/variants/${LIVE_ID}`, () => {
        archived = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Archive Black / M' }))
    expect(
      screen.getByText(/Archiving "Black \/ M" removes it from sale\. Its stock and history are kept\./),
    ).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Archive' }))

    await waitFor(() => expect(archived).toBe(true))
    expect(await screen.findByText('Variant archived')).toBeInTheDocument()
  })

  it('reports the server’s before and after quantities after an adjustment', async () => {
    server.use(
      http.post(`${API}/api/admin/variants/${LIVE_ID}/stock`, () =>
        HttpResponse.json({
          variantId: LIVE_ID,
          previousQuantity: 4,
          newQuantity: 10,
          delta: 6,
          reason: 'Stock take',
        }),
      ),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock for Black / M' }))
    await userEvent.clear(screen.getByLabelText('New quantity'))
    await userEvent.type(screen.getByLabelText('New quantity'), '10')
    await userEvent.type(screen.getByLabelText('Reason'), 'Stock take')
    await userEvent.click(screen.getByRole('button', { name: 'Adjust stock' }))

    // The server's numbers, not the UI's guess.
    expect(await screen.findByText('Black / M stock 4 → 10')).toBeInTheDocument()
  })
})
```

- [ ] **Step 3: Run both to verify they fail**

```bash
cd web/apps/admin
pnpm test variants
```

Expected: FAIL — `Failed to resolve import "./StockDialog"` and `"./VariantsCard"`.

- [ ] **Step 4: Write the API calls**

`web/apps/admin/src/features/variants/api.ts`:

```ts
import {
  request,
  type AdjustStockRequest,
  type AdminVariant,
  type CreateVariantRequest,
  type StockAdjustment,
  type UpdateVariantRequest,
} from '@shopflow/api-client'

/** `stockQuantity` here is an opening balance; every later change is a delta. */
export function createVariant(
  productId: string,
  body: CreateVariantRequest,
): Promise<AdminVariant> {
  return request<AdminVariant>(`/api/admin/products/${productId}/variants`, { method: 'POST', body })
}

/** Colour and size only — `UpdateVariantRequest` has no stock field by design. */
export function updateVariant(
  variantId: string,
  body: UpdateVariantRequest,
): Promise<AdminVariant> {
  return request<AdminVariant>(`/api/admin/variants/${variantId}`, { method: 'PATCH', body })
}

export function archiveVariant(variantId: string): Promise<void> {
  return request<void>(`/api/admin/variants/${variantId}`, { method: 'DELETE' })
}

export function restoreVariant(variantId: string): Promise<AdminVariant> {
  return request<AdminVariant>(`/api/admin/variants/${variantId}/restore`, { method: 'POST' })
}

/**
 * A signed, non-zero delta with a reason. The row is locked server-side, so this is the only safe
 * way to move stock; 409 `Insufficient stock` when the result would be negative.
 */
export function adjustStock(
  variantId: string,
  body: AdjustStockRequest,
): Promise<StockAdjustment> {
  return request<StockAdjustment>(`/api/admin/variants/${variantId}/stock`, { method: 'POST', body })
}
```

- [ ] **Step 5: Write the query hooks**

`web/apps/admin/src/features/variants/queries.ts`:

```ts
import type {
  AdjustStockRequest,
  AdminVariant,
  CreateVariantRequest,
  StockAdjustment,
  UpdateVariantRequest,
} from '@shopflow/api-client'
import { useMutation, useQueryClient, type UseMutationResult } from '@tanstack/react-query'
import { PRODUCTS_QUERY_KEY, productQueryKey } from '../products/queries'
import { adjustStock, archiveVariant, createVariant, restoreVariant, updateVariant } from './api'

/**
 * Every variant change alters the parent product's `variantCount` and `totalStock`, which are
 * computed server-side — so each mutation refetches the product rather than patching a cache.
 */
function useVariantInvalidation(productId: string): () => void {
  const queryClient = useQueryClient()

  return () => {
    void queryClient.invalidateQueries({ queryKey: productQueryKey(productId) })
    void queryClient.invalidateQueries({ queryKey: [PRODUCTS_QUERY_KEY] })
  }
}

export function useCreateVariant(
  productId: string,
): UseMutationResult<AdminVariant, unknown, CreateVariantRequest> {
  const invalidate = useVariantInvalidation(productId)

  return useMutation({
    mutationFn: (body: CreateVariantRequest) => createVariant(productId, body),
    onSuccess: invalidate,
  })
}

export function useUpdateVariant(
  productId: string,
): UseMutationResult<AdminVariant, unknown, { variantId: string; body: UpdateVariantRequest }> {
  const invalidate = useVariantInvalidation(productId)

  return useMutation({
    mutationFn: ({ variantId, body }) => updateVariant(variantId, body),
    onSuccess: invalidate,
  })
}

export function useArchiveVariant(productId: string): UseMutationResult<void, unknown, string> {
  const invalidate = useVariantInvalidation(productId)

  return useMutation({ mutationFn: archiveVariant, onSuccess: invalidate })
}

export function useRestoreVariant(
  productId: string,
): UseMutationResult<AdminVariant, unknown, string> {
  const invalidate = useVariantInvalidation(productId)

  return useMutation({ mutationFn: restoreVariant, onSuccess: invalidate })
}

export function useAdjustStock(
  productId: string,
): UseMutationResult<StockAdjustment, unknown, { variantId: string; body: AdjustStockRequest }> {
  const invalidate = useVariantInvalidation(productId)

  return useMutation({
    mutationFn: ({ variantId, body }) => adjustStock(variantId, body),
    onSuccess: invalidate,
  })
}
```

- [ ] **Step 6: Write the stock dialog**

`web/apps/admin/src/features/variants/StockDialog.tsx`:

```tsx
import type { AdminVariant } from '@shopflow/api-client'
import { useEffect, useState, type FormEvent, type ReactElement } from 'react'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Field, TextInput, inputClass } from '../../components/Field'
import { useToast } from '../../components/Toast'
import { describeError } from '../../lib/errors'
import { useAdjustStock } from './queries'

const QUANTITY_PATTERN = /^\d{1,9}$/

/**
 * The operator counts stock; the API takes movements. This dialog is the translation layer: it asks
 * for the quantity on the shelf and posts `target - current`, so nobody has to do signed mental
 * arithmetic at a stock take.
 */
export function StockDialog({
  open,
  onClose,
  productId,
  variant,
}: {
  open: boolean
  onClose: () => void
  productId: string
  variant: AdminVariant
}): ReactElement | null {
  const [target, setTarget] = useState(String(variant.stockQuantity))
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const adjust = useAdjustStock(productId)
  const showToast = useToast()

  useEffect(() => {
    if (!open) return
    setTarget(String(variant.stockQuantity))
    setReason('')
    setError(null)
  }, [open, variant.stockQuantity])

  const valid = QUANTITY_PATTERN.test(target)
  const delta = valid ? Number(target) - variant.stockQuantity : null

  const onSubmit = (event: FormEvent): void => {
    event.preventDefault()
    setError(null)

    if (delta === null) {
      setError('Enter a whole number of items')
      return
    }
    if (reason.trim() === '') {
      setError('A reason is required')
      return
    }
    // The backend's AssertTrue would answer 400; saying it here is faster and clearer.
    if (delta === 0) {
      setError('That is the current quantity — nothing to adjust')
      return
    }

    adjust.mutate(
      { variantId: variant.id, body: { delta, reason: reason.trim() } },
      {
        onSuccess: (adjustment) => {
          // The server's own before/after, not the numbers this dialog assumed.
          showToast(
            `${variant.color} / ${variant.size} stock ${adjustment.previousQuantity} → ${adjustment.newQuantity}`,
          )
          onClose()
        },
        // A 409 Insufficient stock carries the arithmetic in `detail`; show it verbatim.
        onError: (cause) => setError(describeError(cause).body),
      },
    )
  }

  return (
    <Dialog
      open={open}
      title={`Adjust stock — ${variant.color} / ${variant.size}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={adjust.isPending}>
            Cancel
          </Button>
          <Button type="submit" form="stock-form" loading={adjust.isPending}>
            Adjust stock
          </Button>
        </>
      }
    >
      <form id="stock-form" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <p className="text-sm text-slate-600">{`Currently ${variant.stockQuantity} in stock.`}</p>

        <Field label="New quantity" htmlFor="stock-target" required>
          <TextInput
            id="stock-target"
            inputMode="numeric"
            autoComplete="off"
            value={target}
            onChange={(event) => setTarget(event.target.value)}
          />
        </Field>

        <p className="text-sm font-medium text-slate-700">
          {delta === null
            ? 'Enter a whole number of items'
            : delta === 0
              ? 'No change'
              : delta > 0
                ? `Adds ${delta} (${variant.stockQuantity} → ${variant.stockQuantity + delta})`
                : `Removes ${Math.abs(delta)} (${variant.stockQuantity} → ${variant.stockQuantity + delta})`}
        </p>

        <Field
          label="Reason"
          htmlFor="stock-reason"
          required
          hint="Recorded in the audit trail — it is the only record of why stock moved."
        >
          <textarea
            id="stock-reason"
            rows={2}
            maxLength={500}
            className={inputClass}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </Field>

        {error !== null ? (
          <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
            {error}
          </p>
        ) : null}
      </form>
    </Dialog>
  )
}
```

- [ ] **Step 7: Write the variant form dialog**

`web/apps/admin/src/features/variants/VariantFormDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import type { AdminVariant } from '@shopflow/api-client'
import { useEffect, useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Field, TextInput } from '../../components/Field'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { useCreateVariant, useUpdateVariant } from './queries'

/**
 * `openingStock` exists only on create. Edit mode has no stock control at all, because
 * `UpdateVariantRequest` has no field for it — the absence is the enforcement.
 */
const variantSchema = z.object({
  color: z.string().min(1, 'Colour is required').max(60, 'Must be 60 characters or fewer'),
  size: z.string().min(1, 'Size is required').max(30, 'Must be 30 characters or fewer'),
  openingStock: z
    .string()
    .regex(/^\d{1,9}$/, 'Enter a whole number of items, 0 or more'),
})

type VariantValues = z.infer<typeof variantSchema>

const VARIANT_FIELDS = ['color', 'size'] as const

export function VariantFormDialog({
  open,
  onClose,
  productId,
  variant,
}: {
  open: boolean
  onClose: () => void
  productId: string
  variant?: AdminVariant
}): ReactElement | null {
  const editing = variant !== undefined
  const [formMessage, setFormMessage] = useState<string | null>(null)
  const create = useCreateVariant(productId)
  const update = useUpdateVariant(productId)
  const showToast = useToast()
  const pending = create.isPending || update.isPending

  const { register, handleSubmit, setError, reset, formState } = useForm<VariantValues>({
    resolver: zodResolver(variantSchema),
    defaultValues: { color: '', size: '', openingStock: '0' },
  })

  useEffect(() => {
    if (!open) return
    reset({ color: variant?.color ?? '', size: variant?.size ?? '', openingStock: '0' })
    setFormMessage(null)
  }, [open, variant, reset])

  const onSubmit = handleSubmit((values) => {
    setFormMessage(null)
    const onError = (error: unknown): void =>
      setFormMessage(applyApiErrorToForm<VariantValues>(error, setError, VARIANT_FIELDS))

    if (variant !== undefined) {
      update.mutate(
        { variantId: variant.id, body: { color: values.color, size: values.size } },
        {
          onSuccess: () => {
            showToast('Variant updated')
            onClose()
          },
          onError,
        },
      )
      return
    }

    create.mutate(
      { color: values.color, size: values.size, stockQuantity: Number(values.openingStock) },
      {
        onSuccess: () => {
          showToast('Variant added')
          onClose()
        },
        onError,
      },
    )
  })

  return (
    <Dialog
      open={open}
      title={editing ? `Edit ${variant.color} / ${variant.size}` : 'New variant'}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button type="submit" form="variant-form" loading={pending}>
            {editing ? 'Save changes' : 'Add variant'}
          </Button>
        </>
      }
    >
      <form id="variant-form" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <Field label="Colour" htmlFor="variant-color" required error={formState.errors.color?.message}>
          <TextInput
            id="variant-color"
            invalid={formState.errors.color !== undefined}
            {...register('color')}
          />
        </Field>

        <Field label="Size" htmlFor="variant-size" required error={formState.errors.size?.message}>
          <TextInput
            id="variant-size"
            invalid={formState.errors.size !== undefined}
            {...register('size')}
          />
        </Field>

        {editing ? (
          <p className="text-sm text-slate-600">
            Stock is changed with Adjust stock, so that every movement carries a reason.
          </p>
        ) : (
          <Field
            label="Opening stock"
            htmlFor="variant-opening-stock"
            required
            error={formState.errors.openingStock?.message}
            hint="The starting count. Every later change goes through Adjust stock."
          >
            <TextInput
              id="variant-opening-stock"
              inputMode="numeric"
              autoComplete="off"
              invalid={formState.errors.openingStock !== undefined}
              {...register('openingStock')}
            />
          </Field>
        )}

        {formMessage !== null ? (
          <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
            {formMessage}
          </p>
        ) : null}
      </form>
    </Dialog>
  )
}
```

- [ ] **Step 8: Write the variants card**

`web/apps/admin/src/features/variants/VariantsCard.tsx`:

```tsx
import type { AdminProduct, AdminVariant } from '@shopflow/api-client'
import { useState, type ReactElement } from 'react'
import { Badge } from '../../components/Badge'
import { Button } from '../../components/Button'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { useToast } from '../../components/Toast'
import { describeError } from '../../lib/errors'
import { StockDialog } from './StockDialog'
import { VariantFormDialog } from './VariantFormDialog'
import { useArchiveVariant, useRestoreVariant } from './queries'

type OpenDialog =
  | { kind: 'none' }
  | { kind: 'add' }
  | { kind: 'edit'; variant: AdminVariant }
  | { kind: 'stock'; variant: AdminVariant }
  | { kind: 'archive'; variant: AdminVariant }
  | { kind: 'restore'; variant: AdminVariant }

const CLOSED: OpenDialog = { kind: 'none' }

function label(variant: AdminVariant): string {
  return `${variant.color} / ${variant.size}`
}

export function VariantsCard({ product }: { product: AdminProduct }): ReactElement {
  const [dialog, setDialog] = useState<OpenDialog>(CLOSED)
  const [failure, setFailure] = useState<string | null>(null)
  const archive = useArchiveVariant(product.id)
  const restore = useRestoreVariant(product.id)
  const showToast = useToast()

  const close = (): void => {
    setDialog(CLOSED)
    setFailure(null)
  }

  const liveCount = product.variants.filter((variant) => variant.archivedAt === undefined).length

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-semibold text-slate-900">Variants</h2>
        <Button variant="secondary" onClick={() => setDialog({ kind: 'add' })}>
          Add variant
        </Button>
      </header>

      {liveCount === 0 ? (
        <p className="mt-3 rounded-md bg-amber-50 p-3 text-sm text-amber-900">
          No variants yet — customers cannot buy this product
        </p>
      ) : null}

      {product.variants.length > 0 ? (
        <ul className="mt-3 flex flex-col gap-3">
          {product.variants.map((variant) => {
            const archived = variant.archivedAt !== undefined

            return (
              <li
                key={variant.id}
                aria-label={label(variant)}
                className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-slate-200 p-3"
              >
                <div className="min-w-0">
                  <p className="font-medium text-slate-900">{label(variant)}</p>
                  <p className="mt-1 flex flex-wrap items-center gap-2 text-sm text-slate-600">
                    <span>{`${variant.stockQuantity} in stock`}</span>
                    {archived ? <Badge tone="neutral">Archived</Badge> : null}
                    {!archived && variant.stockQuantity === 0 ? (
                      <Badge tone="danger">Out of stock</Badge>
                    ) : null}
                  </p>
                </div>

                <div className="flex flex-wrap gap-2">
                  {archived ? (
                    <Button
                      variant="secondary"
                      aria-label={`Restore ${label(variant)}`}
                      onClick={() => setDialog({ kind: 'restore', variant })}
                    >
                      Restore
                    </Button>
                  ) : (
                    <>
                      <Button
                        variant="secondary"
                        aria-label={`Adjust stock for ${label(variant)}`}
                        onClick={() => setDialog({ kind: 'stock', variant })}
                      >
                        Adjust stock
                      </Button>
                      <Button
                        variant="ghost"
                        aria-label={`Edit ${label(variant)}`}
                        onClick={() => setDialog({ kind: 'edit', variant })}
                      >
                        Edit
                      </Button>
                      <Button
                        variant="ghost"
                        className="text-red-700"
                        aria-label={`Archive ${label(variant)}`}
                        onClick={() => setDialog({ kind: 'archive', variant })}
                      >
                        Archive
                      </Button>
                    </>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      ) : null}

      <VariantFormDialog open={dialog.kind === 'add'} productId={product.id} onClose={close} />

      {dialog.kind === 'edit' ? (
        <VariantFormDialog open productId={product.id} variant={dialog.variant} onClose={close} />
      ) : null}

      {dialog.kind === 'stock' ? (
        <StockDialog open productId={product.id} variant={dialog.variant} onClose={close} />
      ) : null}

      {dialog.kind === 'archive' ? (
        <ConfirmDialog
          open
          title={`Archive ${label(dialog.variant)}?`}
          confirmLabel="Archive"
          destructive
          busy={archive.isPending}
          error={failure}
          onCancel={close}
          onConfirm={() => {
            setFailure(null)
            archive.mutate(dialog.variant.id, {
              onSuccess: () => {
                showToast('Variant archived')
                close()
              },
              onError: (error) => setFailure(describeError(error).body),
            })
          }}
        >
          {`Archiving "${label(dialog.variant)}" removes it from sale. Its stock and history are kept.`}
        </ConfirmDialog>
      ) : null}

      {dialog.kind === 'restore' ? (
        <ConfirmDialog
          open
          title={`Restore ${label(dialog.variant)}?`}
          confirmLabel="Restore"
          busy={restore.isPending}
          error={failure}
          onCancel={close}
          onConfirm={() => {
            setFailure(null)
            restore.mutate(dialog.variant.id, {
              onSuccess: () => {
                showToast('Variant restored')
                close()
              },
              onError: (error) => setFailure(describeError(error).body),
            })
          }}
        >
          {`Restoring "${label(dialog.variant)}" puts it back on sale with ${dialog.variant.stockQuantity} in stock.`}
        </ConfirmDialog>
      ) : null}
    </section>
  )
}
```

- [ ] **Step 9: Mount it on the detail page**

In `web/apps/admin/src/features/products/ProductDetailPage.tsx`, add the import and replace the Task 11 comment:

```tsx
import { VariantsCard } from '../variants/VariantsCard'
```

```tsx
      <ProductDetailsCard product={product.data} />
      <VariantsCard product={product.data} />

      {/* Task 13 adds the images section here. */}
```

- [ ] **Step 10: Run the tests to verify they pass**

```bash
cd web/apps/admin
pnpm test
pnpm typecheck
pnpm lint
```

Expected: PASS, 84 tests. The `turns a target quantity into a signed delta` and `refuses a zero delta` tests are acceptance criteria 7 and 8.

- [ ] **Step 11: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add web/apps/admin
git commit -m "feat(admin): variants with delta-only stock adjustments"
```

---

### Task 13: Images by URL, with a preview that admits failure

There is **no upload endpoint** — `POST /api/admin/products/{id}/resources` stores a URL string and nothing else. That makes a live preview essential: it is the only way to find out, before saving, that a URL is a 404 or an HTML page rather than an image. The preview is honest about failing.

`isPrimary` is a `Boolean` on both request records precisely so "not mentioned" and "set to false" stay distinguishable, and setting one primary demotes the others server-side — so the UI offers **Make primary**, never **Unset primary**.

**Files:**
- Create: `web/apps/admin/src/features/resources/api.ts`
- Create: `web/apps/admin/src/features/resources/queries.ts`
- Create: `web/apps/admin/src/features/resources/ImagePreview.tsx`
- Create: `web/apps/admin/src/features/resources/AddImageForm.tsx`
- Create: `web/apps/admin/src/features/resources/ImagesCard.tsx`
- Modify: `web/apps/admin/src/features/products/ProductDetailPage.tsx`
- Test: `web/apps/admin/src/features/resources/ImagesCard.test.tsx`

**Interfaces:**
- Consumes: `AdminProduct`, `ProductResource`, `CreateResourceRequest`, `UpdateResourceRequest`, `request` from `@shopflow/api-client`; `PRODUCTS_QUERY_KEY` from Task 9 and `productQueryKey` from Task 11, both in `features/products/queries.ts`; `Badge`, `Button`, `Field`, `TextInput`, `ConfirmDialog`, `useToast`, `describeError`, `applyApiErrorToForm` from Task 5.
- Produces:
  - `function createResource(productId: string, body: CreateResourceRequest): Promise<ProductResource>`
  - `function updateResource(resourceId: string, body: UpdateResourceRequest): Promise<ProductResource>`
  - `function deleteResource(resourceId: string): Promise<void>`
  - `function useCreateResource(productId: string)`, `useUpdateResource(productId: string)`, `useDeleteResource(productId: string)`
  - `function ImagePreview(props: { url: string; alt: string }): ReactElement`
  - `function AddImageForm(props: { productId: string; hasPrimary: boolean }): ReactElement`
  - `function ImagesCard(props: { product: AdminProduct }): ReactElement`

- [ ] **Step 1: Write the failing test**

`web/apps/admin/src/features/resources/ImagesCard.test.tsx`:

```tsx
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { API, HttpResponse, http, server } from '../../test/msw'
import { renderWithProviders } from '../../test/render'
import { ImagesCard } from './ImagesCard'

const PRODUCT_ID = '77777777-7777-7777-7777-777777777777'
const PRIMARY_ID = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee'
const SECOND_ID = 'ffffffff-ffff-ffff-ffff-ffffffffffff'

const PRODUCT = {
  id: PRODUCT_ID,
  name: 'Classic Black Abaya',
  price: 129.5,
  categoryId: '33333333-3333-3333-3333-333333333333',
  categoryName: 'Abaya',
  categoryTypeId: '44444444-4444-4444-4444-444444444444',
  categoryTypeName: 'Abaya',
  variants: [],
  resources: [
    { id: PRIMARY_ID, name: 'Front', url: 'https://cdn.test/front.jpg', type: 'image', isPrimary: true },
    { id: SECOND_ID, name: 'Back', url: 'https://cdn.test/back.jpg', type: 'image', isPrimary: false },
  ],
}

beforeEach(() => {
  server.use(http.get(`${API}/api/admin/products/${PRODUCT_ID}`, () => HttpResponse.json(PRODUCT)))
})

function renderCard(product = PRODUCT) {
  return renderWithProviders(<ImagesCard product={product} />, {
    route: `/products/${PRODUCT_ID}`,
    path: '/products/:id',
  })
}

describe('ImagesCard', () => {
  it('says what an empty gallery means for the storefront', () => {
    renderCard({ ...PRODUCT, resources: [] })

    expect(
      screen.getByText('No images yet — the product will show a placeholder to customers'),
    ).toBeInTheDocument()
  })

  it('marks the primary image and offers to promote the others', () => {
    renderCard()

    const primary = screen.getByRole('listitem', { name: 'Front' })
    expect(within(primary).getByText('Primary')).toBeInTheDocument()
    expect(within(primary).queryByRole('button', { name: 'Make Front primary' })).not.toBeInTheDocument()

    const second = screen.getByRole('listitem', { name: 'Back' })
    expect(within(second).getByRole('button', { name: 'Make Back primary' })).toBeInTheDocument()
  })

  it('previews the URL as it is typed', async () => {
    renderCard()

    await userEvent.type(screen.getByLabelText('Image URL'), 'https://cdn.test/side.jpg')

    expect(await screen.findByRole('img', { name: 'Preview' })).toHaveAttribute(
      'src',
      'https://cdn.test/side.jpg',
    )
  })

  it('admits it when the URL does not load as an image', async () => {
    renderCard()

    await userEvent.type(screen.getByLabelText('Image URL'), 'https://cdn.test/missing.jpg')
    fireEvent.error(await screen.findByRole('img', { name: 'Preview' }))

    expect(
      await screen.findByText('That URL did not load as an image. Check it before saving.'),
    ).toBeInTheDocument()
  })

  it('rejects a URL that is not http or https before any request', async () => {
    renderCard()

    await userEvent.type(screen.getByLabelText('Image URL'), 'cdn.test/front.jpg')
    await userEvent.click(screen.getByRole('button', { name: 'Add image' }))

    expect(await screen.findByText('Must start with http:// or https://')).toBeInTheDocument()
  })

  it('adds an image, optionally as the primary one', async () => {
    const posted: unknown[] = []
    server.use(
      http.post(`${API}/api/admin/products/${PRODUCT_ID}/resources`, async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json({ id: 'new', url: 'https://cdn.test/side.jpg', isPrimary: true }, { status: 201 })
      }),
    )
    renderCard()

    await userEvent.type(screen.getByLabelText('Image URL'), 'https://cdn.test/side.jpg')
    await userEvent.type(screen.getByLabelText('Label'), 'Side')
    await userEvent.click(screen.getByLabelText('Make this the primary image'))
    await userEvent.click(screen.getByRole('button', { name: 'Add image' }))

    await waitFor(() =>
      expect(posted).toEqual([
        { url: 'https://cdn.test/side.jpg', name: 'Side', type: 'image', isPrimary: true },
      ]),
    )
    expect(await screen.findByText('Image added')).toBeInTheDocument()
  })

  it('promotes an image by patching only isPrimary', async () => {
    const patched: unknown[] = []
    server.use(
      http.patch(`${API}/api/admin/resources/${SECOND_ID}`, async ({ request }) => {
        patched.push(await request.json())
        return HttpResponse.json({ ...PRODUCT.resources[1], isPrimary: true })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Make Back primary' }))

    await waitFor(() => expect(patched).toEqual([{ isPrimary: true }]))
  })

  it('confirms before removing an image', async () => {
    let deleted = false
    server.use(
      http.delete(`${API}/api/admin/resources/${SECOND_ID}`, () => {
        deleted = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderCard()

    await userEvent.click(screen.getByRole('button', { name: 'Remove Back' }))
    expect(screen.getByText(/Remove "Back"\? The image itself is not deleted from wherever it is hosted\./))
      .toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Remove image' }))

    await waitFor(() => expect(deleted).toBe(true))
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

```bash
cd web/apps/admin
pnpm test ImagesCard
```

Expected: FAIL — `Failed to resolve import "./ImagesCard"`.

- [ ] **Step 3: Write the API calls and hooks**

`web/apps/admin/src/features/resources/api.ts`:

```ts
import {
  request,
  type CreateResourceRequest,
  type ProductResource,
  type UpdateResourceRequest,
} from '@shopflow/api-client'

/** There is no upload endpoint: a resource is a URL the backend stores as a string. */
export function createResource(
  productId: string,
  body: CreateResourceRequest,
): Promise<ProductResource> {
  return request<ProductResource>(`/api/admin/products/${productId}/resources`, {
    method: 'POST',
    body,
  })
}

/** Setting `isPrimary: true` demotes the product's other resources server-side. */
export function updateResource(
  resourceId: string,
  body: UpdateResourceRequest,
): Promise<ProductResource> {
  return request<ProductResource>(`/api/admin/resources/${resourceId}`, { method: 'PATCH', body })
}

export function deleteResource(resourceId: string): Promise<void> {
  return request<void>(`/api/admin/resources/${resourceId}`, { method: 'DELETE' })
}
```

`web/apps/admin/src/features/resources/queries.ts`:

```ts
import type {
  CreateResourceRequest,
  ProductResource,
  UpdateResourceRequest,
} from '@shopflow/api-client'
import { useMutation, useQueryClient, type UseMutationResult } from '@tanstack/react-query'
import { PRODUCTS_QUERY_KEY, productQueryKey } from '../products/queries'
import { createResource, deleteResource, updateResource } from './api'

/**
 * Promoting one resource demotes the rest, so the whole product is refetched rather than one row
 * patched — a local guess would leave two images claiming to be primary.
 */
function useResourceInvalidation(productId: string): () => void {
  const queryClient = useQueryClient()

  return () => {
    void queryClient.invalidateQueries({ queryKey: productQueryKey(productId) })
    void queryClient.invalidateQueries({ queryKey: [PRODUCTS_QUERY_KEY] })
  }
}

export function useCreateResource(
  productId: string,
): UseMutationResult<ProductResource, unknown, CreateResourceRequest> {
  const invalidate = useResourceInvalidation(productId)

  return useMutation({
    mutationFn: (body: CreateResourceRequest) => createResource(productId, body),
    onSuccess: invalidate,
  })
}

export function useUpdateResource(
  productId: string,
): UseMutationResult<
  ProductResource,
  unknown,
  { resourceId: string; body: UpdateResourceRequest }
> {
  const invalidate = useResourceInvalidation(productId)

  return useMutation({
    mutationFn: ({ resourceId, body }) => updateResource(resourceId, body),
    onSuccess: invalidate,
  })
}

export function useDeleteResource(productId: string): UseMutationResult<void, unknown, string> {
  const invalidate = useResourceInvalidation(productId)

  return useMutation({ mutationFn: deleteResource, onSuccess: invalidate })
}
```

- [ ] **Step 4: Write the preview**

`web/apps/admin/src/features/resources/ImagePreview.tsx`:

```tsx
import { useEffect, useState, type ReactElement } from 'react'

/**
 * The only way to discover before saving that a URL is a 404 or an HTML page. `key={url}` remounts
 * on every change so a previously failed URL cannot leave the message behind.
 */
export function ImagePreview({ url, alt }: { url: string; alt: string }): ReactElement {
  const [failed, setFailed] = useState(false)

  useEffect(() => setFailed(false), [url])

  return (
    <div className="flex flex-col gap-2">
      <div className="flex h-32 w-32 items-center justify-center overflow-hidden rounded-md border border-slate-200 bg-slate-50">
        {failed ? (
          <span aria-hidden="true" className="text-2xl text-slate-400">
            ⚠
          </span>
        ) : (
          <img
            key={url}
            src={url}
            alt={alt}
            className="h-full w-full object-cover"
            onError={() => setFailed(true)}
          />
        )}
      </div>
      {failed ? (
        <p role="alert" className="text-sm text-red-800">
          That URL did not load as an image. Check it before saving.
        </p>
      ) : null}
    </div>
  )
}
```

- [ ] **Step 5: Write the add form**

`web/apps/admin/src/features/resources/AddImageForm.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '../../components/Button'
import { Field, TextInput } from '../../components/Field'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { ImagePreview } from './ImagePreview'
import { useCreateResource } from './queries'

const imageSchema = z.object({
  url: z
    .string()
    .min(1, 'A URL is required')
    .max(1000, 'Must be 1000 characters or fewer')
    .regex(/^https?:\/\//, 'Must start with http:// or https://'),
  name: z.string().max(255, 'Must be 255 characters or fewer'),
  isPrimary: z.boolean(),
})

type ImageValues = z.infer<typeof imageSchema>

const IMAGE_FIELDS = ['url', 'name'] as const

export function AddImageForm({
  productId,
  hasPrimary,
}: {
  productId: string
  hasPrimary: boolean
}): ReactElement {
  const create = useCreateResource(productId)
  const showToast = useToast()
  const [formMessage, setFormMessage] = useState<string | null>(null)

  const { register, handleSubmit, setError, reset, watch, formState } = useForm<ImageValues>({
    resolver: zodResolver(imageSchema),
    // The first image should be the primary one without anyone having to think about it.
    defaultValues: { url: '', name: '', isPrimary: !hasPrimary },
  })

  const url = watch('url')

  const onSubmit = handleSubmit((values) => {
    setFormMessage(null)
    create.mutate(
      {
        url: values.url,
        name: values.name === '' ? undefined : values.name,
        // A free-form string capped at 30 chars; only images are supported here.
        type: 'image',
        isPrimary: values.isPrimary,
      },
      {
        onSuccess: () => {
          showToast('Image added')
          reset({ url: '', name: '', isPrimary: false })
        },
        onError: (error) =>
          setFormMessage(applyApiErrorToForm<ImageValues>(error, setError, IMAGE_FIELDS)),
      },
    )
  })

  return (
    <form className="mt-4 flex flex-col gap-4 border-t border-slate-200 pt-4" onSubmit={onSubmit} noValidate>
      <div className="grid gap-4 md:grid-cols-2">
        <Field
          label="Image URL"
          htmlFor="image-url"
          required
          error={formState.errors.url?.message}
          hint="There is no upload — paste a URL that is already hosted somewhere."
        >
          <TextInput
            id="image-url"
            inputMode="url"
            autoComplete="off"
            invalid={formState.errors.url !== undefined}
            {...register('url')}
          />
        </Field>

        <Field label="Label" htmlFor="image-name" error={formState.errors.name?.message}>
          <TextInput
            id="image-name"
            invalid={formState.errors.name !== undefined}
            {...register('name')}
          />
        </Field>
      </div>

      {url !== '' ? <ImagePreview url={url} alt="Preview" /> : null}

      <label className="flex min-h-11 items-center gap-2 text-sm text-slate-700">
        <input type="checkbox" className="h-4 w-4" {...register('isPrimary')} />
        Make this the primary image
      </label>

      {formMessage !== null ? (
        <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
          {formMessage}
        </p>
      ) : null}

      <div className="flex justify-end">
        <Button type="submit" loading={create.isPending}>
          Add image
        </Button>
      </div>
    </form>
  )
}
```

- [ ] **Step 6: Write the card**

`web/apps/admin/src/features/resources/ImagesCard.tsx`:

```tsx
import type { AdminProduct, ProductResource } from '@shopflow/api-client'
import { useState, type ReactElement } from 'react'
import { Badge } from '../../components/Badge'
import { Button } from '../../components/Button'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { useToast } from '../../components/Toast'
import { describeError } from '../../lib/errors'
import { AddImageForm } from './AddImageForm'
import { ImagePreview } from './ImagePreview'
import { useDeleteResource, useUpdateResource } from './queries'

function label(resource: ProductResource): string {
  return resource.name ?? resource.url
}

export function ImagesCard({ product }: { product: AdminProduct }): ReactElement {
  const [removing, setRemoving] = useState<ProductResource | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const update = useUpdateResource(product.id)
  const remove = useDeleteResource(product.id)
  const showToast = useToast()

  const hasPrimary = product.resources.some((resource) => resource.isPrimary)

  const close = (): void => {
    setRemoving(null)
    setFailure(null)
  }

  const promote = (resource: ProductResource): void => {
    // Only isPrimary is sent: UpdateResourceRequest is partial, and resending url or name would
    // risk overwriting a value someone else just changed.
    update.mutate(
      { resourceId: resource.id, body: { isPrimary: true } },
      {
        onSuccess: () => showToast(`"${label(resource)}" is now the primary image`),
        onError: (error) => showToast(describeError(error).body),
      },
    )
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-lg font-semibold text-slate-900">Images</h2>

      {product.resources.length === 0 ? (
        <p className="mt-3 rounded-md bg-amber-50 p-3 text-sm text-amber-900">
          No images yet — the product will show a placeholder to customers
        </p>
      ) : (
        <ul className="mt-3 grid gap-4 sm:grid-cols-2 md:grid-cols-3">
          {product.resources.map((resource) => (
            <li
              key={resource.id}
              aria-label={label(resource)}
              className="flex flex-col gap-2 rounded-md border border-slate-200 p-3"
            >
              <ImagePreview url={resource.url} alt={label(resource)} />

              <p className="truncate text-sm font-medium text-slate-900">{label(resource)}</p>
              <p className="truncate text-xs text-slate-500">{resource.url}</p>

              <div className="flex flex-wrap items-center gap-2">
                {resource.isPrimary ? (
                  <Badge tone="success">Primary</Badge>
                ) : (
                  <Button
                    variant="secondary"
                    className="px-3 text-xs"
                    aria-label={`Make ${label(resource)} primary`}
                    loading={update.isPending}
                    onClick={() => promote(resource)}
                  >
                    Make primary
                  </Button>
                )}
                <Button
                  variant="ghost"
                  className="px-3 text-xs text-red-700"
                  aria-label={`Remove ${label(resource)}`}
                  onClick={() => setRemoving(resource)}
                >
                  Remove
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}

      <AddImageForm productId={product.id} hasPrimary={hasPrimary} />

      {removing !== null ? (
        <ConfirmDialog
          open
          title={`Remove ${label(removing)}?`}
          confirmLabel="Remove image"
          destructive
          busy={remove.isPending}
          error={failure}
          onCancel={close}
          onConfirm={() => {
            setFailure(null)
            remove.mutate(removing.id, {
              onSuccess: () => {
                showToast('Image removed')
                close()
              },
              onError: (error) => setFailure(describeError(error).body),
            })
          }}
        >
          {`Remove "${label(removing)}"? The image itself is not deleted from wherever it is hosted.`}
        </ConfirmDialog>
      ) : null}
    </section>
  )
}
```

- [ ] **Step 7: Mount it on the detail page**

In `web/apps/admin/src/features/products/ProductDetailPage.tsx`, add the import and replace the Task 13 comment:

```tsx
import { ImagesCard } from '../resources/ImagesCard'
```

```tsx
      <ProductDetailsCard product={product.data} />
      <VariantsCard product={product.data} />
      <ImagesCard product={product.data} />
```

- [ ] **Step 8: Run the tests to verify they pass**

```bash
cd web/apps/admin
pnpm test
pnpm typecheck
pnpm lint
```

Expected: PASS, 92 tests.

- [ ] **Step 9: See a real image**

With the app running, open a product, paste a real image URL (any public one) and check that the preview renders, that a deliberately broken URL shows the failure message, and that after saving the first image it carries the `Primary` badge.

- [ ] **Step 10: Commit**

```bash
cd "$(git rev-parse --show-toplevel)"
git add web/apps/admin
git commit -m "feat(admin): product images by URL with an honest preview"
```

---

### Task 14: End-to-end proof against the real backend, at 390px and 1440px

Everything so far was tested against MSW. This task is the first time the admin portal talks to Spring Boot, Flyway and Postgres, so a failure here is a real defect rather than a red test waiting to go green. It closes acceptance criteria 12 (the three launch categories exist and can hold products) and 14 (usable at 390px and 1440px).

Two rules shape the spec:

1. **It runs against a database that keeps its contents.** Codes are unique, so blindly creating `abaya` a second time returns 409. The seeding step therefore checks the page for a category before creating it, and every product it makes carries a per-run suffix.
2. **Credentials come from the environment, never from the file.** `playwright.config.ts` reads `ADMIN_EMAIL` and `ADMIN_PASSWORD` and fails with an instruction if either is missing. The password is never printed, never defaulted and never written into the spec.

**Files:**
- Create: `web/apps/admin/playwright.config.ts`
- Create: `web/apps/admin/e2e/helpers.ts`
- Create: `web/apps/admin/e2e/catalogue.spec.ts`
- Modify: `web/apps/admin/package.json` (add the `e2e` script and `@playwright/test`)

**Interfaces:**
- Consumes: the whole app from Tasks 1–13, running under `vite` on port 5173 with its `/api` and `/auth` proxy (Task 3); `ADMIN_EMAIL` / `ADMIN_PASSWORD` from `e-commerce-backend/.env` (Task 1); the root `e2e` script and the `.gitignore` entries for `test-results/` and `playwright-report/`, both already in place from Task 1.
- Produces:
  - `const RUN_ID: string`
  - `async function signIn(page: Page): Promise<void>`
  - `async function ensureCategory(page: Page, category: { name: string; code: string }): Promise<void>`

- [ ] **Step 1: Install Playwright and its browser**

```bash
cd web/apps/admin
pnpm add -D @playwright/test@^1.62.1
pnpm exec playwright install chromium
```

Then add the script to `web/apps/admin/package.json`:

```json
    "test:watch": "vitest",
    "e2e": "playwright test",
    "typecheck": "tsc --noEmit"
```

Only Chromium is installed. One engine is enough to prove the layout switches; the point of the two projects below is the viewport, not the vendor.

- [ ] **Step 2: Write the Playwright configuration**

`web/apps/admin/playwright.config.ts`:

```ts
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
    trace: 'retain-on-failure',
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
  globalSetup: undefined,
  metadata: { adminEmail: required('ADMIN_EMAIL'), passwordPresent: required('ADMIN_PASSWORD') !== '' },
})
```

The `metadata` entry exists to make the config fail at load time when a credential is missing, before a browser starts. It records the email and a boolean — never the password.

- [ ] **Step 3: Write the helpers**

`web/apps/admin/e2e/helpers.ts`:

```ts
import { expect, type Page } from '@playwright/test'

/** Distinguishes this run's products from every previous run's, in a database that keeps them. */
export const RUN_ID = Date.now().toString(36)

export async function signIn(page: Page): Promise<void> {
  const email = process.env.ADMIN_EMAIL
  const password = process.env.ADMIN_PASSWORD
  if (email === undefined || password === undefined) throw new Error('ADMIN_EMAIL / ADMIN_PASSWORD missing')

  await page.goto('/login')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()

  // Landing on /products is the proof that login, /api/me and the ADMIN role check all passed.
  await expect(page.getByRole('heading', { name: 'Products', level: 1 })).toBeVisible()
}

/**
 * Idempotent: a second run finds the card and does nothing. Creating a duplicate code would 409,
 * and the point of the seed is the end state, not the insert.
 */
export async function ensureCategory(
  page: Page,
  category: { name: string; code: string },
): Promise<void> {
  const card = page.getByRole('listitem').filter({ hasText: category.code })
  if ((await card.count()) === 0) {
    const create = page.getByRole('button', { name: 'Create your first category' })
    if ((await create.count()) > 0) await create.click()
    else await page.getByRole('button', { name: 'New category' }).click()

    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Name').fill(category.name)
    await expect(dialog.getByLabel('Code')).toHaveValue(category.code)
    await dialog.getByRole('button', { name: 'Create category' }).click()
    await expect(dialog).toBeHidden()
  }

  const saved = page.getByRole('listitem', { name: category.name })
  await expect(saved).toBeVisible()

  // One type per category, named after it — the flat shape the spec asks for.
  if (await saved.getByText(`${category.name} (${category.code})`).isVisible()) return

  await saved.getByRole('button', { name: `Add type to ${category.name}` }).click()
  const typeDialog = page.getByRole('dialog')
  await typeDialog.getByLabel('Name').fill(category.name)
  await typeDialog.getByRole('button', { name: 'Add type' }).click()
  await expect(typeDialog).toBeHidden()
  await expect(saved.getByText(`${category.name} (${category.code})`)).toBeVisible()
}
```

- [ ] **Step 4: Write the catalogue journey**

`web/apps/admin/e2e/catalogue.spec.ts`:

```ts
import { expect, test } from '@playwright/test'
import { RUN_ID, ensureCategory, signIn } from './helpers'

const LAUNCH_CATEGORIES = [
  { name: 'Abaya', code: 'abaya' },
  { name: 'Hijabs', code: 'hijabs' },
  { name: 'Accessories', code: 'accessories' },
]

const PRODUCT_NAME = `E2E Abaya ${RUN_ID}`

test.describe.configure({ mode: 'serial' })

test('seeds the three launch categories, each with a type of the same name', async ({ page }) => {
  await signIn(page)
  await page.goto('/categories')
  await expect(page.getByRole('heading', { name: 'Categories', level: 1 })).toBeVisible()

  for (const category of LAUNCH_CATEGORIES) await ensureCategory(page, category)

  for (const category of LAUNCH_CATEGORIES) {
    const card = page.getByRole('listitem', { name: category.name })
    await expect(card.getByText('No types — cannot hold products')).toBeHidden()
  }

  await page.reload()
  for (const category of LAUNCH_CATEGORIES) {
    await expect(page.getByRole('listitem', { name: category.name })).toBeVisible()
  }
})

test('takes a product from nothing to sellable', async ({ page }) => {
  await signIn(page)

  await page.getByRole('link', { name: 'New product' }).first().click()
  await expect(page.getByRole('heading', { name: 'New product', level: 1 })).toBeVisible()

  await page.getByLabel('Name').fill(PRODUCT_NAME)
  await page.getByLabel('Description').fill('Created by the end-to-end suite.')
  await page.getByLabel('Price').fill('129.50')
  await page.getByLabel('Category').selectOption({ label: 'Abaya' })
  await page.getByLabel('Type').selectOption({ label: 'Abaya' })
  await page.getByRole('button', { name: 'Create product' }).click()

  await expect(page.getByRole('heading', { name: PRODUCT_NAME, level: 1 })).toBeVisible()
  await expect(page.getByText('No variants yet — customers cannot buy this product')).toBeVisible()

  // A variant with an opening balance.
  await page.getByRole('button', { name: 'Add variant' }).click()
  const variantDialog = page.getByRole('dialog')
  await variantDialog.getByLabel('Colour').fill('Black')
  await variantDialog.getByLabel('Size').fill('M')
  await variantDialog.getByLabel('Opening stock').fill('5')
  await variantDialog.getByRole('button', { name: 'Add variant' }).click()
  await expect(variantDialog).toBeHidden()

  const variant = page.getByRole('listitem', { name: 'Black / M' })
  await expect(variant).toBeVisible()
  await expect(page.getByText('No variants yet — customers cannot buy this product')).toBeHidden()

  // Every later change is a signed delta with a reason.
  await variant.getByRole('button', { name: 'Adjust stock for Black / M' }).click()
  const stockDialog = page.getByRole('dialog')
  await stockDialog.getByLabel('New quantity').fill('8')
  await expect(stockDialog.getByText('Adds 3 (5 → 8)')).toBeVisible()
  await stockDialog.getByLabel('Reason').fill(`E2E restock ${RUN_ID}`)
  await stockDialog.getByRole('button', { name: 'Adjust stock' }).click()
  await expect(stockDialog).toBeHidden()
  await expect(variant.getByText('8')).toBeVisible()

  // An image, which becomes the primary one because it is the first.
  await page.getByLabel('Image URL').fill('https://placehold.co/600x800/png')
  await page.getByLabel('Label').fill('Front')
  await page.getByRole('button', { name: 'Add image' }).click()
  const image = page.getByRole('listitem', { name: 'Front' })
  await expect(image.getByText('Primary')).toBeVisible()

  // The list reflects the computed columns the detail page never sends.
  await page.getByRole('link', { name: 'Products' }).first().click()
  await page.getByLabel('Search products').fill(PRODUCT_NAME)
  const row = page.getByRole('link', { name: PRODUCT_NAME })
  await expect(row).toBeVisible()
})

test('archives a product away from customers and restores it', async ({ page }) => {
  await signIn(page)
  await page.getByLabel('Search products').fill(PRODUCT_NAME)
  await page.getByRole('link', { name: PRODUCT_NAME }).click()
  await expect(page.getByRole('heading', { name: PRODUCT_NAME, level: 1 })).toBeVisible()

  const productId = new URL(page.url()).pathname.split('/').pop() ?? ''
  expect(productId).not.toBe('')

  // A customer can see it right now.
  expect((await page.request.get(`/api/products/${productId}`)).status()).toBe(200)

  await page.getByRole('button', { name: 'Archive' }).first().click()
  const archiveDialog = page.getByRole('dialog')
  await expect(archiveDialog).toContainText('Nothing is deleted and you can restore it later.')
  await archiveDialog.getByRole('button', { name: 'Archive' }).click()
  await expect(page.getByText('Archived').first()).toBeVisible()

  // Criterion 10, the half no mocked test can prove: gone for customers, 404 rather than a 410 or
  // an empty body, because to a customer an archived product never existed.
  expect((await page.request.get(`/api/products/${productId}`)).status()).toBe(404)

  // ...and gone from the admin default list, but findable under the archived filters.
  await page.getByRole('link', { name: 'Products' }).first().click()
  await page.getByLabel('Search products').fill(PRODUCT_NAME)
  await expect(page.getByRole('link', { name: PRODUCT_NAME })).toBeHidden()

  await page.getByLabel('Status').selectOption('only')
  await expect(page.getByRole('link', { name: PRODUCT_NAME })).toBeVisible()
  await page.getByLabel('Status').selectOption('all')
  await expect(page.getByRole('link', { name: PRODUCT_NAME })).toBeVisible()

  await page.getByRole('link', { name: PRODUCT_NAME }).click()
  await page.getByRole('button', { name: 'Restore' }).first().click()
  const restoreDialog = page.getByRole('dialog')
  await expect(restoreDialog).toContainText('Variants archived separately stay archived.')
  await restoreDialog.getByRole('button', { name: 'Restore' }).click()

  await expect(page.getByRole('button', { name: 'Archive' }).first()).toBeVisible()
  expect((await page.request.get(`/api/products/${productId}`)).status()).toBe(200)
})

test('shows the layout that fits the viewport', async ({ page }, testInfo) => {
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Products', level: 1 })).toBeVisible()

  const table = page.getByRole('table')
  const openMenu = page.getByRole('button', { name: 'Open menu' })

  if (testInfo.project.name === 'mobile') {
    // 390px: cards, no table, and navigation behind the drawer.
    await expect(table).toBeHidden()
    await expect(openMenu).toBeVisible()

    await openMenu.click()
    await page.getByRole('link', { name: 'Categories' }).click()
    await expect(page.getByRole('heading', { name: 'Categories', level: 1 })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Close menu' })).toBeHidden()

    // Nothing overflows sideways on a phone.
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth)
    expect(scrollWidth).toBeLessThanOrEqual(390)

    // Every visible control clears 44px. `min-h-11` is meant to guarantee this; measure it rather
    // than trust it, because a stray `h-8` in one component is invisible in review.
    const controls = await page.getByRole('button').all()
    for (const control of controls) {
      const box = await control.boundingBox()
      if (box !== null) expect(box.height).toBeGreaterThanOrEqual(44)
    }
  } else {
    // 1440px: the table, the permanent sidebar, no drawer trigger.
    await expect(table).toBeVisible()
    await expect(openMenu).toBeHidden()
    await expect(page.getByRole('link', { name: 'Categories' })).toBeVisible()
  }
})
```

- [ ] **Step 5: Start the backend and run the suite**

The backend must already be running on port 8080 with the administrator from Task 1. Then, from `web/apps/admin`:

```bash
cd web/apps/admin
(set -a; . ../../../e-commerce-backend/.env; set +a; pnpm e2e)
```

The subshell and `set -a` keep the password out of the terminal and out of the parent shell. Expected: 8 passing tests — four in `laptop`, four in `mobile`.

Failures here are real. Read them rather than adjusting the test:
- Nothing at `http://localhost:8080` → the backend is down; the sign-in step will time out.
- Sign-in fails with "Invalid email or password" → the `.env` credentials do not match the bootstrapped administrator; redo Task 1 Step 4.
- "Administrator access required" → the account exists but lacks the `ADMIN` role.
- A 409 while seeding → a category with that code exists under a different name; rename it in the UI or pick a different launch code.

- [ ] **Step 6: Read the report**

```bash
cd web/apps/admin
pnpm exec playwright show-report
```

Look at the `mobile` project's screenshots for the products list and the product detail page. Anything cramped, clipped or below a 44px touch target is a bug to fix now, while the layout is fresh.

- [ ] **Step 7: Confirm no artefacts and no secrets are staged**

```bash
cd "$(git rev-parse --show-toplevel)"
git status --short
git check-ignore -v web/apps/admin/test-results web/apps/admin/playwright-report
```

Expected: `git status` shows the three new e2e files and the two modified manifests, and nothing from `test-results/`, `playwright-report/` or `e-commerce-backend/.env`. `git check-ignore` must print a matching rule for both artefact directories.

- [ ] **Step 8: Commit**

```bash
git add web/apps/admin/playwright.config.ts web/apps/admin/e2e web/apps/admin/package.json web/pnpm-lock.yaml
git commit -m "test(admin): end-to-end catalogue journey at phone and laptop widths"
```

---

## Acceptance criteria coverage

Every criterion in §16 of the spec, and the test that proves it. If a task's tests pass but the criterion in this table still fails by hand, the task is not done.

| # | Criterion | Proved by |
|---|---|---|
| 1 | Non-admin sees an explanatory no-access message and holds no tokens | Task 6, `RequireAdmin.test.tsx` — the "Administrator access required" screen plus the assertion that the store is empty afterwards |
| 2 | Reload keeps an admin signed in; closing the browser signs them out | Task 3 `tokenStore` on `sessionStorage`; Task 6 Step 10 by hand |
| 3 | N concurrent 401s trigger exactly one `POST /auth/refresh` | Task 3, `refresh.test.ts` — the two counted-call assertions |
| 4 | "Abaya" pre-fills `abaya`; a duplicate code errors on that field | Task 7, `CategoriesPage.test.tsx` — the auto-slug test and the 409-on-`code` test |
| 5 | A type-less category shows the badge; the product form links to the fix | Task 8 badge test; Task 10 `“Abaya” has no types yet.` + `Add a type` link test |
| 6 | The product form cannot submit a type from another category | Task 10 — the type resets on category change, so the pair is unrepresentable |
| 7 | 10 → 25 sends `delta: 15` with a reason and reports `previous → new` | Task 12, `StockDialog.test.tsx` |
| 8 | A zero-delta submission is rejected before any network call | Task 12 — refused in the handler, with MSW set to `onUnhandledRequest: 'error'` |
| 9 | Backend field validation lands on the input, not only in a banner | Task 5 `applyApiErrorToForm`; exercised in Tasks 7, 8, 10, 11, 12, 13 |
| 10 | Archive hides from public `GET /api/products` and the default admin list, shows under `only`/`all`, restore reverses it, and no false claim about variants | Task 11 component tests; Task 14 `archives a product away from customers and restores it` — the real 200 → 404 → 200 |
| 11 | Deleting a category confirms with the type count and the irreversibility | Task 8, `CategoryCard.test.tsx` cascade test |
| 12 | Usable at 390px and 1440px, no horizontal scroll, 44px targets | Task 5 `min-h-11`; Task 9 dual table/card render; Task 14 `shows the layout that fits the viewport` — measured `scrollWidth` and button heights |
| 13 | Every query has a distinct loading, error-with-retry and empty state | Task 5 `QueryStates.test.tsx`; asserted per screen in Tasks 7, 9, 11 |
| 14 | The three categories exist, each with one type, created through the UI | Task 14 `seeds the three launch categories, each with a type of the same name` |

## What this plan does not build

Copied from §17 of the spec so nobody adds it in passing: order fulfilment, the audit viewer, the storefront, image file upload, admin user management, production CORS, `packages/ui`, bulk actions, CSV import, dark mode, i18n, and optimistic updates. Each has a reason recorded in the spec. If one of them looks necessary while executing a task, that is a signal to stop and ask, not to widen the task.
