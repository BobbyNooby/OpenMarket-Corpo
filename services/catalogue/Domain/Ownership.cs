namespace Catalogue.Domain;

public class WatchlistEntry
{
    public Guid UserId { get; set; }
    public Guid ListingId { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public Listing Listing { get; set; } = null!;
}

public enum ItemListType { Have, Want }

// Per-user have/want marker for one catalog entity (item XOR currency).
public class UserItemList
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public Guid UserId { get; set; }
    public ItemListType ListType { get; set; }
    public Guid? ItemId { get; set; }
    public Guid? CurrencyId { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}

// Outbox for listing.created/sold/expired/cancelled, catalog.retired, etc.
// Column shape mirrors auth's outbox_events 1:1 so a single relay design
// can serve both services later.
public class OutboxEvent
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public string AggregateType { get; set; } = "";
    public Guid AggregateId { get; set; }
    public string Topic { get; set; } = "";
    public string Payload { get; set; } = "{}"; // jsonb
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime? PublishedAt { get; set; }
}
