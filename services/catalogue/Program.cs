var builder = WebApplication.CreateBuilder(args);
var app = builder.Build();

app.MapGet("/health/live", () => Results.Ok(new { status = "ok" }));

app.MapGet("/health/ready", () =>
{
    var dbUrl = Environment.GetEnvironmentVariable("DATABASE_URL");
    if (string.IsNullOrEmpty(dbUrl))
        return Results.Ok(new { status = "no DATABASE_URL" });
    return Results.Ok(new { status = "ready" });
});

app.MapGet("/", () => Results.Ok(new
{
    service = "catalogue",
    status = "ok",
    version = "0.1.0"
}));

app.Run();
