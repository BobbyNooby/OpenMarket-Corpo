# frontend — OpenMarket v2 (Next.js)

The v2 frontend: a **Next.js (App Router)** app. It talks **only** to the Go
gateway (REST + WS) — the gateway is the single public entry point and shapes
all service data into UI-ready DTOs (BFF pattern).

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
service), `elysia`/`eden` (no TS server; types will come from the gateway's
OpenAPI), Drizzle (frontend never touches a DB), paraglide i18n and charts
(layerchart → recharts later, when needed).

## Sessions (no better-auth)

The auth service sets `om_access` / `om_refresh` httpOnly cookies. The
frontend will manage sessions itself: Next.js middleware reads the cookie for
route guards, server components forward cookies to gateway endpoints
(e.g. `/users/me`), and the browser never handles raw tokens. Not wired yet.

## Dev

```bash
make frontend            # from repo root — dev server on http://localhost:5173
# or: cd frontend && npm run dev
```

Port is **5173** (v1 parity). The gateway owns :3000.

```bash
npm run lint             # eslint
npm run build            # production build (typechecks)
```

Requires Node ≥ 20.9 (repo uses 22, see `.nvmrc`).

## Conventions

- `src/app/` — routes (App Router). Placeholder routes for the domain
  (listings, chat, profile, admin) come as gateway endpoints land.
- `src/components/ui/` — shadcn components (`npx shadcn@latest add <name>`).
- `src/lib/` — utils (`cn`), future api/ws clients.
- No comments-in-lieu-of-docs; keep it minimal like the backend skeletons.

## Deferred

Dockerfile + compose service · CI job · OpenAPI type-gen (`openapi-typescript`)
· WS client (chat/presence) · session middleware · i18n · charts.
