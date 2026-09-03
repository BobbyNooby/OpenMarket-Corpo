# Frontend testing

> [frontend](../README.md) › Testing

18 vitest tests across two files. Run from `frontend/`:

```bash
npm test                 # vitest run (also runs in CI on every push)
```

The stack: vitest 5 + `@testing-library/react` (jsdom) + `userEvent`, wired
in `vitest.config.ts` with the `@` → `src` alias and `src/test/setup.ts`.

## The layers

| Layer | Where | What it proves |
|---|---|---|
| Request-shape contracts | `src/lib/dev-api.test.ts` (12) | the API layer's byte-exact request shapes: same-origin relative paths (`startsWith("/")` — never absolute, so cookies stay first-party through the Next.js rewrite), exact JSON bodies (register `{email,password,name}`, login `{email,password}`, create-conversation `{otherUserId, listingId: null\|string}`), forced `Content-Type: application/json` on every request, bodyless POSTs stay bodyless, conversation-id interpolation, `listingId: ""` → JSON `null`, content sent verbatim (the server trims — the harness can prove it), pretty-printed bodies with an `(empty body)` fallback, network failures logged with `status: null` |
| DOM contract | `src/app/dev/page.test.tsx` (4) | what a human sees: calls render newest-first with green `<200` / red `>=400` status classes, non-JSON bodies pass through raw, network errors read as "network error" |
| Production gate | `src/app/dev/page.test.tsx` (2) | the raw harness never ships: `isProductionBuild` (pure predicate — the bundler inlines `NODE_ENV`, so rendering can't exercise the prod branch in vitest) rejects `"production"` and accepts everything else; and the harness inputs start empty (no pre-filled credentials — the demo account is gone, not just hidden) |

## Why the shapes get their own layer

`dev-api.ts` is the only place that knows the gateway's request shapes.
Pinning them byte-exact means a gateway contract change fails a frontend
test before it fails a user. This is the same idea as the backend's
contract tests, minus the network: the fetcher is injected, so the tests
run without a gateway.

## The harness wiring test

`page.test.tsx`'s *send* test goes one level up from shapes: it types into
the actual harness inputs, clicks `send`, and asserts the exact
`fetch(path, init)` the browser would emit — path with the interpolated
conversation id, POST, JSON content-type, body `{content}` — plus the
rendered 201. That pins the inputs → `messagingCalls` → `call` → fetch
wiring, not just the pieces.

## Deliberately NOT tested (yet)

- **The Next.js rewrite** (`next.config.ts`) — it's config, not code; the
  full chain is exercised manually via the `/dev` harness against a running
  gateway, not in vitest.
- **The prod build's harness-free prerender** — `npm run build` output is
  what proves `/dev` renders a 404 shell; there is no automated grep of
  `.next/` yet (done by hand after builds, see the 2026-09 audit addendum).
- **fetch `credentials` mode / cookie behavior** — nothing to pin until
  session middleware exists (README "Sessions"); the current default
  (`same-origin`) is asserted indirectly by the relative-path pins.
- **WS client** — messaging pushes have no frontend consumer yet; the
  harness is REST-only by design.
