# contracts — the single source of truth for cross-service data shapes

Everything two services must agree on lives here: protobuf schemas for
internal gRPC calls and Kafka events, plus (as domains ship) OpenAPI specs
for public REST surfaces. **Contract-first**: the schema is written and
reviewed before either side implements against it.

## Layout

```
contracts/
├── generate.sh                    # regenerates committed Go stubs (needs protoc + plugins)
├── proto/
│   └── openmarket/
│       └── auth/v1/auth.proto     # AuthService: IntrospectToken (live), GetUser (reserved)
└── (OpenAPI specs land here as domains ship)
```

## How codegen works

| Consumer | Mechanism | Needs local tooling? |
|---|---|---|
| Gateway (Go) | `./generate.sh` → stubs into `services/gateway/internal/authpb` — **committed**, so CI never runs protoc | yes, once: protoc + protoc-gen-go + protoc-gen-go-grpc |
| Auth (Java) | `protobuf-maven-plugin` reads `contracts/proto` during `mvn compile` — generated into `target/`, never committed | no (plugin downloads protoc) |

Change flow: edit the `.proto` → run `./generate.sh` → commit the schema
*and* the regenerated stubs in the same PR → implement both sides. Schema
diffs are API diffs — review them like one.

## Rules

- Packages are versioned (`openmarket.auth.v1`) and directories must match
  (buf-lint convention).
- Additive changes only within a version: new optional fields, new RPCs.
  Breaking changes = a new version package, old one deprecated.
- See the protocol selection rule in
  [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) for *whether* a given
  interaction should be gRPC, a Kafka event, or plain REST — protobuf is the
  data language everywhere, but not every interaction should be a call.
