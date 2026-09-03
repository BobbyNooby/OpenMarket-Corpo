using System.Net;
using System.Text;

using Npgsql;

namespace Catalogue.Infrastructure;

// Parses the libpq-style DATABASE_URL the compose stack hands out
// (postgres://user:pass@host:port/db) into an Npgsql connection string,
// with a localhost:5433-ish fallback for bare `dotnet run`.
public static class DatabaseUrl
{
    public static string ToConnectionString(string? databaseUrl, string pgHost, int pgPort, string pgUser, string pgPassword, string sslMode)
    {
        if (string.IsNullOrWhiteSpace(databaseUrl))
        {
            return new NpgsqlConnectionStringBuilder
            {
                Host = pgHost,
                Port = pgPort,
                Username = pgUser,
                Password = pgPassword,
                Database = "catalogue_db",
                SslMode = ParseSslMode(sslMode is "" or "auto" ? "disable" : sslMode),
            }.ConnectionString;
        }

        var uri = new Uri(databaseUrl);
        var userInfo = (uri.UserInfo ?? "").Split(':', 2);
        var builder = new NpgsqlConnectionStringBuilder
        {
            Host = uri.Host,
            Port = uri.Port > 0 ? uri.Port : 5432,
            Username = Uri.UnescapeDataString(userInfo[0]),
            Password = userInfo.Length > 1 ? Uri.UnescapeDataString(userInfo[1]) : "",
            Database = uri.AbsolutePath.TrimStart('/'),
        };
        // libpq heuristic (mirrors auth's DatabaseConfig): remote host => require,
        // loopback => disable; DATABASE_SSLMODE overrides both.
        builder.SslMode = uri.Host is "localhost" or "127.0.0.1" or "::1"
            ? ParseSslMode(sslMode is "" or "auto" ? "disable" : sslMode)
            : ParseSslMode(sslMode is "" or "auto" ? "require" : sslMode);
        return builder.ConnectionString;
    }

    // compose hands out DATABASE_SSLMODE=disable; anything unrecognized fails fast
    private static SslMode ParseSslMode(string mode) => mode switch
    {
        "disable" => SslMode.Disable,
        "require" => SslMode.Require,
        "prefer" => SslMode.Prefer,
        _ => throw new InvalidOperationException($"Unsupported DATABASE_SSLMODE '{mode}' (use disable|require|prefer)"),
    };
}
