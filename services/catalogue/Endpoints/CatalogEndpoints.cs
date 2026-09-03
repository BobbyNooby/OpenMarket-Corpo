using Catalogue.Auth;
using Catalogue.Domain;
using Catalogue.Infrastructure;
using Microsoft.EntityFrameworkCore;

namespace Catalogue.Endpoints;

// Shared catalog definitions: public browse, admin-or-owner create + retire.
// Retire = hide, never hard-delete (listings referencing a definition must
// not mass-vanish — the v1 cascade was the defacement risk).
public static class CatalogEndpoints
{
    public record CreateCatalogRequest(string Name, string? Description, string? CategorySlug,
        string? WikiLink, string? ImageUrl);

    public static IEndpointConventionBuilder MapCatalog(this IEndpointRouteBuilder app,
        IIntrospector intro, ILogger logger)
    {
        var g = app.MapGroup("/api/v1/catalogue").WithTags("catalog");
        var db = (HttpContext ctx) => ctx.RequestServices.GetRequiredService<CatalogueDbContext>();

        // ── public browse ───────────────────────────────────────
        g.MapGet("/categories", async (HttpContext ctx) =>
        {
            var categories = await db(ctx).Categories.OrderBy(c => c.Name)
                .Select(c => new { c.Id, c.Slug, c.Name, c.IconUrl, retired = c.RetiredAt != null })
                .ToListAsync();
            return Results.Json(new { categories });
        })
        .AllowAnonymous();

        g.MapGet("/items", async (HttpContext ctx, int? limit, int? offset, bool includeRetired = false) =>
        {
            var context = db(ctx);
            var (safeLimit, safeOffset) = Page(limit, offset);
            var q = context.Items.AsNoTracking().OrderBy(i => i.Name);
            if (!includeRetired) q = (IOrderedQueryable<Item>)q.Where(i => i.RetiredAt == null);
            var total = await q.CountAsync();
            var items = await q.Skip(safeOffset).Take(safeLimit)
                .Select(i => new
                {
                    i.Id,
                    i.Slug,
                    i.Name,
                    i.Description,
                    i.ImageUrl,
                    category = i.Category!.Slug,
                    retired = i.RetiredAt != null
                })
                .ToListAsync();
            return Results.Json(new { items, total });
        })
        .AllowAnonymous();

        g.MapGet("/items/{slug}", async (HttpContext ctx, string slug) =>
        {
            var item = await db(ctx).Items.AsNoTracking().Include(i => i.Category)
                .FirstOrDefaultAsync(i => i.Slug == slug);
            return item is null
                ? Envelope.Error(404, "not_found", "Unknown item")
                : Results.Json(new
                {
                    item.Id,
                    item.Slug,
                    item.Name,
                    item.Description,
                    item.WikiLink,
                    item.ImageUrl,
                    category = item.Category?.Slug,
                    retired = item.RetiredAt != null
                });
        })
        .AllowAnonymous();

        g.MapGet("/currencies", async (HttpContext ctx) =>
        {
            var currencies = await db(ctx).Currencies.OrderBy(c => c.Name)
                .Select(c => new { c.Id, c.Slug, c.Name, retired = c.RetiredAt != null }).ToListAsync();
            return Results.Json(new { currencies });
        })
        .AllowAnonymous();

        g.MapGet("/currencies/{slug}", async (HttpContext ctx, string slug) =>
        {
            var cur = await db(ctx).Currencies.AsNoTracking().FirstOrDefaultAsync(c => c.Slug == slug);
            if (cur is null) return Envelope.Error(404, "not_found", "Unknown currency");
            return Results.Json(new { cur.Id, cur.Slug, cur.Name, cur.Description, cur.WikiLink, retired = cur.RetiredAt != null });
        })
        .AllowAnonymous();

        // ── admin-or-owner mutations ────────────────────────────
        g.MapPost("/categories", async (HttpContext ctx) =>
        {
            if (await Edge.RequireCatalogAdminAsync(ctx, intro, logger) is { } fail) return fail;
            var body = await ctx.Request.ReadFromJsonAsync<CreateCatalogRequest>();
            if (body is null || string.IsNullOrWhiteSpace(body.Name))
                return Envelope.Error(400, "validation_failed", "Name is required", "name");

            var category = new ItemCategory { Name = body.Name.Trim(), Slug = await SlugFor(db(ctx), body.Name, "category"), IconUrl = body.ImageUrl };
            db(ctx).Categories.Add(category);
            try
            {
                await db(ctx).SaveChangesAsync();
            }
            catch (DbUpdateException)
            {
                return Envelope.Error(409, "slug_taken", "A category with this name already exists");
            }
            return Results.Json(new { category.Id, category.Slug, category.Name }, statusCode: 201);
        });

        g.MapPost("/items", async (HttpContext ctx) =>
        {
            if (await Edge.RequireCatalogAdminAsync(ctx, intro, logger) is { } fail) return fail;
            var body = await ctx.Request.ReadFromJsonAsync<CreateCatalogRequest>();
            if (body is null || string.IsNullOrWhiteSpace(body.Name))
                return Envelope.Error(400, "validation_failed", "Name is required", "name");

            Guid? categoryId = null;
            if (body.CategorySlug is { } catSlug)
            {
                categoryId = (await db(ctx).Categories.FirstOrDefaultAsync(c => c.Slug == catSlug))?.Id;
                if (categoryId is null)
                    return Envelope.Error(400, "unknown_category", "Unknown category", "categorySlug");
            }

            var item = new Item
            {
                Name = body.Name.Trim(),
                Slug = await SlugFor(db(ctx), body.Name),
                Description = body.Description,
                WikiLink = body.WikiLink,
                ImageUrl = body.ImageUrl,
                CategoryId = categoryId,
            };
            db(ctx).Items.Add(item);
            try
            {
                await db(ctx).SaveChangesAsync();
            }
            catch (DbUpdateException)
            {
                return Envelope.Error(409, "slug_taken", "An item with this name already exists");
            }
            return Results.Json(new { item.Id, item.Slug, item.Name }, statusCode: 201);
        });

        g.MapPost("/currencies", async (HttpContext ctx) =>
        {
            if (await Edge.RequireCatalogAdminAsync(ctx, intro, logger) is { } fail) return fail;
            var body = await ctx.Request.ReadFromJsonAsync<CreateCatalogRequest>();
            if (body is null || string.IsNullOrWhiteSpace(body.Name))
                return Envelope.Error(400, "validation_failed", "Name is required", "name");

            var currency = new Currency
            {
                Name = body.Name.Trim(),
                Slug = await SlugFor(db(ctx), body.Name, "currency"),
                Description = body.Description
            };
            db(ctx).Currencies.Add(currency);
            try
            {
                await db(ctx).SaveChangesAsync();
            }
            catch (DbUpdateException)
            {
                return Envelope.Error(409, "slug_taken", "A currency with this name already exists");
            }
            return Results.Json(new { currency.Id, currency.Slug, currency.Name }, statusCode: 201);
        });

        // retire = hide: blocked for new listings, existing data untouched
        g.MapPost("/items/{slug}/retire", async (HttpContext ctx, string slug) =>
        {
            if (await Edge.RequireCatalogAdminAsync(ctx, intro, logger) is { } fail) return fail;
            var item = await db(ctx).Items.FirstOrDefaultAsync(i => i.Slug == slug);
            if (item is null) return Envelope.Error(404, "not_found", "Unknown item");
            if (item.RetiredAt is null)
            {
                item.RetiredAt = DateTime.UtcNow;
                await db(ctx).SaveChangesAsync();
            }
            return Results.Json(new { item.Slug, retired = true });
        });

        g.MapPost("/categories/{slug}/retire", async (HttpContext ctx, string slug) =>
        {
            if (await Edge.RequireCatalogAdminAsync(ctx, intro, logger) is { } fail) return fail;
            var category = await db(ctx).Categories.FirstOrDefaultAsync(c => c.Slug == slug);
            if (category is null) return Envelope.Error(404, "not_found", "Unknown category");
            if (category.RetiredAt is null)
            {
                category.RetiredAt = DateTime.UtcNow;
                await db(ctx).SaveChangesAsync();
            }
            return Results.Json(new { category.Slug, retired = true });
        });

        g.MapPost("/currencies/{slug}/retire", async (HttpContext ctx, string slug) =>
        {
            if (await Edge.RequireCatalogAdminAsync(ctx, intro, logger) is { } fail) return fail;
            var currency = await db(ctx).Currencies.FirstOrDefaultAsync(c => c.Slug == slug);
            if (currency is null) return Envelope.Error(404, "not_found", "Unknown currency");
            if (currency.RetiredAt is null)
            {
                currency.RetiredAt = DateTime.UtcNow;
                await db(ctx).SaveChangesAsync();
            }
            return Results.Json(new { currency.Slug, retired = true });
        });

        return g;
    }

    public static async Task<string> SlugFor(CatalogueDbContext db, string name, string entity = "item")
    {
        // NFKC-normalized, URL-safe, immutable identifier
        var normalized = name.Normalize(System.Text.NormalizationForm.FormKC).ToLowerInvariant();
        var sb = new System.Text.StringBuilder();
        foreach (var c in normalized)
            sb.Append(char.IsAsciiLetterOrDigit(c) ? c : '-');
        var slug = sb.ToString().Trim('-');
        while (slug.Contains("--")) slug = slug.Replace("--", "-");
        if (slug.Length == 0) slug = "item";
        slug = slug.Length > 64 ? slug[..64].TrimEnd('-') : slug;

        // probe the table the slug will actually live in — a category colliding
        // with an item's name is fine; one colliding with another category is not
        var taken = entity switch
        {
            "category" => await db.Categories.AnyAsync(c => c.Slug == slug),
            "currency" => await db.Currencies.AnyAsync(c => c.Slug == slug),
            _ => await db.Items.AnyAsync(i => i.Slug == slug),
        };
        // per-table uniqueness is what matters; cross-table sharing is fine
        if (!taken) return slug;
        for (var n = 2; n < 50; n++)
        {
            var candidate = $"{slug}-{n}";
            taken = entity switch
            {
                "category" => await db.Categories.AnyAsync(c => c.Slug == candidate),
                "currency" => await db.Currencies.AnyAsync(c => c.Slug == candidate),
                _ => await db.Items.AnyAsync(i => i.Slug == candidate),
            };
            if (!taken) return candidate;
        }
        return $"{slug}-{Guid.NewGuid().ToString()[..8]}";
    }

    private static (int limit, int offset) Page(int? limit, int? offset) =>
        (Math.Clamp(limit ?? 20, 1, 50), Math.Max(offset ?? 0, 0));
}
