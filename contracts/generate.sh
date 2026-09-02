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

echo "generated:"
find ../services/gateway/internal/authpb -type f
