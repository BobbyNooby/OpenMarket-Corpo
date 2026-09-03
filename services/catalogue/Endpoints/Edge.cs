using Catalogue.Auth;
using Catalogue.Infrastructure;
using Microsoft.EntityFrameworkCore;

namespace Catalogue.Endpoints;

// Edge checks for catalogue mutations: require a live token (401), an active
// account (403), and a reachable introspector (503 fail-closed). Reads are
// public — browsing a marketplace while banned is harmless; mutations are
// where the introspection ban-check earns its keep (defends against direct
// :8081 access, which bypasses the gateway's edge).
public static class Edge
{
    public static string? ExtractToken(HttpContext ctx)
    {
        var h = ctx.Request.Headers.Authorization.ToString();
        if (h.StartsWith("Bearer ")) return h["Bearer ".Length..];
        return ctx.Request.Cookies.TryGetValue("om_access", out var v) ? v : null;
    }

    public static IntrospectionResult Identity(HttpContext ctx) =>
        (IntrospectionResult)ctx.Items[EdgeIdentityKey]!;

    public static bool HasRole(IntrospectionResult identity, string role) =>
        identity.Roles.Any(r => string.Equals(r, role, StringComparison.Ordinal));

    // admin-or-owner: auth's role hierarchy (owner ⊃ admin) is enforced
    // server-side in auth, but the JWT/introspection only carries raw ids
    public static bool IsCatalogAdmin(IntrospectionResult identity) =>
        HasRole(identity, "admin") || HasRole(identity, "owner");

    /// <summary>Null = proceed (identity stored in ctx.Items). Non-null = write this and stop.</summary>
    public static async Task<IResult?> RequireLiveAsync(HttpContext ctx, IIntrospector intro, ILogger logger)
    {
        var token = ExtractToken(ctx);
        if (token is null)
            return Envelope.Error(StatusCodes.Status401Unauthorized, "unauthorized", "Authentication required");

        var result = await intro.IntrospectAsync(token, ctx.RequestAborted);
        if (result is null)
        {
            logger.LogWarning("introspection unavailable, failing closed");
            return Envelope.Error(StatusCodes.Status503ServiceUnavailable, "service_unavailable",
                "Authentication is temporarily unavailable");
        }
        if (!result.Active)
            return Envelope.Error(StatusCodes.Status403Forbidden, "forbidden",
                "This account cannot perform this action");

        ctx.Items[EdgeIdentityKey] = result;
        return null;
    }

    public static async Task<IResult?> RequireCatalogAdminAsync(HttpContext ctx, IIntrospector intro, ILogger logger)
    {
        if (await RequireLiveAsync(ctx, intro, logger) is { } fail) return fail;
        if (!IsCatalogAdmin(Identity(ctx)))
            return Envelope.Error(StatusCodes.Status403Forbidden, "forbidden", "Insufficient permissions");
        return null;
    }

    public static Guid Sub(HttpContext ctx) =>
        Guid.Parse(ctx.User.FindFirst("sub")!.Value);

    private const string EdgeIdentityKey = "catalogue:identity";
}
