# The example provider's worker. Build from sdk/go:
#   docker build -f examples/ip-pool/Dockerfile -t ip-pool-provider .
FROM golang:1.24 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY provider ./provider
COPY examples/ip-pool ./examples/ip-pool
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/ip-pool ./examples/ip-pool

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/ip-pool /ip-pool
USER nonroot:nonroot
ENTRYPOINT ["/ip-pool"]
CMD ["run"]
