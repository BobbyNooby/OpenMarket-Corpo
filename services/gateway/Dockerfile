FROM golang:1.25-alpine AS build
RUN apk add --no-cache curl
WORKDIR /src
COPY go.mod go.sum ./
RUN CGO_ENABLED=0 go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /app .

FROM alpine:3.20
RUN apk add --no-cache curl ca-certificates \
    && adduser -D -H -u 1500 gateway
COPY --from=build /app /app
USER gateway
EXPOSE 3000
ENTRYPOINT ["/app"]
