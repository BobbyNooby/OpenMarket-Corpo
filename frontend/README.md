# frontend — OpenMarket v2 (Next.js)

The v2 frontend: a **Next.js (App Router)** app. It talks **only** to the Go
gateway (REST + WS) — the gateway is the single public entry point and shapes
all service data into UI-ready DTOs (BFF pattern).

> Tests are documented in [`docs/testing.md`](docs/testing.md) — what each
> layer pins, and what is deliberately not tested yet.

- **Stack:** Next.js 16, App Router, React 19, Tailwind CSS 4, shadcn/ui
- **Port:** 5173 dev (v1 parity); the gateway owns :3000
- **State:** no client-side session store — sessions are httpOnly cookies

## Stack

| Concern    | Choice                                    | Carried from v1 (SvelteKit) as          |
|------------|-------------------------------------------|-----------------------------------------|
| Framework  | Next.js 16, App Router, React 19          | SvelteKit 2 / Svelte 5                  |
| Styling    | Tailwind CSS 4 + shadcn/ui (Radix, Nova)  | Tailwind 4 + bits-ui                    |
| Icons      | lucide-react                              | @lucide/svelte                          |
| Theming    | next-themes (class, system default)       | mode-watcher                            |
| Toasts     | sonner                                    | svelte-sonner                           |
| Variants   | class-variance-authority                  | tailwind-variants                       |
| Utils      | clsx + tailwind-merge (`cn()`)            | same                                    |

Dropped from v1 on purpose: `better-auth` (v2 auth is the Spring Boot auth
service), `elysia`/`eden` (no TS server; types come from the gateway's
OpenAPI), Drizzle (the frontend never touches a DB), paraglide i18n and
charts (recharts later, when needed).

## Same-origin by construction

`next.config.ts` rewrites `/api/*` to the gateway server-side. Session
cookies (`om_access` / `om_refresh`, httpOnly, `SameSite=Lax`, set by the
auth service through the gateway) therefore stay first-party: the browser
only ever talks to the frontend's own origin, no CORS config exists
anywhere, and no JavaScript ever sees a raw token. Default `fetch`
credentials (`same-origin`) is the correct mode here — there is nothing to
opt into.

## The `/dev` harness (dev-only)

`src/app/dev` is a raw API exerciser: it drives the real chain — Next.js
rewrite → gateway edge check (gRPC introspection + ban blocklist) →
services → Postgres — and prints raw statuses and JSON bodies. "Unpolished"
is the feature; it exists to make the request contract visible without a
browser tab full of devtools.

| Group | Buttons | Endpoint |
|---|---|---|
| auth | register, login, me, refresh, logout | `/api/v1/auth/*`, `/api/v1/users/me` |
| messaging | conversations, unread | `GET /api/v1/messaging/conversations[/unread-count]` |
| messaging | + conversation | `POST …/conversations` (`{otherUserId, listingId|null}`) |
| messaging | messages, send, mark read | `…/conversations/{id}/messages`, `…/read` |

Request shapes live in `src/lib/dev-api.ts` and are pinned byte-exact by
`dev-api.test.ts`. Raw rules, on purpose:

- an empty `listingId` is sent as JSON `null`; a filled one as a string
- `content` is sent **verbatim** — the server trims, so the harness can
  prove it
- a non-uuid `conversationId` 400s at the gateway; that error body IS the
  instructive result

Production builds **404 the route**: `page.tsx` calls `notFound()` when
`NODE_ENV=production` (inlined at build time, so the prod prerender ships
no harness markup), and the client harness carries no pre-filled
credentials. Both properties are pinned in `page.test.tsx`.

## Sessions (no better-auth)

The auth service sets `om_access` / `om_refresh` httpOnly cookies. The
frontend will manage sessions itself: Next.js middleware reads the cookie
for route guards, server components forward cookies to gateway endpoints
(e.g. `/users/me`), and the browser never handles raw tokens. Not wired yet.

## Dev

```bash
make frontend            # from repo root — dev server on http://localhost:5173
# or: cd frontend && npm run dev
```

Requires Node ≥ 20.9 (repo uses 22, see `.nvmrc`).

```bash
npm test                 # vitest — the request-shape + gate pins (18 tests)
npm run lint             # eslint
npm run build            # production build (typechecks + prerenders)
```

All three run in CI (`.github/workflows/ci.yml`, `frontend` job) on every
push; `npm run build` is the gate that proves the `/dev` gate prerenders a
harness-free route.

## Conventions

- `src/app/` — routes (App Router). Placeholder routes for the domain
  (listings, chat, profile, admin) come as gateway endpoints land.
- `src/components/ui/` — shadcn components (`npx shadcn@latest add <name>`).
- `src/lib/` — utils (`cn`), the API layer (`dev-api.ts`; a real typed
  client replaces it when OpenAPI type-gen lands).
- Comments state constraints the code can't show (why a rewrite exists,
  why a gate is build-time-inlined) — not narration.

## Deferred

Dockerfile + compose service (dev runs on the host; the compose stack is
backend-only) · OpenAPI type-gen (`openapi-typescript`) · WS client
(messaging pushes land here; the harness is REST-only by design) · session
middleware · real auth UI · i18n · charts.

Known gap, flagged by the 2026-09 audit: the rewrite destination is
hardcoded `http://localhost:3000` with no env override — any non-local
deploy needs `process.env.GATEWAY_URL ?? "http://localhost:3000"` before
the first real deployment.
