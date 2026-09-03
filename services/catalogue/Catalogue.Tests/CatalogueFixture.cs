using Xunit;
using System.Net;
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using Catalogue.Auth;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;
using Testcontainers.PostgreSql;

namespace Catalogue.Tests;

[CollectionDefinition("catalogue")]
public class CatalogueCollection : ICollectionFixture<CatalogueFixture>;

/// <summary>
/// Real Postgres (Testcontainers) + the real app (WebApplicationFactory).
/// JWTs are signed with a test RSA key whose public half is injected as the
/// IssuerSigningKey, so no JWKS fetch happens; the introspector is a fake
/// whose behavior each test controls.
/// </summary>
public class CatalogueFixture : IAsyncLifetime
{
    public PostgreSqlContainer Postgres { get; } = new PostgreSqlBuilder()
        .WithImage("postgres:17")
        .WithUsername("catalogue")
        .WithPassword("catalogue-test")
        .WithDatabase("catalogue_db")
        .Build();

    public FakeIntrospector Introspector { get; } = new();

    public RsaSecurityKey SigningKey { get; private set; } = null!;
    private RsaSecurityKey PrivateKey { get; set; } = null!;

    public WebApplicationFactory<Program> Factory { get; private set; } = null!;

    public async Task InitializeAsync()
    {
        await Postgres.StartAsync();

        var rsa = RSA.Create(2048);
        SigningKey = new RsaSecurityKey(rsa.ExportParameters(false)) { KeyId = "test-key" };
        PrivateKey = new RsaSecurityKey(rsa);

        Factory = new WebApplicationFactory<Program>().WithWebHostBuilder(b =>
        {
            b.ConfigureLogging(l => l.AddSimpleConsole().SetMinimumLevel(LogLevel.Error));
            // real libpq-shaped URL — exercises the same parser production uses
            b.UseSetting("DATABASE_URL",
                $"postgres://catalogue:catalogue-test@{Postgres.Hostname}:{Postgres.GetMappedPublicPort(5432)}/catalogue_db");
            b.ConfigureTestServices(services =>
            {
                var existing = services.FirstOrDefault(d => d.ServiceType == typeof(IIntrospector));
                if (existing is not null) services.Remove(existing);
                services.AddSingleton<IIntrospector>(Introspector);
                services.PostConfigure<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme, o =>
                {
                    o.TokenValidationParameters.IssuerSigningKey = SigningKey;
                });
            });
        });
        // boots the app: migrations + seeding run here, once
        _ = Factory.CreateClient();
    }

    public async Task DisposeAsync()
    {
        Factory.Dispose();
        await Postgres.DisposeAsync();
    }

    public HttpClient ClientFor(string token) => ClientFor(token, out _);

    public HttpClient ClientFor(string token, out string userId)
    {
        userId = Introspector.UserFor(token).ToString();
        var handler = new HttpClientHandler { CookieContainer = new CookieContainer() };
        var client = Factory.CreateDefaultClient();
        client.DefaultRequestHeaders.Authorization = new("Bearer", token);
        return client;
    }

    public string TokenFor(string user, string[]? roles = null, DateTime? expires = null) =>
        Introspector.TokenFor(user, roles, expires, PrivateKey);
}

/// <summary>
/// Deterministic fake: tokens of the form "token-&lt;name&gt;" map to stable
/// per-name UUIDs; tests flip Active/Roles per scenario. Null result =
/// introspection unavailable (fail-closed 503 path).
/// </summary>
public class FakeIntrospector : IIntrospector
{
    private (bool active, Guid user, string[] roles)? configured;

    public Func<string, (bool active, Guid user, string[] roles)?>? Override { get; set; }
    public int CallCount;
    private readonly Dictionary<string, (bool active, Guid user, string[] roles)> registered = new();

    /// <summary>Register what introspection should answer for this exact token.</summary>
    public void Register(string token, Guid user, string[] roles) =>
        registered[token] = (true, user, roles);

    public Guid UserFor(string token) =>
        GuidUtility.FromName(token);

    public string TokenFor(string user, string[]? roles = null, DateTime? expires = null, RsaSecurityKey? key = null)
    {
        var userId = GuidUtility.FromName(user);
        roles ??= ["user"];
        var descriptor = new Microsoft.IdentityModel.Tokens.SecurityTokenDescriptor
        {
            Issuer = "auth",
            Audience = "openmarket",
            Claims = new Dictionary<string, object>
            {
                ["sub"] = userId.ToString(),
                ["roles"] = roles,
            },
            Expires = expires ?? DateTime.UtcNow.AddMinutes(15),
            SigningCredentials = new SigningCredentials(key ?? throw new InvalidOperationException("key not ready"),
                SecurityAlgorithms.RsaSha256),
        };
        var handler = new JsonWebTokenHandler();
        // claims written verbatim (no inbound-claim mapping on outbound tokens)
        var token = handler.CreateToken(descriptor);
        Register(token, userId, roles);
        return token;
    }

    public Task<IntrospectionResult?> IntrospectAsync(string accessToken, CancellationToken ct)
    {
        Interlocked.Increment(ref CallCount);
        if (Override is not null)
        {
            // an override returning null means "introspection unavailable" —
            // callers must fail closed, never fall through to defaults
            var o = Override(accessToken);
            return o is null
                ? Task.FromResult<IntrospectionResult?>(null)
                : Task.FromResult<IntrospectionResult?>(ToResult(o.Value));
        }
        if (registered.TryGetValue(accessToken, out var reg)) return Task.FromResult<IntrospectionResult?>(ToResult(reg));
        if (configured is { } c) return Task.FromResult<IntrospectionResult?>(ToResult(c));
        var user = GuidUtility.FromName(accessToken);
        return Task.FromResult<IntrospectionResult?>(new IntrospectionResult(true, user, ["user"]));
    }

    private static IntrospectionResult ToResult((bool active, Guid user, string[] roles) c) =>
        new(c.active, c.user, c.roles);
}

public static class GuidUtility
{
    public static Guid FromName(string name)
    {
        var bytes = SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(name));
        return new Guid(bytes[..16]);
    }
}
