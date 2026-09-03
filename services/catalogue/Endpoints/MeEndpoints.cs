using Catalogue.Auth;
using Catalogue.Domain;
using Catalogue.Infrastructure;
using Microsoft.EntityFrameworkCore;

namespace Catalogue.Endpoints;

public record AddItemListRequest(string ListType, Guid? ItemId, Guid? CurrencyId);

public static class MeEndpoints
{
    private const int WatchlistCap = 500;
    private const int ItemListCap = 200;

    public static IEndpointConventionBuilder MapMe(this IEndpointRouteBuilder app,
        IIntrospector intro, ILogger logger)
    {
        var g = app.MapGroup("/api/v1/catalogue/me").WithTags("me");
        CatalogueDbContext Db(HttpContext ctx) => ctx.RequestServices.GetRequiredService<CatalogueDbContext>();

        // ── watchlist ───────────────────────────────────────────
        // NOTE: "my listings" intentionally lives only in ListingEndpoints
        // (/listings/me/listings — with the ban check); the former /me/listings
        // copy here was the same query without the ban check, removed as dup

        g.MapGet("/watchlist", async (HttpContext ctx) =>
        {
            var sub = Edge.Sub(ctx);
            var entries = await Db(ctx).Watchlist.AsNoTracking()
                .Include(w => w.Listing)
                .Where(w => w.UserId == sub)
                .OrderByDescending(w => w.CreatedAt)
                .Select(w => new
                {
                    w.ListingId,
                    w.CreatedAt,
                    listing = new
                    {
                        id = w.Listing.Id,
                        status = w.Listing.Status.ToString().ToLowerInvariant(),
                        orderType = w.Listing.OrderType.ToString().ToLowerInvariant(),
                        amount = w.Listing.Amount,
                        expiresAt = w.Listing.ExpiresAt,
                    }
                })
                .ToListAsync();
            return Results.Json(new { entries });
        });

        g.MapPut("/watchlist/{listingId:guid}", async (HttpContext ctx, Guid listingId,
            IIntrospector introspector) =>
        {
            if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
            var sub = Edge.Sub(ctx);
            var db = Db(ctx);
            if (!await db.Listings.AnyAsync(l => l.Id == listingId))
                return Envelope.Error(404, "not_found", "Unknown listing");

            var count = await db.Watchlist.CountAsync(w => w.UserId == sub);
            var exists = await db.Watchlist.AnyAsync(w => w.UserId == sub && w.ListingId == listingId);
            if (!exists && count >= WatchlistCap)
                return Envelope.Error(409, "watchlist_full", "Your watchlist is full");

            var existsEntry = await db.Watchlist.FindAsync(sub, listingId);
            if (existsEntry is null)
                db.Watchlist.Add(new WatchlistEntry { UserId = sub, ListingId = listingId });
            await db.SaveChangesAsync();
            return Results.Ok(new { listingId, watching = true });
        });

        g.MapDelete("/watchlist/{listingId:guid}", async (HttpContext ctx, Guid listingId,
            IIntrospector introspector) =>
        {
            // same ban gate as every other mutation — banned users get no
            // write path, not even "unwatch" (policy parity, Edge.cs)
            if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
            var sub = Edge.Sub(ctx);
            var removed = await Db(ctx).Watchlist
                .Where(w => w.UserId == sub && w.ListingId == listingId)
                .ExecuteDeleteAsync();
            return removed == 1
                ? Results.Ok(new { listingId, watching = false })
                : Envelope.Error(404, "not_found", "Not on your watchlist");
        });

        // ── have/want lists ─────────────────────────────────────
        g.MapGet("/item-lists", async (HttpContext ctx, string? listType) =>
        {
            var sub = Edge.Sub(ctx);
            var db = Db(ctx);
            var query = db.UserItemLists.AsNoTracking().Where(l => l.UserId == sub);
            if (listType is not null)
            {
                if (!System.Enum.TryParse<ItemListType>(listType, ignoreCase: true, out var lt))
                    return Envelope.Error(400, "validation_failed", "Unknown listType", "listType");
                query = query.Where(l => l.ListType == lt);
            }
            var entries = await query
                .OrderByDescending(l => l.CreatedAt)
                .Select(l => new
                {
                    l.Id,
                    listType = l.ListType.ToString().ToLowerInvariant(),
                    l.ItemId,
                    l.CurrencyId,
                    l.CreatedAt
                })
                .ToListAsync();
            return Results.Json(new { entries });
        });

        g.MapPost("/item-lists", async (HttpContext ctx, IIntrospector introspector) =>
        {
            if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
            var sub = Edge.Sub(ctx);
            var db = Db(ctx);
            var body = await ctx.Request.ReadFromJsonAsync<AddItemListRequest>();
            if (body is null
                || !System.Enum.TryParse<ItemListType>(body.ListType, ignoreCase: true, out var listType)
                || num_non_nulls(body.ItemId, body.CurrencyId) != 1)
                return Envelope.Error(400, "validation_failed",
                    "listType plus exactly one of itemId / currencyId is required");

            if (body.ItemId is { } itemId && !await db.Items.AnyAsync(i => i.Id == itemId))
                return Envelope.Error(400, "unknown_item", "Unknown item", "itemId");
            if (body.CurrencyId is { } currencyId && !await db.Currencies.AnyAsync(c => c.Id == currencyId))
                return Envelope.Error(400, "unknown_currency", "Unknown currency", "currencyId");

            var count = await db.UserItemLists.CountAsync(l => l.UserId == sub);
            if (count >= ItemListCap)
                return Envelope.Error(409, "item_lists_full", "Your have/want lists are full");

            var byItem = body.ItemId is not null;
            var dupe = await db.UserItemLists.AnyAsync(l =>
                l.UserId == sub && l.ListType == listType
                && (byItem ? l.ItemId == body.ItemId : l.CurrencyId == body.CurrencyId));
            if (dupe)
                return Envelope.Error(409, "already_listed", "Already on this list");

            var entry = new UserItemList
            {
                UserId = sub,
                ListType = listType,
                ItemId = body.ItemId,
                CurrencyId = body.CurrencyId,
            };
            db.UserItemLists.Add(entry);
            await db.SaveChangesAsync();
            return Results.Json(new { entry.Id, listType = body.ListType }, statusCode: 201);

            static int num_non_nulls(Guid? a, Guid? b) => (a is not null ? 1 : 0) + (b is not null ? 1 : 0);
        });

        g.MapDelete("/item-lists/{id:guid}", async (HttpContext ctx, Guid id,
            IIntrospector introspector) =>
        {
            if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
            var sub = Edge.Sub(ctx);
            var removed = await Db(ctx).UserItemLists
                .Where(l => l.Id == id && l.UserId == sub)
                .ExecuteDeleteAsync();
            return removed == 1
                ? Results.NoContent()
                : Envelope.Error(404, "not_found", "Unknown list entry");
        });

        return g;
    }
}
