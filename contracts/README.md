# contracts — the single source of truth for cross-service data shapes

Everything two services must agree on lives here: protobuf schemas for
internal gRPC calls and Kafka events, plus (as domains ship) OpenAPI specs
for public REST surfaces. **Contract-first**: the schema is written and
reviewed before either side implements against it.

## Layout

```
contracts/
├── generate.sh                    # regenerates committed Go + C# stubs (needs protoc + plugins)
├── proto/
│   └── openmarket/
│       └── auth/v1/auth.proto     # AuthService: IntrospectToken (live), GetUser (reserved)
└── (OpenAPI specs land here as domains ship)
```

## How codegen works

| Consumer | Mechanism | Needs local tooling? |
|---|---|---|
| Gateway (Go) | `./generate.sh` → stubs into `services/gateway/internal/authpb` — **committed**, so CI never runs protoc | yes, once: protoc + protoc-gen-go + protoc-gen-go-grpc |
| Catalogue (C#) | `./generate.sh` → stubs into `services/catalogue/AuthGrpc` (via the `csharp_namespace` option) — **committed**, same rationale | yes, once: protoc + Grpc.Tools plugins |
| Auth (Java) | `protobuf-maven-plugin` reads `contracts/proto` during `mvn compile` — generated into `target/`, never committed | no (plugin downloads protoc) |

Change flow: edit the `.proto` → run `./generate.sh` → commit the schema
*and* the regenerated stubs in the same PR → implement both sides. Schema
diffs are API diffs — review them like one.

## REST + event contracts

- `openapi/messaging.v1.yaml` — the messaging service's REST surface and
  its WebSocket push envelope (the shape every chat client codes against).
- `proto/openmarket/events/v1/user_events.proto` — auth's domain events
  (`user.banned`, `user.unbanned`, `user.roles_changed`, `user.deleted`),
  carried on the transactional outbox and relayed to Kafka. Wire format is
  proto3-shaped JSON until the Schema Registry lands (Phase 4); consumers
  must be idempotent — the relay is at-least-once.

Service claims live or die with these files: if a service publishes or
serves something that isn't a contract here, either add the contract or
remove the claim.

## Rules

- Packages are versioned (`openmarket.auth.v1`) and directories must match
  (buf-lint convention).
- Additive changes only within a version: new optional fields, new RPCs.
  Breaking changes = a new version package, old one deprecated.
- See the protocol selection rule in
  [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) for *whether* a given
  interaction should be gRPC, a Kafka event, or plain REST — protobuf is the
  data language everywhere, but not every interaction should be a call.
