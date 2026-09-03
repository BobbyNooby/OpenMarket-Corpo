FROM golang:1.23-alpine AS build
RUN apk add --no-cache curl
WORKDIR /src
# dep cache layer first: go.mod + go.sum alone survive source churn
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /app .

FROM alpine:3.20
# no mkdir for /app: the COPY below must land the BINARY at /app, and a
# pre-created directory would swallow it as /app/app (learned live)
RUN apk add --no-cache curl ca-certificates \
    && adduser -D -H -u 1500 appuser
COPY --from=build --chown=appuser /app /app
EXPOSE 8082
USER appuser
ENTRYPOINT ["/app"]
