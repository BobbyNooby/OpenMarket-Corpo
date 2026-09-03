using Catalogue.Auth;
using Catalogue.Endpoints;
using Catalogue.Infrastructure;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Http.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.IdentityModel.Tokens;
using Npgsql;
using System.Text.Json.Serialization;

var builder = WebApplication.CreateBuilder(args);

// ── configuration (env-first, localhost defaults for bare `dotnet run`) ──
// reads via IConfiguration so host-level settings (tests) and env vars both work
var databaseUrl = builder.Configuration["DATABASE_URL"];
var pgHost = builder.Configuration["POSTGRES_HOST"] ?? "localhost";
var pgPort = int.TryParse(builder.Configuration["POSTGRES_PORT"], out var pp) ? pp : 5432;
var pgUser = builder.Configuration["POSTGRES_USER"] ?? "om";
var pgPassword = builder.Configuration["POSTGRES_PASSWORD"] ?? "devpassword123";
var sslMode = builder.Configuration["DATABASE_SSLMODE"] ?? "auto";
var authUrl = builder.Configuration["AUTH_URL"] ?? "http://localhost:8080";
var authGrpcUrl = builder.Configuration["AUTH_GRPC_URL"] ?? "http://localhost:9090";
var internalSecret = builder.Configuration["GRPC_INTERNAL_SECRET"] ?? "dev-internal-secret";

// dev fallbacks must never silently survive into a prod-shaped environment.
// (The compose stack runs Development explicitly — it IS the dev environment.)
if (builder.Environment.IsProduction())
{
    if (internalSecret == "dev-internal-secret")
        throw new InvalidOperationException(
            "GRPC_INTERNAL_SECRET is unset or still the dev default — refusing to start in Production");
    if (pgPassword == "devpassword123")
        throw new InvalidOperationException(
            "POSTGRES_PASSWORD is unset or still the dev default — refusing to start in Production");
}

var connectionString = DatabaseUrl.ToConnectionString(databaseUrl, pgHost, pgPort, pgUser, pgPassword, sslMode);

builder.Services.AddNpgsqlDataSource(connectionString);
builder.Services.AddDbContext<CatalogueDbContext>(o => o.UseNpgsql(connectionString));

// ── identity: tokens minted by auth, validated locally against its JWKS ──
// catalog mutations ALSO call IntrospectToken (ban check) — see edge-auth docs
builder.Services
    .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(o =>
    {
        // auth publishes a bare JWKS (no OIDC discovery doc). Fetch the key
        // ONCE at startup and pin it — deterministic validation, no lazy
        // configuration-fetch races. Auth's key lives on a persistent volume,
        // so rotation (and therefore restarts) is rare by design.
        o.RequireHttpsMetadata = false; // internal http hop; TLS is a fleet-level deferral
        o.MapInboundClaims = false; // read claims as issued: "sub", "roles"
        o.TokenValidationParameters = new TokenValidationParameters
        {
            ValidIssuer = "auth",
            ValidAudience = "openmarket",
            ValidAlgorithms = ["RS256"], // alg-confusion hard stop
            ClockSkew = TimeSpan.FromSeconds(30),
            NameClaimType = "sub",
            RoleClaimType = "roles",
            // lazy, fail-soft: fetch failure -> no keys -> 401 fail-closed
            IssuerSigningKeyResolver = (token, st, kid, parameters) =>
                SigningKeyResolver.Resolve(authUrl, kid),
        };
        // the /dev harness and the browser use the om_access cookie; header wins
        o.Events = new JwtBearerEvents
        {
            OnMessageReceived = e =>
            {
                if (e.Token is "" or null
                    && e.Request.Cookies.TryGetValue("om_access", out var cookie))
                    e.Token = cookie;
                return Task.CompletedTask;
            },
            // require a parseable sub at the boundary — handlers never see an
            // identity that couldn't be a real user id (Edge.Sub stays total)
            OnTokenValidated = e =>
            {
                if (!Guid.TryParse(e.Principal?.FindFirst("sub")?.Value, out _))
                    e.Fail("token has no parseable sub claim");
                return Task.CompletedTask;
            },
        };
    });
builder.Services.AddAuthorization(o =>
{
    // deny-by-default: only endpoints explicitly marked AllowAnonymous are open
    o.FallbackPolicy = o.DefaultPolicy;
});
builder.Services.AddSingleton<IIntrospector>(new GrpcIntrospector(authGrpcUrl, internalSecret));
builder.Services.AddHostedService<ExpiryScanner>();

builder.Services.ConfigureHttpJsonOptions(Envelope.Configure);
builder.Services.AddOpenApi();

var app = builder.Build();

var logger = app.Logger;

// last-resort handler: log the exception server-side, answer with the
// standard envelope, never leak stack traces
app.UseExceptionHandler(a => a.Run(async context =>
{
    var feature = context.Features.Get<Microsoft.AspNetCore.Diagnostics.IExceptionHandlerFeature>();
    if (feature is not null)
        logger.LogError(feature.Error, "unhandled exception on {method} {path}",
            context.Request.Method, context.Request.Path);
    await Results.Json(new { code = "internal_error", message = "Unexpected error" },
        statusCode: 500).ExecuteAsync(context);
}));

// ── migrations at startup (auth's Flyway-on-startup precedent), under an
// advisory lock so a second replica waits instead of racing
using (var migrateScope = app.Services.CreateScope())
    await MigrationsRunner.RunAsync(
        app.Services.GetRequiredService<NpgsqlDataSource>(),
        migrateScope.ServiceProvider,
        app.Logger,
        app.Lifetime.ApplicationStopping);

app.MapGet("/health/live", () => Results.Json(new { status = "ok" })).AllowAnonymous();
app.MapGet("/health/ready", async (NpgsqlDataSource ds) =>
{
    try
    {
        await using var conn = await ds.OpenConnectionAsync();
        var depth = 0L;
        await using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT count(*) FROM \"Outbox\" WHERE \"PublishedAt\" IS NULL";
        depth = (long)(await cmd.ExecuteScalarAsync())!;
        // outbox backpressure degrades readiness once the relay lands; before
        // that it warns loudly in the payload while staying honest about 503
        if (depth > 10_000)
            return Results.Json(new { status = "degraded", outboxDepth = depth }, statusCode: 503);
        return Results.Json(new { status = "ready", outboxDepth = depth });
    }
    catch (Exception)
    {
        return Results.Json(new { status = "db_unreachable" }, statusCode: 503);
    }
})
.AllowAnonymous();
app.MapGet("/", () => Results.Json(new { service = "catalogue", status = "ok", version = "0.2.0" }))
    .AllowAnonymous();

app.MapOpenApi().AllowAnonymous();

app.MapListings(app.Services.GetRequiredService<IIntrospector>(), logger);
app.MapCatalog(app.Services.GetRequiredService<IIntrospector>(), logger);
app.MapMe(app.Services.GetRequiredService<IIntrospector>(), logger);

app.Run();

// exposes the implicit Program class to WebApplicationFactory in tests
public partial class Program { }

// Fetches auth's public signing key from its JWKS endpoint — lazily at
// first validation, cached for an hour, fail-soft (fetch failure returns
// no keys => tokens 401 fail-closed rather than 500ing the request).
// Auth's key lives on a persistent volume, so refreshes are rare by design.
public static class SigningKeyResolver
{
    private static readonly TimeSpan CacheTtl = TimeSpan.FromHours(1);
    private static readonly object Lock = new();
    private static string? _cachedKid;
    private static Microsoft.IdentityModel.Tokens.RsaSecurityKey? _cachedKey;
    private static DateTime _fetchedAt;

    public static IEnumerable<Microsoft.IdentityModel.Tokens.SecurityKey> Resolve(string authUrl, string? kid)
    {
        lock (Lock)
        {
            if (_cachedKey is null || DateTime.UtcNow - _fetchedAt > CacheTtl)
                Refresh(authUrl);
        }
        if (_cachedKey is null) yield break;
        if (kid is null || _cachedKid == kid) yield return _cachedKey;
    }

    private static void Refresh(string authUrl)
    {
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
            var json = http.GetStringAsync($"{authUrl}/.well-known/jwks.json").GetAwaiter().GetResult();
            var jwks = new Microsoft.IdentityModel.Tokens.JsonWebKeySet(json);
            var rsaJwk = jwks.Keys.FirstOrDefault(k => k.Kty == "RSA")
                ?? throw new InvalidOperationException("auth JWKS contained no RSA key");
            _cachedKid = rsaJwk.Kid;
            _cachedKey = new Microsoft.IdentityModel.Tokens.RsaSecurityKey(
                new System.Security.Cryptography.RSAParameters
                {
                    Exponent = Microsoft.IdentityModel.Tokens.Base64UrlEncoder.DecodeBytes(rsaJwk.E),
                    Modulus = Microsoft.IdentityModel.Tokens.Base64UrlEncoder.DecodeBytes(rsaJwk.N),
                });
            _fetchedAt = DateTime.UtcNow;
        }
        catch (Exception)
        {
            _cachedKey = null; // fail soft; next validation retries the fetch
        }
    }
}





