# Testing

> [auth](../README.md) › Testing

How the test suite is structured, what each test *pins*, and how to extend it.
Run everything with `mvn test` (≈140 tests, a few seconds total).

## Contents

- [The three layers](#the-three-layers)
- [Test files and what they pin](#test-files-and-what-they-pin)
- [The fixtures](#the-fixtures)
- [How a contract test works](#how-a-contract-test-works)
- [How a unit test works](#how-a-unit-test-works)
- [What is deliberately NOT tested here](#what-is-deliberately-not-tested-here)
- [Conventions for adding tests](#conventions-for-adding-tests)

---

## The three layers

| Layer | What it loads | Speed | Answers the question | Where |
|---|---|---|---|---|
| **Contract test** (`@WebMvcTest`) | One controller + the real security filter chain; services are mocks | ~0.3 s | "If you send X to path Y, do you get exactly status Z, body shape E, cookie set C?" | `*ContractTest.java` |
| **Unit test** (plain JUnit + Mockito) | One service class, repos mocked | ~0.03 s | "Given this input and this repo state, does the *decision logic* do the right thing?" | `*Test.java` next to the service's package |
| **Integration test** (Testcontainers) | Whole app + real Postgres in Docker | seconds | "Do the transactions actually behave — does the family revoke survive the rollback?" | *future — repo plans it as a fast-follow* |

The point of the first two layers is **contract-pinning**: the HTTP surface
(paths, status codes, error envelope, cookie names/flags, response shapes)
and the decision logic are locked by tests, so everything *below* the
controller boundary can be refactored freely. A red test after a refactor
means the *contract* broke — which is exactly what the frontend and gateway
consume, so that should be a deliberate decision, never an accident.

## Test files and what they pin

### Contract tests (web layer)

| File | Pins |
|---|---|
| `auth/AuthControllerContractTest` | register 201 + both cookies (`om_access` 900 s httpOnly, `om_refresh` path `/api/v1/auth` 7 d); login 200; refresh rotates cookie values; validation → 400 `validation_failed` with `field`; malformed JSON → 400 `malformed_json`; dup email → 409 `email_taken`+`field`; bad creds → 401 `invalid_credentials`; **logout works with no access token at all** (best-effort, always clears cookies) |
| `auth/SessionControllerContractTest` | `GET /sessions` shape (`familyId`, `userAgent`, `ipAddress`, `current` flag); `DELETE /sessions/{id}` → 204 or 404 `session_not_found`; `POST /sessions/revoke-all` → 204; 401 when unauthenticated |
| `auth/CredentialControllerContractTest` | add → 201 (and 409 `password_exists`, 400 `validation_failed` on `password`); change → 204 and the `keepFamily` passed to the service comes from the presented `om_refresh` cookie (current device survives) or null when absent; remove → 204 / 409 `last_login_method`; every method 401 without a token |
| `auth/DiscordOAuthControllerContractTest` | start → 302 to Discord + `om_oauth` cookie (httpOnly, path `/api/v1/auth/discord`); `/link` requires auth and binds the user id into the state; callback with Discord `error` → 302 `?error=oauth_failed`; forged/mismatched state → `oauth_state_mismatch`; login mode → code exchange → auth cookies + 302 success; link mode → `linkDiscord(subject)`; unverified email → `?error=oauth_email_required`; unlink → 204 / 401 |
| `user/UserControllerContractTest` | `/users/me` shape incl. `loginMethods`; identity always from token `sub` (never request); PATCH partial update; PATCH validation (`accentColor` hex → 400 + `field`); unknown user → 404 `user_not_found`; **DELETE → exactly 204** (this test caught it returning 200) |
| `token/JwksControllerContractTest` | JWKS is public; exactly one key; `kty=RSA`, `use=sig`, `alg=RS256`, `kid` present; **no private material** (`d`, `p` absent — that's the leak test); `Cache-Control: max-age=900` |

### Unit tests (service layer)

| File | Pins |
|---|---|
| `token/RefreshTokenServiceTest` | issue → new family, 43-char base64url token, 64-char hash stored (never the raw token), ~7 d TTL, device metadata saved; rotate → old consumed, successor in *same* family, `rotatedFromId` set, device inherited; reuse → `refresh_token_reused` **and family revoke issued**; expired → `refresh_token_expired` (no revoke); unknown → `invalid_refresh_token`; missing → `missing_refresh_token`; revoke / revoke-all / ownership-guarded family revoke; `familyOf` never throws; `listSessions` groups by family, flags current, sorts newest-first, skips expired |
| `auth/AuthServiceTest` | register → email normalized, bcrypt hash ≠ raw, default `user` role, profile defaults (`en`, `{}`), username derivation sanitized/unique/≤32; dup email/username → typed conflicts with `field`; login happy; **unknown email, wrong password, and deleted user all return the identical `invalid_credentials`** (no enumeration); refresh → hands out the *rotated* token and **never calls `issue()` again** (this test pins the "new family per refresh" bug fix); refresh after deletion → `account_deleted`; logout delegates |
| `auth/AuthServiceDiscordTest` | the full linking matrix from [accounts.md](accounts.md): known Discord account → login (no new identity); verified email matching existing user → **auto-link** (no duplicate identity); unknown + verified → new identity with `email_verified=true`, derived username, default role; unverified/missing email → `oauth_email_required` and nothing saved; Discord account of a deleted user → `account_deleted`; link to free/self/foreign Discord account → save/no-op/`provider_already_linked`; unlink → `provider_not_linked` / `last_login_method` / delete |
| `auth/AuthServiceCredentialTest` | add-only-once (`password_exists`); hash stored ≠ raw; change without password → `password_not_set`; wrong current → `invalid_credentials`; happy change → hash replaced **and other devices revoked** (`revokeAllForUserExcept`); remove → `password_not_set` / wrong current / `last_login_method` (no OAuth left) / happy delete |
| `oauth/OAuthStateServiceTest` | the OAuth CSRF defense: issue→validate roundtrip; query-param ↔ cookie must match; tampered payload/signature rejected (HMAC); garbage rejected *silently*; expired state rejected |
| `oauth/DiscordClientTest` | talks to **MockWebServer playing Discord** with real-schema payloads: code exchange posts form-urlencoded with `grant_type/client_id/client_secret` (Discord rejects JSON!) and parses `access_token`; `/users/@me` parses snowflake-string id, snake_case `global_name`, **ignores unknown fields**; HTTP errors → `oauth_failed`; unverified/missing email never trusted; authorize URL contains `response_type/client_id/scope/redirect_uri/state` |
| `user/UserServiceTest` | `me()` maps JSON-string columns → typed maps; `loginMethods` aggregation (password flag + sorted providers); malformed stored JSON degrades to empty maps instead of 500; partial PATCH semantics (null = untouched); username conflict → `username_taken`+`field`; delete → sets `deleted_at` **and** revokes all sessions |

## The fixtures

All in `src/test/java/dev/bob/openmarket/auth/support/`:

- **`TestSecurityConfig`** — replaces the real RS256 `JwtDecoder`. Whatever
  opaque token string arrives becomes a valid Jwt for user
  `11111111-…` with `roles: ["user"]`. Consequence: in contract tests,
  "authenticated" means *any* token is present (header or `om_access`
  cookie) — requests without one prove the 401 path. The stub's fixed
  identity is why `UserControllerContractTest` can assert that the controller
  used the token's `sub`, not anything from the request.
- **`TestKeys`** — a throwaway 2048-bit RSA key so the JWKS slice doesn't
  need key files.
- **`TestUsers`** — factory for `User` entities. JPA-managed fields
  (`id`, `createdAt`, `updatedAt`) have no setters by design; tests set them
  via `ReflectionTestUtils.setField`.

## How a contract test works

```java
@WebMvcTest(AuthController.class)                       // 1. boot ONLY this controller
@Import({SecurityConfig.class,                          // 2. + the REAL security chain
         TokenCookieService.class,                      //    + real cookie transport
         TestSecurityConfig.class})                     //    + stubbed JWT verification
class AuthControllerContractTest {
    @Autowired MockMvc mvc;                             // fake HTTP, real routing
    @MockBean AuthService authService;                  // deps = mocks

    @Test
    void register_duplicate_email_returns_409_envelope() {
        when(authService.register(any(), any(), any()))
            .thenThrow(new ConflictException("email_taken", "…", "email"));

        mvc.perform(post("/api/v1/auth/register")
                .contentType(APPLICATION_JSON).content(VALID_BODY))
            .andExpect(status().isConflict())                    // pin status
            .andExpect(jsonPath("$.code").value("email_taken"))  // pin envelope
            .andExpect(jsonPath("$.field").value("email"));      // pin field
    }
}
```

Read one as: *"when the service throws X, the client sees exactly Y."* The
mock isn't pretending to be the logic (that's the unit tests' job) — it's a
remote control for steering the controller through every branch of the
contract, including branches that are expensive or dangerous to trigger for
real (replay, dup email, deleted account).

## How a unit test works

No Spring at all — construct the service with mocked repositories:

```java
@Test
void rotate_reused_token_throws_refresh_token_reused_and_revokes_family() {
    RefreshToken consumed = token(...);           // revokedAt already set
    when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(consumed));

    service.rotate("raw");                        // ← assertj wraps the expected throw

    assertThatThrownBy(...)                       // pin the error code
    verify(repository).revokeActiveInFamily(eq(FAMILY), any()); // pin the side effect
}
```

Services are annotated `@MockitoSettings(strictness = Strictness.LENIENT)`
because shared setup stubs (e.g. `save`) aren't used by every test; Mockito's
default strict mode treats that as an error. Trade-off accepted deliberately
— per-test minimal stubbing would cost more readability than it buys.

## The flow test — one long run against the live service

`scripts/flow-test.sh` is the third layer, made repeatable. It boots its own
isolated stack (throwaway Postgres on :5433, **fake Discord API** on :5399 —
`scripts/fake-discord.py`, serving real-schema payloads — and the real app on
:8080), then walks the whole contract in ~10 sections / 60+ assertions:

infra & JWKS → registration (+ dup/validation/malformed) → login
(+ enumeration-resistance) → profile → sessions → refresh rotation &
**replay → family revocation** → password credentials (add/change/remove
with all guard rails) → **the complete Discord flow against the fake**
(signup → auto-provisioned profile → add password → password login →
foreign-link conflict redirect → unlink) → logout → account deletion →
access control.

This is the layer that catches what mocks structurally cannot: Flyway
migrations, Hibernate validation against real DDL, real bcrypt/RS256,
transaction semantics, cookie flags over real HTTP. Run it:

```bash
services/auth/scripts/flow-test.sh      # exit 0 = all green
```

## What is deliberately NOT tested here

- **Transaction semantics.** Partially covered now: the flow test proves
  reuse → family revocation end-to-end through real Postgres. Testcontainers
  still planned for finer-grained cases inside `mvn test`.
- **Cryptography.** The contract-test stub decoder bypasses real RS256
  verification. Key loading, signing, and JWKS round-trip are covered by the
  flow test instead (decode the access token, verify against
  `/.well-known/jwks.json`).
- **Controllers `/` and `/health/*`** — trivial passthroughs, no contract.

## Conventions for adding tests

1. Every new endpoint ships with its contract test **in the same change** —
   pin: status code, envelope code(s) + `field`, cookie effects, response
   shape (including what must be *absent*).
2. Every new service decision ships with unit tests — one per branch,
   named `<subject>_<expected outcome>`.
3. Errors: assert on `code`, not on `message` (messages are prose; codes are
   the contract).
4. When the contract legitimately changes, update the test **and** the docs
   (`api.md`) in the same commit — a test that disagrees with the docs is a
   bug in one of them.
5. Never assert log output or internal calls that aren't contract-relevant —
   that's how test suites become refactoring brakes instead of safety nets.

---

Related: [api.md](api.md) (the contract these tests pin) ·
[tokens.md](tokens.md) (the rotation logic under heaviest test)
