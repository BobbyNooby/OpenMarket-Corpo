using Catalogue.Auth;
using Catalogue.AuthGrpc;
using Xunit;

namespace Catalogue.Tests;

// pure unit tests — no container, no app host. Pins the introspection
// mapping contract: a malformed identity from auth must fail closed,
// never fabricate a Guid.Empty owner for listings and trades.
public class IntrospectionMappingTests
{
    [Fact]
    public void malformed_user_id_fails_closed()
    {
        var resp = new IntrospectTokenResponse { Active = true, UserId = "not-a-guid" };
        Assert.Null(GrpcIntrospector.Map(resp));
    }

    [Fact]
    public void wellformed_verdict_maps_through()
    {
        var id = Guid.NewGuid();
        var resp = new IntrospectTokenResponse { Active = true, UserId = id.ToString() };
        resp.Roles.Add("user");
        resp.Roles.Add("admin");

        var mapped = GrpcIntrospector.Map(resp);
        Assert.NotNull(mapped);
        Assert.Equal(id, mapped!.UserId);
        Assert.True(mapped.Active);
        Assert.Equal(new[] { "user", "admin" }, mapped.Roles);
    }

    [Fact]
    public void inactive_verdict_still_requires_parseable_identity()
    {
        // even a rejection must carry a real user id — auth is the source of
        // truth for who was rejected; an unparseable id is a contract break
        var resp = new IntrospectTokenResponse { Active = false, UserId = "" };
        Assert.Null(GrpcIntrospector.Map(resp));
    }
}
