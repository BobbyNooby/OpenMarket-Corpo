using Catalogue.AuthGrpc;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;
using Grpc.Net.Client;

namespace Catalogue.Auth;

public record IntrospectionResult(bool Active, Guid UserId, IReadOnlyList<string> Roles);

// Abstraction so tests substitute a fake; production dials auth's gRPC.
public interface IIntrospector
{
    /// <returns>null when auth is unreachable (caller must fail closed 503)</returns>
    Task<IntrospectionResult?> IntrospectAsync(string accessToken, CancellationToken ct);
}

public class GrpcIntrospector : IIntrospector
{
    private readonly AuthService.AuthServiceClient client;
    private readonly string secret;

    public GrpcIntrospector(string authGrpcUrl, string secret)
    {
        // Grpc.Net.Client requires the scheme; the gateway's scheme-less
        // auth:9090 form is a trap we do not copy
        var target = authGrpcUrl.StartsWith("http://") || authGrpcUrl.StartsWith("https://")
            ? authGrpcUrl
            : "http://" + authGrpcUrl;
        var channel = GrpcChannel.ForAddress(target, new GrpcChannelOptions
        {
            Credentials = ChannelCredentials.Insecure,
        });
        client = new AuthService.AuthServiceClient(channel);
        this.secret = secret;
    }

    public async Task<IntrospectionResult?> IntrospectAsync(string accessToken, CancellationToken ct)
    {
        var headers = new Metadata { { "x-internal-secret", secret } };
        try
        {
            var resp = await client.IntrospectTokenAsync(
                new IntrospectTokenRequest { AccessToken = accessToken }, headers, deadline: DateTime.UtcNow.AddSeconds(2));
            return Map(resp);
        }
        catch (RpcException e) when (e.StatusCode is StatusCode.Unavailable
            or StatusCode.DeadlineExceeded or StatusCode.Internal or StatusCode.Unknown)
        {
            // infrastructure failure of any shape — caller fails closed 503
            return null;
        }
    }

    /// <summary>
    /// Auth's verdict → local identity. A malformed user id is an auth-side
    /// contract violation: failing closed (null → 503) beats minting a
    /// Guid.Empty identity that would then own listings and trades.
    /// </summary>
    public static IntrospectionResult? Map(IntrospectTokenResponse resp)
    {
        if (!Guid.TryParse(resp.UserId, out var id)) return null;
        return new IntrospectionResult(resp.Active, id, new List<string>(resp.Roles));
    }
}
