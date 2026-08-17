# ShopFlow Web — Sub-project 1: Admin Portal v1 (Catalogue)

**Date:** 2026-08-17
**Status:** Approved
**Consumes:** ShopFlow API v1 at `http://localhost:8080` (verified live; spec read from `/v3/api-docs`)
**Related:** `docs/superpowers/specs/2026-08-11-foundation-auth-design.md` (backend slice 1)

## 1. Context

The backend is built and running. Its OpenAPI document exposes 51 operations, 26 of
them under `/api/admin/**`. Nothing consumes it yet except Swagger UI. This document
designs the first of three frontend sub-projects.

| Sub-project | Contents |
|---|---|
| **1. Admin portal v1** (this document) | Admin login, categories and types, products, variants, stock, images |
| 2. Storefront | Public browse and search, cart, checkout, order history, profile, addresses |
| 3. Admin portal v2 | Order fulfilment (ship/deliver/cancel), audit trail viewer |

### Starting point

Verified against the running backend on 2026-08-17:

- `GET /api/products` returns `totalElements: 0`. `GET /api/categories` returns `[]`.
  **The catalogue is empty.** Nothing can be browsed until it is populated.
- `V2__seed_roles.sql` seeds the `CUSTOMER` and `ADMIN` roles but no admin *user*. Its
  comment defers that to `AdminBootstrap`, which reads `ADMIN_EMAIL` / `ADMIN_PASSWORD`.
  Both are **blank** in `e-commerce-backend/.env`, so **no admin account exists**.
- `SecurityConfig.java` configures request matchers (lines 54-67) but never calls
  `.cors(...)`. **There is no CORS configuration**, so a browser on another origin is blocked.
- `/auth/register` creates customer accounts only. There is no API to grant `ADMIN`.

### Decisions taken

| Decision | Choice | Reason |
|---|---|---|
| Build order | Admin before storefront | Only the admin API can populate the empty catalogue. A storefront with no products cannot be built or judged. |
| Repository layout | One pnpm workspace, two apps, shared client | Token refresh and generated types are written once instead of diverging across two copies. |
| Workspace location | `web/` in the existing repo | `docs/superpowers/specs/` already lives at this root and the backend is itself a subfolder. Separate build outputs, so deploys stay independent. |
| Framework | Vite + React + TypeScript SPA | Fastest dev loop, static-file deploy, and the dev proxy closes the CORS gap without a backend change. An admin portal needs no SEO. |
| `packages/ui` | **Not created in v1** | It would have one consumer. Components live in `apps/admin` and get extracted when the storefront needs them. |
| Admin v1 scope | Catalogue only | Unblocks the storefront sooner. Orders and audit are v2. |
| Category types | Flat, one type per category, field visible | The API requires `categoryTypeId` on every product. One same-named type per category satisfies it with no hidden magic, and adding real subcategories later needs no migration. |
| Token storage | Access in memory, refresh in `sessionStorage` | The API returns tokens in the response body, so httpOnly cookies are unavailable. Closing the browser should end a session that can archive the catalogue. |

## 2. Scope of this sub-project

In scope:

- Admin login with an `ADMIN`-role gate, token refresh, logout
- Categories: list, create, update, delete (cascades to types); types: create, update, delete
- Products: paginated list with search, category filter, archived filter, sort; create; update;
  archive; restore
- Variants: add, edit colour/size, archive, restore
- Stock: adjust by signed delta with a mandatory reason
- Product images: attach by URL, edit, delete, set primary
- Responsive layouts for mobile (<768px) and desktop (>=768px)
- Seeding the three initial categories through the finished UI

Out of scope, with reasons in §17: orders, audit trail, storefront, file upload,
admin user management, internationalisation, production CORS.

## 3. Technology

| Concern | Choice |
|---|---|
| Build | Vite 7, TypeScript strict |
| UI | React 19 |
| Routing | React Router 7 |
| Server state | TanStack Query 5 |
| Styling | Tailwind CSS 4 |
| Forms | react-hook-form + zod resolver |
| Types | `openapi-typescript`, generated from `/v3/api-docs` |
| Unit/component tests | Vitest + React Testing Library + MSW |
| End-to-end | Playwright |
| Package manager | pnpm (via corepack) |

No component library. The screen count is small, the widget needs are ordinary, and a
library's opinions about tables and dialogs would fight the mobile/desktop split in §9.

## 4. Workspace structure

```
web/
├─ .nvmrc                        22
├─ pnpm-workspace.yaml
├─ package.json                  scripts delegating to apps
├─ packages/
│  └─ api-client/
│     ├─ src/
│     │  ├─ generated.ts         openapi-typescript output, never hand-edited
│     │  ├─ http.ts              fetch wrapper, ProblemDetail parsing
│     │  ├─ problem.ts           ApiError, FieldError types
│     │  ├─ tokens.ts            token store (memory + sessionStorage)
│     │  └─ refresh.ts           single-flight refresh
│     └─ package.json
└─ apps/
   └─ admin/
      ├─ src/
      │  ├─ main.tsx
      │  ├─ routes/              one folder per route
      │  ├─ features/
      │  │  ├─ auth/
      │  │  ├─ categories/
      │  │  └─ products/
      │  ├─ components/          shared primitives, extraction candidates
      │  ├─ hooks/               query and mutation hooks
      │  └─ lib/
      ├─ vite.config.ts          dev proxy
      └─ package.json
```

Each feature folder owns its queries, mutations, forms, and screens. A feature may
import from `components/` and `lib/`, never from another feature. This keeps each
feature independently readable and testable.

## 5. API surface consumed

All paths verified present in `/v3/api-docs`. Everything under `/api/admin/**` requires
`hasRole("ADMIN")` per `SecurityConfig.java:66`.

**Auth** (`sec=None` except logout)

| Method | Path | Use |
|---|---|---|
| POST | `/auth/login` | `LoginRequest` → `TokenPairResponse` |
| POST | `/auth/refresh` | `RefreshRequest` → `TokenPairResponse`, rotates |
| POST | `/auth/logout` | `LogoutRequest`, revokes the refresh token |

**Profile**

| Method | Path | Use |
|---|---|---|
| GET | `/api/me` | `UserProfileResponse`; `roles` drives the admin gate |

**Categories**

| Method | Path | Use |
|---|---|---|
| GET | `/api/categories` | Public. `CategoryResponse[]` with nested `types` |
| POST | `/api/admin/categories` | `CreateCategoryRequest` |
| PATCH | `/api/admin/categories/{id}` | `UpdateCategoryRequest` |
| DELETE | `/api/admin/categories/{id}` | Hard delete, cascades to types |
| POST | `/api/admin/categories/{id}/types` | `CreateCategoryTypeRequest` |
| PATCH | `/api/admin/category-types/{id}` | `UpdateCategoryTypeRequest` |
| DELETE | `/api/admin/category-types/{id}` | Hard delete |

**Products, variants, stock, resources**

| Method | Path | Use |
|---|---|---|
| GET | `/api/admin/products` | Params below |
| GET | `/api/admin/products/{id}` | `AdminProductResponse`, includes archived rows |
| POST | `/api/admin/products` | `CreateProductRequest` |
| PATCH | `/api/admin/products/{id}` | `UpdateProductRequest` |
| DELETE | `/api/admin/products/{id}` | Archive (soft) |
| POST | `/api/admin/products/{id}/restore` | Unarchive |
| POST | `/api/admin/products/{id}/variants` | `CreateVariantRequest` |
| PATCH | `/api/admin/variants/{id}` | `UpdateVariantRequest`, colour and size only |
| DELETE | `/api/admin/variants/{id}` | Archive |
| POST | `/api/admin/variants/{id}/restore` | Unarchive |
| POST | `/api/admin/variants/{id}/stock` | `AdjustStockRequest` → `StockAdjustmentResponse` |
| POST | `/api/admin/products/{id}/resources` | `CreateResourceRequest` |
| PATCH | `/api/admin/resources/{id}` | `UpdateResourceRequest` |
| DELETE | `/api/admin/resources/{id}` | Hard delete |

### `GET /api/admin/products` parameters

Read from `AdminProductController.java:47-69`. These are validated with `@Pattern`, so a
wrong value is a 400, not a silent fallback. The UI must send only these:

| Param | Allowed values | Default |
|---|---|---|
| `archived` | `exclude`, `only`, `all` — **not a boolean** | `exclude` |
| `sort` | `name`, `price`, `createdAt` — whitelisted, no arbitrary entity paths | `createdAt` |
| `direction` | `asc`, `desc` (case-insensitive) | `desc` |
| `categoryId` | uuid | none |
| `q` | free text | none |
| `page`, `size` | integers | 0, 20 |

The public `GET /api/products` uses the same `sort` and `direction` whitelist
(`ProductController.java:51-54`), so sub-project 2 inherits it.

### Constraints the UI must respect

Read from the schema and the backend source, not assumed:

1. **`categoryTypeId` is required on every product** (`CreateProductRequest`). A category
   with zero types can hold zero products.
2. **A type must belong to the chosen category or the response is 404, not 400**
   (`AdminProductService.java:186-196`). The UI must make the mismatch unreachable.
3. **`categories.code` is globally unique** (`V1__init.sql:107`, `uq_categories_code`).
4. **`category_types.code` has no unique constraint** (`V1__init.sql:110-118`). The UI must
   not assume type codes are unique, even within one category.
5. **Stock changes only by signed delta with a mandatory reason** (`AdjustStockRequest`:
   `delta` required, `reason` required, delta must be non-zero). There is no set-absolute
   endpoint.
6. **`UpdateVariantRequest` carries only colour and size.** Stock is a separate call.
7. **Product delete is soft.** `DELETE` sets `archivedAt`; `restore` clears it.
8. **Category delete is hard and cascades to its types.**
9. **There is no upload endpoint.** `CreateResourceRequest.url` is a string, max 1000 chars.
10. **`PageResponse*` is a custom envelope**, not Spring's `Page`: `content`, `page`, `size`,
    `totalElements`, `totalPages`, `first`, `last`.
11. **Restoring a product does not resurrect variants archived separately, and is
    idempotent** (`AdminProductController.java:110-111`). A restored product can therefore
    still have no live variants, and the UI must not imply otherwise.
12. **`variantCount` and `totalStock` count unarchived variants only**
    (`AdminProductController.java:52-53`). They are not totals over all rows.
13. **Catalogue prices carry no currency.** Currency is a hardcoded `USD` constant on the
    order side (`OrderService.java:54-57`), whose comment states that changing it alone
    would silently reinterpret catalogue prices. The admin UI therefore formats prices as
    USD and never offers a currency selector.
14. **An archived product answers 200, not 404, on the admin detail endpoint**
    (`AdminProductController.java:74-76`), and includes archived variants.

### Field limits, for the zod schemas

| Request | Field | Limit |
|---|---|---|
| `CreateProductRequest` | `name` | required, <=255 |
| | `description` | optional, <=2000 |
| | `price` | required, >=0 |
| | `categoryId`, `categoryTypeId` | required, uuid |
| `CreateVariantRequest` | `color` | required, <=60 |
| | `size` | required, <=30 |
| | `stockQuantity` | optional, >=0 |
| `AdjustStockRequest` | `delta` | required, non-zero |
| | `reason` | required, <=500 |
| `CreateResourceRequest` | `url` | required, <=1000 |
| | `name`, `type` | optional, <=255 / <=30 |
| `CreateCategoryRequest` | `name` | required, <=255 |
| | `code` | required, <=100 |
| | `description` | optional, <=2000 |

## 6. The `api-client` package

### Type generation

`pnpm gen:api` runs `openapi-typescript http://localhost:8080/v3/api-docs -o src/generated.ts`.
The output is committed so builds do not require a running backend, and regenerating it
turns backend drift into a TypeScript error instead of a runtime surprise.

### Error model

Every non-2xx response is parsed into one `ApiError`:

```ts
type FieldError = { field: string; message: string }

class ApiError extends Error {
  status: number
  title?: string
  detail?: string
  fieldErrors: FieldError[]   // from ProblemDetail.errors[], [] when absent
}
```

Confirmed against the live backend — `POST /auth/register` with invalid input returns
`{status, title, detail, instance, errors:[{field,message}]}`. `fieldErrors` is what lets
§10 place server messages on the offending input.

### Token store and refresh

- Access token: module-level variable. Never persisted.
- Refresh token: `sessionStorage`.
- On startup, a refresh token without an access token triggers one refresh to restore the session.

Single-flight refresh, because the backend rotates refresh tokens and slice 1 implements
reuse detection — concurrent refreshes would present the same token twice and could
invalidate the session:

1. A 401 on any request calls `ensureFresh()`.
2. The first caller starts `POST /auth/refresh` and stores the promise.
3. Later callers await the same promise. No second network call is made.
4. On success, tokens are replaced and each original request retries **once**.
5. On failure, tokens are purged and an `auth:expired` event redirects to `/login`.

A retried request that 401s again is not retried a second time.

## 7. Auth and the admin gate

Login is two steps, deliberately:

1. `POST /auth/login` → store the token pair.
2. `GET /api/me` → require `roles` to include `ADMIN`.

A valid non-admin login is rejected with "This account does not have administrator
access", and its tokens are cleared. Without this check the user would reach a shell
where every panel independently fails with 403 — technically safe, but unexplainable.

`<RequireAdmin>` wraps all routes but `/login`: it renders a spinner while `/api/me` is
in flight, redirects to `/login` when unauthenticated, and shows the no-access message
for an authenticated non-admin. The gate is a UX affordance; `SecurityConfig.java:66` is
the actual enforcement.

Logout calls `POST /auth/logout` with the refresh token, then clears local state. A
failed logout call still clears local state.

## 8. Routes and screens

```
/login                     email + password
/                          redirect -> /products
/products                  paginated list, filters, sort
/products/new              create form
/products/:id              edit + variants + images
/categories                categories with nested types
*                          not found
```

### `/products`

Filters map one-to-one onto the query parameters in §5 and live in the URL, so a filtered
list is shareable and survives reload: `q`, `categoryId`, `archived`, `sort`, `direction`,
`page`, `size`. Search input is debounced 300ms.

The archived filter is a three-way control — **Live / Archived / All** — mapping to
`exclude`, `only`, `all`. It is not a checkbox, because the parameter is not a boolean.
Sort offers exactly the three whitelisted fields; the UI never exposes a column header
that cannot be sorted, since an off-whitelist value is a 400.

Columns use `AdminProductSummaryResponse`: thumbnail, name, category and type,
price (USD, per constraint 13), `variantCount`, `totalStock`, and an archived badge when
`archivedAt` is set. Both counts cover unarchived variants only (constraint 12), so the
column headers read "Live variants" and "Live stock" rather than "Variants" and "Stock" —
the shorter labels would misreport a product with archived variants.

`totalStock` of 0 is shown as an "out of stock" marker, since a product with variants but
no stock is invisible to customers and that should be obvious here.

### `/products/new` and `/products/:id`

Creation is two-step by necessity — the API has no nested create. `/products/new` collects
only what `CreateProductRequest` accepts, then redirects to `/products/:id` for variants
and images. The form states this before submission rather than implying one atomic save.

`/products/:id` has three sections: **Details** (`PATCH`), **Variants** (table with
stock-adjust and archive per row), **Images** (URL list with a primary marker).

**The category/type pair.** Constraint 2 in §5 makes a mismatched pair return 404. So:
the type `<select>` is disabled until a category is chosen; its options are the chosen
category's `types` from `GET /api/categories`; and changing category resets type to
empty. If the chosen category has no types, the field is replaced by a link to
`/categories` explaining that a type is required first. The invalid combination is never
submittable.

### `/categories`

Categories with their types nested one level. A category with an empty `types` array gets
a **"No types — cannot hold products"** badge, so constraint 1 is visible before it is hit
from the product form.

`code` auto-derives from `name` (uppercase, non-alphanumerics to underscore: "Abaya" →
`ABAYA`) and stays editable. A duplicate code is a conflict from the unique constraint in
§5, shown inline on the `code` field.

Deleting a category is hard and cascades. Its confirmation names the category, states how
many types will be deleted with it, and says the action cannot be undone. It is the only
irreversible action in v1 and is styled as such.

## 9. Responsive design

One breakpoint, Tailwind `md` (768px). Two layouts maintained well beat four maintained badly.

```
MOBILE (<768px)                DESKTOP (>=768px)
┌──────────────────┐           ┌───────┬─────────────────────────┐
│ ☰  Products    +│           │       │ Products        [+ New] │
├──────────────────┤           │ Cata- ├─────────────────────────┤
│ [Live ▾] [Sort ▾]│           │ logue │ [Live ▾] [Cat ▾] [q...] │
├──────────────────┤           │       ├─────────────────────────┤
│ ┌──────────────┐ │           │ Prod- │ Name   Cat     Stk   ⋯ │
│ │ [img] Abaya  │ │           │ ucts  │ Abaya  Abaya    42   ⋯ │
│ │ $49 · 42 live│ │           │       │ Hijab  Hijabs    8   ⋯ │
│ └──────────────┘ │           │ Cate- │ Pin    Access    0   ⋯ │
│ ┌──────────────┐ │           │ gories│                         │
│ │ [img] Hijab  │ │           │       │ < 1 2 3 >               │
│ │ $29 · 8 live │ │           │       │                         │
│ └──────────────┘ │           │       │                         │
├──────────────────┤           │       │                         │
│    [ + New ]     │           │       │                         │
└──────────────────┘           └───────┴─────────────────────────┘
 cards, drawer nav,             table, persistent sidebar,
 sticky action bar              inline filters
```

| Concern | Mobile | Desktop |
|---|---|---|
| Navigation | Top bar + slide-over drawer | Persistent left sidebar |
| Lists | Card per row | Table with columns |
| Dialogs | Full-screen sheet | Centred modal |
| Forms | Single column, sticky save bar | Two columns where fields are short |
| Filters | Collapsible panel | Inline row |

Rules: one query feeds both presentations — `hidden md:table` beside `md:hidden` cards, so
the two views can never disagree. Minimum 44px touch targets. On mobile the primary save
action sits in a sticky footer so it is never scrolled out of reach. No horizontally
scrolling tables; the card layout replaces them.

## 10. Forms and validation

`react-hook-form` with a zod resolver per request type, mirroring §5's limits so obvious
mistakes never reach the network.

Server errors are merged, not replaced. On `ApiError` with `fieldErrors`, each entry maps
to `setError(field, { message })`; anything unmatched, plus `detail`, becomes a form-level
message. Backend validation therefore lands on the offending input, and the two error
sources share one presentation.

### The stock dialog

`AdjustStockRequest` takes a delta, but an operator counting inventory knows the *total*.
So the dialog shows the current quantity, accepts a **new quantity**, and computes
`delta = target - current`. It displays the computed delta before submit, requires a
reason, and rejects a zero delta client-side with "That is the current quantity".

The confirmation reports `previousQuantity → newQuantity` from `StockAdjustmentResponse`
rather than echoing the input, so a concurrent change by someone else is visible instead
of hidden. This matters: the delta is applied to whatever the server holds, which may no
longer be the number the form was based on.

### Images

A URL field with a live `<img>` preview and a load-failure message, a name, a type
defaulting to `IMAGE`, and a primary marker. Setting a new primary clears the previous
one. No dropzone or file picker, because there is no upload endpoint and offering one
would promise a capability the API lacks.

## 11. Error handling

| Condition | Behaviour |
|---|---|
| 400 with `errors[]` | Mapped onto form fields (§10) |
| 401 | Single-flight refresh, retry once; on failure purge and redirect to `/login` |
| 403 | "You do not have permission for this action". No retry |
| 404 on a detail route | Not-found panel with a link back to the list |
| 404 on product create | Treated as a category/type mismatch and shown on the type field, since §5 constraint 2 makes that the only realistic cause |
| 409 | The server's `detail` shown verbatim; conflicts here are meaningful (duplicate code, stock would go negative) |
| 5xx | Error panel with a Retry button. Never silently swallowed |
| Network failure | Same panel, distinct wording |

Queries render three states explicitly: skeleton while loading, error panel with Retry,
and an empty state with a primary call to action. An empty catalogue is the *first* thing
this app will ever show, so its empty state is a real screen with a "Create your first
category" action, not an afterthought.

Mutation failures keep the dialog open with the data intact. Successes close it and show
a brief confirmation.

## 12. Server state

TanStack Query owns all server state. No Redux, no context mirror of fetched data. The
only global client state is the auth session.

Query keys: `['products', filters]`, `['product', id]`, `['categories']`.

Mutations invalidate rather than optimistically patch. For a catalogue tool, showing the
server's truth beats showing a guess — decisively so for stock, where an optimistic number
could be wrong the moment it renders. The screens are small and refetches are fast, so the
perceived cost is negligible.

`GET /api/categories` is cached with a long `staleTime` — it changes rarely and feeds
every product form's dropdowns.

## 13. Initial catalogue data

Created through the finished UI, which doubles as the first end-to-end exercise of it:

| Category | Code | Initial type |
|---|---|---|
| Abaya | `ABAYA` | Abaya |
| Hijabs | `HIJABS` | Hijabs |
| Accessories | `ACCESSORIES` | Accessories |

One same-named type per category satisfies constraint 1 in §5. Real subcategories
(Abaya → Open, Closed, Kimono) are added later as rows, with no migration of existing
products, because the type field is present from the start.

## 14. Prerequisites

Verified on this machine 2026-08-17. All four are setup steps, not code.

1. **No admin user exists.** Set `ADMIN_EMAIL` and `ADMIN_PASSWORD` in
   `e-commerce-backend/.env` (both currently blank) and restart the backend so
   `AdminBootstrap` creates the account. Nothing in this app can be tested until then.
2. **Node is 18.20.7**, which is past end-of-life; Vite 7 and React Router 7 require Node
   20+. `nvm` is installed and Homebrew already has Node 24.8.0. Use `nvm install 22` and
   pin it with `web/.nvmrc`.
3. **pnpm is not installed.** `corepack enable pnpm` (corepack ships with the current Node).
4. **No backend CORS.** The Vite dev server proxies `/api` and `/auth` to
   `http://localhost:8080`, making requests same-origin in development. No backend change.

## 15. Testing

**Unit and component** — Vitest + RTL + MSW, handlers typed from `generated.ts`. Covering:
the category/type dropdown dependency (options filter, reset on category change, disabled
when empty); the stock dialog's delta arithmetic including the zero-delta rejection; the
`fieldErrors` → `setError` mapping; and single-flight refresh, asserting that N concurrent
401s produce exactly one refresh call.

**End-to-end** — Playwright against the real backend, at a 390px and a 1440px viewport:
log in → create a category and a type → create a product → add a variant → adjust stock →
archive → restore → verify it is hidden from public `GET /api/products` and present under
`archived=only`.

MSW handlers can drift from the real API, so the Playwright run against the live backend
is what actually protects the contract; the mocked tests protect the logic. Both are
needed and neither substitutes for the other.

## 16. Acceptance criteria

1. A non-admin who logs in with valid credentials sees an explanatory no-access message,
   not a broken shell, and holds no tokens afterwards.
2. Refreshing the page keeps an admin signed in; closing the browser signs them out.
3. N concurrent requests meeting a 401 trigger exactly one `POST /auth/refresh`.
4. Creating a category named "Abaya" pre-fills the code `ABAYA`; a duplicate code shows an
   error on that field.
5. A category with no types shows the "cannot hold products" badge, and the product form
   offers a link to fix it instead of an empty dropdown.
6. The product form cannot submit a type belonging to another category.
7. Adjusting stock from 10 to 25 sends `delta: 25 - 10` with a reason and reports the
   server's `previousQuantity → newQuantity`.
8. A zero-delta stock submission is rejected before any network call.
9. Backend field validation appears on the corresponding input, not only as a banner.
10. Archiving a product removes it from public `GET /api/products` and from the default
    admin list, and shows it under `archived=only` and `archived=all`; restore reverses this.
    Restoring does not resurrect separately archived variants, and the UI does not claim it does.
11. Deleting a category requires a confirmation naming the type count and the irreversibility.
12. Every list and detail screen renders usable layouts at 390px and 1440px, with no
    horizontal scrolling and touch targets of at least 44px.
13. Every query renders a distinct loading, error-with-retry, and empty state.
14. The three categories of §13 exist, each with one type, created through the UI.

## 17. Deferred, with reasons

| Deferred | Reason |
|---|---|
| Order fulfilment (ship/deliver/cancel) | Admin v2. The catalogue must exist before orders can, and this unblocks the storefront sooner. |
| Audit trail viewer | Admin v2. Read-only diagnostics, not an operational need for launch. |
| Storefront | Sub-project 2. Needs a populated catalogue, which this provides. |
| Image file upload | No API for it. `CreateResourceRequest` takes a URL string; a dropzone would promise a capability the backend lacks. |
| Admin user management | No API grants roles. `AdminBootstrap` is the only path to an admin. |
| Production CORS or reverse proxy | A backend change, out of a frontend spec's scope. The dev proxy covers development; deploying to a real origin needs one of the two and should be its own decision. |
| `packages/ui` | One consumer until the storefront exists. Extract when there are two. |
| Bulk actions, CSV import | No batch endpoints. Would be N sequential calls with partial-failure semantics to invent. |
| Dark mode, i18n | No requirement stated. |
| Optimistic updates | §12. Correctness over perceived speed for a catalogue tool. |
