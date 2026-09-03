using System.Text.Json;
using System.Text.Json.Serialization;
using Catalogue.Auth;
using Catalogue.Domain;
using Catalogue.Infrastructure;
using Microsoft.EntityFrameworkCore;

namespace Catalogue.Endpoints;

public static class ListingEndpoints
{
    public record OfferedLine(string Kind, Guid Id, int Amount);

    public record CreateListingRequest(Guid? RequestedItemId, Guid? RequestedCurrencyId, int Amount,
        string OrderType, string PayingType, DateTime? ExpiresAt, List<OfferedLine> Offered);

    // full-replace contract (v1's PUT semantics, kept on PATCH for route
    // stability): the body states the ENTIRE desired listing — absent fields
    // mean "cleared", so switching the requested item↔currency kind is an
    // ordinary update rather than an XOR-violating merge
    public record PatchListingRequest(int Amount, DateTime? ExpiresAt,
        Guid? RequestedItemId, Guid? RequestedCurrencyId, List<OfferedLine> Offered);

    private const int MaxOfferLines = 20;

    public static IEndpointConventionBuilder MapListings(this IEndpointRouteBuilder app,
        IIntrospector intro, ILogger logger)
    {
        var g = app.MapGroup("/api/v1/catalogue/listings").WithTags("listings");

        // ── public browse / search ──────────────────────────────
        g.MapGet("/", async (HttpContext ctx, CatalogueDbContext db,
            string? q, string? category, string? orderType, string? status,
            Guid? requestedItemId, Guid? requestedCurrencyId,
            Guid? offeredItemId, Guid? offeredCurrencyId, Guid? authorId,
            string? sort, int? limit, int? offset) =>
        {
            if (status is not null && !IsValidStatus(status))
                return Envelope.Error(400, "validation_failed", "Unknown status", "status");
            if (orderType is not null && orderType is not ("buy" or "sell"))
                return Envelope.Error(400, "validation_failed", "Unknown orderType", "orderType");
            if (sort is not null && sort is not ("newest" or "ending_soon"))
                return Envelope.Error(400, "validation_failed", "Unknown sort", "sort");
            var (safeLimit, safeOffset) = (Math.Clamp(limit ?? 20, 1, 50), Math.Clamp(offset ?? 0, 0, 10_000));
            var query = db.Listings.AsNoTracking()
                .Include(l => l.RequestedItem).Include(l => l.RequestedCurrency)
                .Include(l => l.OfferedItems).ThenInclude(o => o.Item)
                .Include(l => l.OfferedCurrencies).ThenInclude(o => o.Currency)
                .AsQueryable();

            // default: active only — pass an explicit status to see other states
            var statusFilter = status is null
                ? ListingStatus.Active
                : (ListingStatus)Enum.Parse(typeof(ListingStatus), status, ignoreCase: true);
            query = query.Where(l => l.Status == statusFilter);

            if (orderType is not null)
            {
                var ot = (OrderType)Enum.Parse(typeof(OrderType), orderType, ignoreCase: true);
                query = query.Where(l => l.OrderType == ot);
            }
            if (category is not null)
                query = query.Where(l => l.RequestedItem != null && l.RequestedItem.Category!.Slug == category);
            if (requestedItemId is not null) query = query.Where(l => l.RequestedItemId == requestedItemId);
            if (requestedCurrencyId is not null) query = query.Where(l => l.RequestedCurrencyId == requestedCurrencyId);
            if (offeredItemId is not null) query = query.Where(l => l.OfferedItems.Any(o => o.ItemId == offeredItemId));
            if (offeredCurrencyId is not null) query = query.Where(l => l.OfferedCurrencies.Any(o => o.CurrencyId == offeredCurrencyId));
            if (authorId is not null) query = query.Where(l => l.AuthorId == authorId);
            if (q is { } term)
            {
                var escaped = "%" + term.Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_") + "%";
                query = query.Where(l =>
                    EF.Functions.ILike(l.RequestedItem!.Name, escaped) ||
                    EF.Functions.ILike(l.RequestedItem!.Description ?? "", escaped) ||
                    l.OfferedItems.Any(o => EF.Functions.ILike(o.Item.Name, escaped)));
            }

            query = sort == "ending_soon"
                ? query.OrderBy(l => l.ExpiresAt ?? DateTime.MaxValue).ThenByDescending(l => l.CreatedAt)
                : query.OrderByDescending(l => l.CreatedAt).ThenByDescending(l => l.Id);

            var total = await query.CountAsync();
            var page = await query.Skip(safeOffset).Take(safeLimit).ToListAsync();
            return Results.Json(new { total, items = page.Select(Shape).ToList() });
        })
        .AllowAnonymous();

        g.MapGet("/{id:guid}", async (HttpContext ctx, Guid id, CatalogueDbContext db) =>
        {
            var listing = await db.Listings.AsNoTracking()
                .Include(l => l.RequestedItem).Include(l => l.RequestedCurrency)
                .Include(l => l.OfferedItems).ThenInclude(o => o.Item)
                .Include(l => l.OfferedCurrencies).ThenInclude(o => o.Currency)
                .FirstOrDefaultAsync(l => l.Id == id);
            return listing is null
                ? Envelope.Error(404, "not_found", "Unknown listing")
                : Results.Json(Shape(listing));
        })
        .AllowAnonymous();

        // ── create (authenticated; owner from the token, never the body) ──
        g.MapPost("/", async (HttpContext ctx, CatalogueDbContext db, IIntrospector introspector) =>
        {
            if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
            var authorId = Edge.Sub(ctx);

            var body = await ctx.Request.ReadFromJsonAsync<CreateListingRequest>();
            if (body is null)
                return Envelope.Error(400, "validation_failed", "Body required");

            var idemKey = ctx.Request.Headers["Idempotency-Key"].ToString();
            if (idemKey.Length > 100)
                return Envelope.Error(400, "validation_failed", "Idempotency-Key too long", "Idempotency-Key");

            // per-author cap: listings are the cheap raw material of the outbox
            // — uncapped, one account loops create/PATCH and floods readiness
            const int MaxListingsPerAuthor = 200;
            if (await db.Listings.CountAsync(l => l.AuthorId == authorId) >= MaxListingsPerAuthor)
                return Envelope.Error(409, "listing_cap_reached",
                    $"At most {MaxListingsPerAuthor} active-or-historical listings per author");

            // validate BEFORE the replay check: garbage enums answer 400, and a
            // genuine replay passes validation unchanged
            if (await ValidateAsync(db, body.Amount, body.OrderType, body.PayingType, body.ExpiresAt, body.Offered,
                    body.RequestedItemId, body.RequestedCurrencyId) is { } verr) return verr;

            if (idemKey.Length > 0)
            {
                var existing = await db.Listings.AsNoTracking()
                    .Include(l => l.OfferedItems).Include(l => l.OfferedCurrencies)
                    .FirstOrDefaultAsync(l => l.AuthorId == authorId && l.IdempotencyKey == idemKey);
                if (existing is not null)
                {
                    // same key must mean same request — a mismatch is a client
                    // bug worth surfacing, not a silent second outcome
                    var sameBody = existing.Amount == body.Amount
                        && existing.RequestedItemId == body.RequestedItemId
                        && existing.RequestedCurrencyId == body.RequestedCurrencyId
                        && SameExpiry(existing.ExpiresAt, body.ExpiresAt)
                        && existing.OrderType == (OrderType)System.Enum.Parse(typeof(OrderType), body.OrderType, ignoreCase: true)
                        && existing.PayingType == (PayingType)System.Enum.Parse(typeof(PayingType), body.PayingType, ignoreCase: true)
                        && SameLines(existing, body.Offered);
                    if (!sameBody)
                        return Envelope.Error(409, "idempotency_key_reused",
                            "This Idempotency-Key was used for a different request");
                    return Results.Json(new { replay = true, listingId = existing.Id }, statusCode: 200);
                }
            }

            var listing = new Listing
            {
                AuthorId = authorId,
                RequestedItemId = body.RequestedItemId,
                RequestedCurrencyId = body.RequestedCurrencyId,
                Amount = body.Amount,
                OrderType = (OrderType)System.Enum.Parse(typeof(OrderType), body.OrderType, ignoreCase: true),
                PayingType = (PayingType)System.Enum.Parse(typeof(PayingType), body.PayingType, ignoreCase: true),
                ExpiresAt = body.ExpiresAt,
                IdempotencyKey = idemKey,
            };
            foreach (var line in body.Offered) AddOfferedLine(db, listing, line);
            db.Listings.Add(listing);
            db.Outbox.Add(Outbox(listing.Id, "listing.created", new
            {
                listingId = listing.Id,
                authorId,
                orderType = body.OrderType,
            }));
            await db.SaveChangesAsync(ct(ctx));

            return Results.Json(new { listingId = listing.Id }, statusCode: 201);
        });

        // ── owner mutations ─────────────────────────────────────
        g.MapPatch("/{id:guid}", async (HttpContext ctx, Guid id, CatalogueDbContext db,
            IIntrospector introspector) =>
        {
            if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
            var sub = Edge.Sub(ctx);
            var body = await ctx.Request.ReadFromJsonAsync<PatchListingRequest>();
            if (body is null) return Envelope.Error(400, "validation_failed", "Body required");

            var listing = await db.Listings
                .Include(l => l.RequestedItem).Include(l => l.RequestedCurrency)
                .Include(l => l.OfferedItems).ThenInclude(o => o.Item)
                .Include(l => l.OfferedCurrencies).ThenInclude(o => o.Currency)
                .FirstOrDefaultAsync(l => l.Id == id && l.AuthorId == sub);
            if (listing is null) return Envelope.Error(404, "not_found", "Unknown listing");
            if (listing.Status is not (ListingStatus.Active or ListingStatus.Paused))
                return Envelope.Error(409, "listing_not_editable", $"A {listing.Status.ToString().ToLowerInvariant()} listing cannot be edited");

            if (await ValidateAsync(db, body.Amount, listing.OrderType.ToString(), listing.PayingType.ToString(),
                    body.ExpiresAt, body.Offered, body.RequestedItemId, body.RequestedCurrencyId) is { } verr) return verr;

            listing.Amount = body.Amount;
            listing.ExpiresAt = body.ExpiresAt;
            listing.RequestedItemId = body.RequestedItemId;
            listing.RequestedCurrencyId = body.RequestedCurrencyId;
            db.OfferedItems.RemoveRange(listing.OfferedItems);
            db.OfferedCurrencies.RemoveRange(listing.OfferedCurrencies);
            listing.OfferedItems = new List<ListingOfferedItem>();
            listing.OfferedCurrencies = new List<ListingOfferedCurrency>();
            foreach (var line in body.Offered) AddOfferedLine(db, listing, line);
            listing.UpdatedAt = DateTime.UtcNow;
            db.Outbox.Add(Outbox(listing.Id, "listing.updated", new { listingId = listing.Id, authorId = sub }));
            try
            {
                await db.SaveChangesAsync();
            }
            catch (DbUpdateConcurrencyException)
            {
                // the listing changed under us (accept sold it, the sweep
                // expired it) — the stale editor gets a conflict, never a
                // silent rewrite of a listing whose snapshot already froze
                return Envelope.Error(409, "listing_conflict",
                    "The listing changed while you were editing it — reload and retry");
            }
            return Results.Json(new { listing.Id, listing.UpdatedAt });
        });

        g.MapPost("/{id:guid}/pause", async (HttpContext ctx, Guid id, CatalogueDbContext db,
            IIntrospector introspector) =>
            await Transition(ctx, db, introspector, logger, id, ListingStatus.Active, ListingStatus.Paused, null));

        g.MapPost("/{id:guid}/resume", async (HttpContext ctx, Guid id, CatalogueDbContext db,
            IIntrospector introspector) =>
        {
            if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
            var sub = Edge.Sub(ctx);
            var listing = await db.Listings.FirstOrDefaultAsync(l => l.Id == id && l.AuthorId == sub);
            if (listing is null) return Envelope.Error(404, "not_found", "Unknown listing");
            // resume past the deadline = it simply expires again — surface that
            // as 410 rather than flipping to active for one doomed request
            if (listing.Status == ListingStatus.Paused
                && listing.ExpiresAt is { } exp && exp <= DateTime.UtcNow)
                return Envelope.Error(410, "listing_expired", "This listing has already expired");
            var updated = await db.Listings
                .Where(l => l.Id == id && l.Status == ListingStatus.Paused)
                .ExecuteUpdateAsync(s => s
                    .SetProperty(l => l.Status, ListingStatus.Active)
                    .SetProperty(l => l.UpdatedAt, DateTime.UtcNow));
            return updated == 1 ? Results.Ok(new { listing.Id, status = "active" })
                : Envelope.Error(409, "conflict", "Listing is not paused");
        });

        g.MapPost("/{id:guid}/cancel", async (HttpContext ctx, Guid id, CatalogueDbContext db,
            IIntrospector introspector) =>
        {
            if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
            var sub = Edge.Sub(ctx);
            var updated = await db.Listings
                .Where(l => l.Id == id && l.AuthorId == sub
                    && (l.Status == ListingStatus.Active || l.Status == ListingStatus.Paused))
                .ExecuteUpdateAsync(s => s
                    .SetProperty(l => l.Status, ListingStatus.Cancelled)
                    .SetProperty(l => l.CancelledAt, DateTime.UtcNow)
                    .SetProperty(l => l.UpdatedAt, DateTime.UtcNow));
            if (updated == 0) return Envelope.Error(409, "conflict", "Listing cannot be cancelled from its current state");
            db.Outbox.Add(Outbox(id, "listing.cancelled", new { listingId = id, authorId = sub }));
            await db.SaveChangesAsync();
            return Results.Ok(new { id, status = "cancelled" });
        });

        // ── accept: the whole-lot deal, first valid committer wins ──
        g.MapPost("/{id:guid}/accept", async (HttpContext ctx, Guid id, CatalogueDbContext db,
            IIntrospector introspector) =>
        {
            if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
            var accepterId = Edge.Sub(ctx);

            var idemKey = ctx.Request.Headers["Idempotency-Key"].ToString();
            if (idemKey.Length > 100)
                return Envelope.Error(400, "validation_failed", "Idempotency-Key too long", "Idempotency-Key");
            // deterministic server-side key when the client omits one: a keyless
            // retry then replays instead of colliding with the empty string on
            // the (accepter, key) unique index
            var effectiveKey = idemKey.Length > 0 ? idemKey : $"accept:{accepterId:N}:{id:N}";
            {
                var existing = await db.Trades.AsNoTracking()
                    .FirstOrDefaultAsync(t => t.AcceptedById == accepterId && t.IdempotencyKey == effectiveKey);
                if (existing is not null)
                {
                    // the key must belong to THIS listing — replaying listing A's
                    // trade against listing B is a client bug, not a success
                    if (existing.ListingId != id)
                        return Envelope.Error(409, "idempotency_key_reused",
                            "This Idempotency-Key was used for a different listing");
                    return Results.Json(new { replay = true, tradeId = existing.Id }, statusCode: 200);
                }
            }

            var listing = await db.Listings
                .Include(l => l.RequestedItem).Include(l => l.RequestedCurrency)
                .Include(l => l.OfferedItems).ThenInclude(o => o.Item)
                .Include(l => l.OfferedCurrencies).ThenInclude(o => o.Currency)
                .FirstOrDefaultAsync(l => l.Id == id);
            if (listing is null) return Envelope.Error(404, "not_found", "Unknown listing");
            if (listing.AuthorId == accepterId)
                return Envelope.Error(409, "self_accept", "You cannot accept your own listing");
            if (listing.Status == ListingStatus.Expired)
                return Envelope.Error(410, "listing_expired", "This listing has expired");
            // lapsed but not yet swept: the status column still says Active —
            // do not sell a dead listing just because the scanner is a minute away
            if (listing.ExpiresAt is { } lapse && lapse <= DateTime.UtcNow)
                return Envelope.Error(410, "listing_expired", "This listing has expired");

            await using var tx = await db.Database.BeginTransactionAsync();
            // the atomic gate: exactly one accepter flips active→sold; everyone
            // else races the rowcount and gets 409/410 below. The expiry
            // predicate closes the check-vs-update race with the sweep.
            var won = await db.Listings
                .Where(l => l.Id == id && l.Status == ListingStatus.Active
                    && (l.ExpiresAt == null || l.ExpiresAt > DateTime.UtcNow))
                .ExecuteUpdateAsync(s => s
                    .SetProperty(l => l.Status, ListingStatus.Sold)
                    .SetProperty(l => l.UpdatedAt, DateTime.UtcNow));
            if (won == 0)
            {
                await db.Entry(listing).ReloadAsync();
                return listing.Status == ListingStatus.Expired
                       || (listing.ExpiresAt is { } lost && lost <= DateTime.UtcNow)
                    ? Envelope.Error(410, "listing_expired", "This listing has expired")
                    : Envelope.Error(409, "listing_sold", "This listing is no longer available");
            }

            var (sellerId, buyerId) = listing.OrderType == OrderType.Sell
                ? (listing.AuthorId, accepterId)
                : (accepterId, listing.AuthorId);

            var snapshot = JsonSerializer.Serialize(new
            {
                listing.Id,
                listing.OrderType,
                listing.PayingType,
                listing.Amount,
                listing.ExpiresAt,
                requested = listing.RequestedItemId is not null
                    ? new { kind = "item", id = listing.RequestedItemId, name = listing.RequestedItem?.Name }
                    : new { kind = "currency", id = listing.RequestedCurrencyId, name = listing.RequestedCurrency?.Name },
                offeredItems = listing.OfferedItems.Select(o => new { item = o.Item.Name, o.Amount }),
                offeredCurrencies = listing.OfferedCurrencies.Select(o => new { currency = o.Currency.Name, o.Amount }),
                sellerId,
                buyerId,
                acceptedById = accepterId,
                completedAt = DateTime.UtcNow,
            }, OutboxJson); // camelCase, matching the outbox + API surface

            var trade = new Trade
            {
                ListingId = id,
                SellerId = sellerId,
                BuyerId = buyerId,
                Snapshot = snapshot,
                AcceptedById = accepterId,
                IdempotencyKey = effectiveKey,
            };
            db.Trades.Add(trade);
            db.Outbox.Add(Outbox(id, "listing.sold", new
            {
                listingId = id,
                sellerId,
                buyerId,
                tradeId = trade.Id,
            }));
            await db.SaveChangesAsync();
            await tx.CommitAsync();

            return Results.Json(new { tradeId = trade.Id, listingId = id, sellerId, buyerId }, statusCode: 201);
        });

        // ── trades (participant-only reads) ─────────────────────
        g.MapGet("/trades/{id:guid}", async (HttpContext ctx, Guid id, CatalogueDbContext db,
            IIntrospector introspector) =>
        {
            if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
            var sub = Edge.Sub(ctx);
            var trade = await db.Trades.AsNoTracking().FirstOrDefaultAsync(t => t.Id == id);
            if (trade is null || (trade.SellerId != sub && trade.BuyerId != sub))
                return Envelope.Error(404, "not_found", "Unknown trade");
            return Results.Json(new
            {
                trade.Id,
                trade.ListingId,
                trade.SellerId,
                trade.BuyerId,
                trade.CompletedAt,
                snapshot = JsonSerializer.Deserialize<JsonElement>(trade.Snapshot),
            });
        });

        g.MapGet("/me/trades", async (HttpContext ctx, CatalogueDbContext db,
            IIntrospector introspector) =>
        {
            if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
            var sub = Edge.Sub(ctx);
            var trades = await db.Trades.AsNoTracking()
                .Where(t => t.SellerId == sub || t.BuyerId == sub)
                .OrderByDescending(t => t.CompletedAt)
                .Select(t => new { t.Id, t.ListingId, t.SellerId, t.BuyerId, t.CompletedAt })
                .ToListAsync();
            return Results.Json(new { trades });
        });

        g.MapGet("/me/listings", async (HttpContext ctx, CatalogueDbContext db,
            IIntrospector introspector) =>
        {
            if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
            var sub = Edge.Sub(ctx);
            var listings = await db.Listings.AsNoTracking()
                .Where(l => l.AuthorId == sub)
                .OrderByDescending(l => l.CreatedAt)
                .Select(l => new { l.Id, l.Status, l.OrderType, l.Amount, l.CreatedAt, l.ExpiresAt })
                .ToListAsync();
            return Results.Json(new { listings });
        });

        return g;
    }

    private static async Task<IResult> Transition(HttpContext ctx, CatalogueDbContext db,
        IIntrospector introspector, ILogger logger, Guid id,
        ListingStatus from, ListingStatus to, string? eventName)
    {
        if (await Edge.RequireLiveAsync(ctx, introspector, logger) is { } fail) return fail;
        var sub = Edge.Sub(ctx);
        var updated = await db.Listings
            .Where(l => l.Id == id && l.AuthorId == sub && l.Status == from)
            .ExecuteUpdateAsync(s => s
                .SetProperty(l => l.Status, to)
                .SetProperty(l => l.UpdatedAt, DateTime.UtcNow));
        if (updated == 0)
            return Envelope.Error(409, "conflict", $"Listing is not {from.ToString().ToLowerInvariant()}");
        if (eventName is not null)
        {
            db.Outbox.Add(Outbox(id, eventName, new { listingId = id, authorId = sub }));
            await db.SaveChangesAsync();
        }
        return Results.Ok(new { id, status = to.ToString().ToLowerInvariant() });
    }

    private static async Task<IResult?> ValidateAsync(CatalogueDbContext db, int amount, string orderType,
        string payingType, DateTime? expiresAt, List<OfferedLine> offered,
        Guid? requestedItemId, Guid? requestedCurrencyId)
    {
        // case-insensitive: PATCH handlers pass enum.ToString() ("Sell"),
        // JSON bodies carry the lowercase wire form — both must validate
        var order = orderType.ToLowerInvariant();
        var paying = payingType.ToLowerInvariant();
        if (order is not ("buy" or "sell"))
            return Envelope.Error(400, "validation_failed", "orderType must be buy or sell", "orderType");
        if (paying is not ("each" or "total"))
            return Envelope.Error(400, "validation_failed", "payingType must be each or total", "payingType");
        if (amount < 1 || amount > 1_000_000_000)
            return Envelope.Error(400, "validation_failed", "amount must be between 1 and 1,000,000,000", "amount");
        if (num_non_nulls(requestedItemId, requestedCurrencyId) != 1)
            return Envelope.Error(400, "validation_failed",
                "A listing requests exactly one of: item or currency", "requestedItemId");
        if (expiresAt is { } exp)
        {
            // no timezone offset binds as Unspecified — Npgsql rejects it on
            // write with a 500; make it an honest 400 instead
            if (exp.Kind == DateTimeKind.Unspecified)
                return Envelope.Error(400, "validation_failed",
                    "expiresAt must include a timezone offset", "expiresAt");
            if (exp <= DateTime.UtcNow)
                return Envelope.Error(400, "validation_failed", "expiresAt must be in the future", "expiresAt");
            // per request, never a static: a process-lifetime horizon silently
            // shrinks with uptime until every dated create is rejected
            if (exp > DateTime.UtcNow.AddDays(30))
                return Envelope.Error(400, "validation_failed", "expiresAt may be at most 30 days out", "expiresAt");
        }

        var lines = offered;
        if (lines.Count == 0)
            return Envelope.Error(400, "validation_failed", "At least one offered line is required", "offered");
        if (lines.Count > MaxOfferLines)
            return Envelope.Error(400, "validation_failed", $"At most {MaxOfferLines} offered lines", "offered");
        if (lines.Any(l => l.Amount < 1))
            return Envelope.Error(400, "validation_failed", "Offered amounts must be at least 1", "offered");
        if (lines.Select(l => (l.Kind, l.Id)).Distinct().Count() != lines.Count)
            return Envelope.Error(400, "validation_failed", "Duplicate offered lines", "offered");

        foreach (var line in lines)
        {
            if (line.Kind is not ("item" or "currency"))
                return Envelope.Error(400, "validation_failed", "Offered kind must be item or currency", "offered");
            var ok = line.Kind == "item"
                ? await db.Items.AnyAsync(i => i.Id == line.Id && i.RetiredAt == null)
                : await db.Currencies.AnyAsync(c => c.Id == line.Id && c.RetiredAt == null);
            if (!ok)
                return Envelope.Error(400, "unknown_" + line.Kind, $"Unknown or retired {line.Kind}", "offered");
        }

        if (requestedItemId is not null)
        {
            var item = await db.Items.FindAsync(requestedItemId);
            if (item is null || item.RetiredAt is not null)
                return Envelope.Error(400, "unknown_item", "Unknown or retired item", "requestedItemId");
        }
        if (requestedCurrencyId is not null)
        {
            var currency = await db.Currencies.FindAsync(requestedCurrencyId);
            if (currency is null || currency.RetiredAt is not null)
                return Envelope.Error(400, "unknown_currency", "Unknown or retired currency", "requestedCurrencyId");
        }
        return null;

        static int num_non_nulls(Guid? a, Guid? b) => (a is not null ? 1 : 0) + (b is not null ? 1 : 0);
    }

    private static void AddOfferedLine(CatalogueDbContext db, Listing listing, OfferedLine line)
    {
        // explicit DbSet.Add, never navigation-add: these entities carry
        // client/generated v7 keys, and graph discovery from a *tracked
        // modified* parent would mark them Unchanged→UPDATE (0 rows, 500).
        // DbSet.Add states Added unconditionally; create's fresh-parent path
        // stays correct too.
        if (line.Kind == "item")
        {
            var e = new ListingOfferedItem { ItemId = line.Id, Amount = line.Amount };
            db.OfferedItems.Add(e);
            listing.OfferedItems.Add(e);
        }
        else
        {
            var e = new ListingOfferedCurrency { CurrencyId = line.Id, Amount = line.Amount };
            db.OfferedCurrencies.Add(e);
            listing.OfferedCurrencies.Add(e);
        }
    }

    private static readonly JsonSerializerOptions OutboxJson = new(JsonSerializerDefaults.Web);

    private static OutboxEvent Outbox(Guid aggregateId, string topic, object payload) =>
        new()
        {
            AggregateType = "listing",
            AggregateId = aggregateId,
            Topic = topic,
            Payload = JsonSerializer.Serialize(payload, OutboxJson),
        };

    private static object Shape(Listing l) => new
    {
        l.Id,
        l.CreatedAt,
        l.UpdatedAt,
        l.AuthorId,
        orderType = l.OrderType.ToString().ToLowerInvariant(),
        payingType = l.PayingType.ToString().ToLowerInvariant(),
        status = l.Status.ToString().ToLowerInvariant(),
        l.Amount,
        l.ExpiresAt,
        requested = l.RequestedItemId is not null
            ? new { kind = "item", id = l.RequestedItemId, slug = l.RequestedItem?.Slug, name = l.RequestedItem?.Name }
            : l.RequestedCurrencyId is not null
                ? new { kind = "currency", id = l.RequestedCurrencyId, slug = l.RequestedCurrency?.Slug, name = l.RequestedCurrency?.Name }
                : null,
        offered = l.OfferedItems.Select(o => (object)new
            {
                kind = "item",
                id = o.ItemId,
                slug = o.Item.Slug,
                name = o.Item.Name,
                o.Amount
            })
            .Concat(l.OfferedCurrencies.Select(o => (object)new
            {
                kind = "currency",
                id = o.CurrencyId,
                slug = o.Currency.Slug,
                name = o.Currency.Name,
                o.Amount
            })),
        retired = l.RequestedItem?.RetiredAt is not null
            || l.OfferedItems.Any(o => o.Item.RetiredAt is not null)
            || l.OfferedCurrencies.Any(o => o.Currency.RetiredAt is not null),
    };

    private static CancellationToken ct(HttpContext ctx) => ctx.RequestAborted;

    private static bool SameLines(Listing listing, IReadOnlyList<OfferedLine> offered)
    {
        var existing = listing.OfferedItems.Select(o => ("item", o.ItemId, o.Amount))
            .Concat(listing.OfferedCurrencies.Select(o => ("currency", o.CurrencyId, o.Amount)))
            .OrderBy(x => x.Item1).ThenBy(x => x.Item2).ToList();
        var incoming = offered.Select(l => (l.Kind, l.Id, l.Amount))
            .OrderBy(x => x.Item1).ThenBy(x => x.Item2).ToList();
        return existing.Count == incoming.Count &&
               existing.Zip(incoming).All(pair =>
                   pair.First.Item1 == pair.Second.Kind && pair.First.Item2 == pair.Second.Id &&
                   pair.First.Item3 == pair.Second.Amount);
    }

    private static bool SameExpiry(DateTime? stored, DateTime? incoming) =>
        stored is null && incoming is null
        || stored is { } s && incoming is { } i
            && Math.Abs((s - i).Ticks) <= TimeSpan.TicksPerMillisecond;
    // timestamptz rounds to microseconds; a genuine retry re-sends the same
    // literal, so only sub-millisecond drift may be tolerated

    private static bool IsValidStatus(string s) => s.ToLowerInvariant() is
        "active" or "sold" or "paused" or "expired" or "cancelled";
}
