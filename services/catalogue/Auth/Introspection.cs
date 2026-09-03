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
            var roles = new List<string>(resp.Roles);
            return new IntrospectionResult(resp.Active, Guid.TryParse(resp.UserId, out var id) ? id : Guid.Empty, roles);
        }
        catch (RpcException e) when (e.StatusCode == StatusCode.Unavailable
            || e.StatusCode == StatusCode.DeadlineExceeded)
        {
            return null; // caller fails closed 503
        }
    }
}
