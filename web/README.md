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
