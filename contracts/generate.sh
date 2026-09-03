#!/usr/bin/env bash
# Regenerates code from the .proto contracts. Requires: protoc, protoc-gen-go,
# protoc-gen-go-grpc (brew install protobuf; go install .../protoc-gen-go@latest
# and .../protoc-gen-go-grpc@latest). Generated .pb.go files are committed —
# CI never needs the toolchain.
#
# Java stubs are generated separately by the auth service's Maven build
# (protobuf-maven-plugin), so `mvn test` needs no local toolchain.
set -euo pipefail
cd "$(dirname "$0")"

protoc \
  --go_out=../services/gateway \
  --go_opt=module=github.com/openmarket-corpo/gateway \
  --go-grpc_out=../services/gateway \
  --go-grpc_opt=module=github.com/openmarket-corpo/gateway \
  proto/openmarket/auth/v1/auth.proto

# C# stubs for the catalogue service (committed alongside the Go ones).
# protoc-gen-grpc-csharp ships inside the Grpc.Tools nuget package:
#   ~/.nuget/packages/grpc.tools/<ver>/tools/<platform>/grpc_csharp_plugin
GRPC_CSHARP_PLUGIN="${GRPC_CSHARP_PLUGIN:-$HOME/.nuget/packages/grpc.tools/2.72.0/tools/macosx_x64/grpc_csharp_plugin}"
protoc \
  --csharp_out=../services/catalogue/AuthGrpc \
  --grpc_out=../services/catalogue/AuthGrpc \
  --plugin=protoc-gen-grpc="$GRPC_CSHARP_PLUGIN" \
  proto/openmarket/auth/v1/auth.proto

echo "generated:"
find ../services/gateway/internal/authpb ../services/catalogue/AuthGrpc -type f
