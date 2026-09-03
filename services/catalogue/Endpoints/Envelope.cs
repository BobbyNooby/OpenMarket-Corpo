using Microsoft.AspNetCore.Http.Json;
using System.Text.Json.Serialization;

namespace Catalogue.Endpoints;

// Same {code, message, field} envelope auth uses; nulls omitted so clients
// can't rely on `field` being present everywhere.
public static class Envelope
{
    public static void Configure(Microsoft.AspNetCore.Http.Json.JsonOptions o) =>
        o.SerializerOptions.DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull;

    public static IResult Error(int status, string code, string message, string? field = null) =>
        Results.Json(new { code, message, field }, statusCode: status);
}
