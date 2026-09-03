namespace Catalogue.Domain;

public enum OrderType { Buy, Sell }
public enum PayingType { Each, Total }
public enum ListingStatus { Active, Sold, Paused, Expired, Cancelled }

// A market order: the author requests ONE thing (an item or an amount of a
// currency — XOR, enforced in the DB) and offers ≥1 lines in exchange.
// Acceptance is whole-lot: the first valid accept flips active→sold and
// freezes a jsonb snapshot; competing accepts get 409.
public class Listing
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
    public Guid AuthorId { get; set; }

    public Guid? RequestedItemId { get; set; }
    public Guid? RequestedCurrencyId { get; set; }
    public Item? RequestedItem { get; set; }
    public Currency? RequestedCurrency { get; set; }
    public int Amount { get; set; } = 1;

    public OrderType OrderType { get; set; }
    public PayingType PayingType { get; set; } = PayingType.Each;
    public ListingStatus Status { get; set; } = ListingStatus.Active;
    public DateTime? ExpiresAt { get; set; }
    public DateTime? CancelledAt { get; set; }
    public string IdempotencyKey { get; set; } = "";

    public List<ListingOfferedItem> OfferedItems { get; set; } = new();
    public List<ListingOfferedCurrency> OfferedCurrencies { get; set; } = new();
}

public class ListingOfferedItem
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public Guid ListingId { get; set; }
    public Guid ItemId { get; set; }
    public int Amount { get; set; } = 1;
    public Item Item { get; set; } = null!;
    public Listing Listing { get; set; } = null!;
}

public class ListingOfferedCurrency
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public Guid ListingId { get; set; }
    public Guid CurrencyId { get; set; }
    public int Amount { get; set; } = 1;
    public Currency Currency { get; set; } = null!;
    public Listing Listing { get; set; } = null!;
}

// A completed deal (honor-system ledger — settlement happens off-platform
// in-game). The snapshot is frozen server-side at accept time and is the
// source of truth afterwards; the listing keeps living but as a closed record.
public class Trade
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public Guid ListingId { get; set; }
    public Guid SellerId { get; set; }
    public Guid BuyerId { get; set; }
    public string Snapshot { get; set; } = ""; // jsonb, server-built
    public DateTime CompletedAt { get; set; } = DateTime.UtcNow;
    // The counterparty who accepted, for (accepter, idempotency-key) replay
    public Guid AcceptedById { get; set; }
    public string IdempotencyKey { get; set; } = "";
}
