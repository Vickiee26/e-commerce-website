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

## Why the generator pins its own TypeScript version

The `gen:api` script uses `pnpm dlx` to pin both `typescript@5.9.3` and `openapi-typescript@7.13.0` in a temporary environment. openapi-typescript needs the TypeScript 5 compiler API, which the TypeScript 7 native port does not expose. The workspace's own TypeScript 7.0.2 is correct for typechecking and for Vite/Vitest, so the generator invokes its own compatible version through `dlx` rather than downgrading the project.
