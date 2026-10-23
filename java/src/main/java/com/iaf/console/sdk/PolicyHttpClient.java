package com.iaf.console.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * The HttpClient every generated API class sends through: it puts the bearer token on each
 * request, renews it once after a 401 {@code invalid_token} and sends the request again, gives a
 * write an {@code Idempotency-Key} (the same one on every attempt), and sends an idempotent
 * request again after a 429 or 503, up to {@code retries} times, after {@code Retry-After}.
 *
 * <p>A response it may act on (a 401, or a 429 or 503 it may retry) is read first and, when it
 * stands, replayed to the caller's body handler; every other response streams to it untouched.
 */
final class PolicyHttpClient extends HttpClient {
    static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    static final Set<String> IDEMPOTENT = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");
    private static final Pattern INVALID_TOKEN = Pattern.compile("(?i)\\berror\\s*=\\s*\"?invalid_token\"?");

    /** A response the generated code will throw for, so that {@link ConsoleClient#call} can name its request. */
    record Exchange(String method, URI uri, Operation operation, HttpHeaders headers) {}

    private static final ThreadLocal<Exchange> LAST = new ThreadLocal<>();

    private final HttpClient delegate;
    private final boolean ownsDelegate;
    private final TokenProvider tokens;
    private final Map<String, String> defaults;
    private final Supplier<String> idempotencyKeys;
    private final int retries;
    private final Duration maxRetryDelay;
    private final Sleeper sleeper;
    private final Clock clock;
    private final String basePath;
    private final ObjectMapper mapper;

    PolicyHttpClient(HttpClient delegate, boolean ownsDelegate, TokenProvider tokens, Map<String, String> defaults,
            Supplier<String> idempotencyKeys, int retries, Duration maxRetryDelay, Sleeper sleeper, Clock clock,
            String basePath, ObjectMapper mapper) {
        this.delegate = delegate;
        this.ownsDelegate = ownsDelegate;
        this.tokens = tokens;
        this.defaults = defaults;
        this.idempotencyKeys = idempotencyKeys;
        this.retries = retries;
        this.maxRetryDelay = maxRetryDelay;
        this.sleeper = sleeper;
        this.clock = clock;
        this.basePath = basePath;
        this.mapper = mapper;
    }

    static Exchange last() {
        return LAST.get();
    }

    static void forget() {
        LAST.remove();
    }

    /** The request's operation: its path below the endpoint's, matched against the contract. */
    Operation operation(HttpRequest request) {
        String raw = request.uri().getRawPath() == null ? "" : request.uri().getRawPath();
        String path = !basePath.isEmpty() && raw.startsWith(basePath) ? raw.substring(basePath.length()) : raw;
        return Operation.find(request.method(), path);
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        HttpRequest prepared = prepare(request);
        Operation operation = operation(prepared);
        // a write that takes an Idempotency-Key is answered once however often it is sent
        boolean idempotent = IDEMPOTENT.contains(prepared.method()) || (operation != null && operation.idempotencyKey);
        boolean refreshed = false;
        int retried = 0;
        for (;;) {
            String token = token();
            HttpRequest attempt = token == null
                    ? prepared
                    : HttpRequest.newBuilder(prepared, (name, value) -> !name.equalsIgnoreCase("Authorization"))
                            .header("Authorization", "Bearer " + token)
                            .build();
            boolean hold401 = token != null && !refreshed;
            boolean holdBusy = idempotent && retried < retries;
            HttpResponse<Object> res = delegate.send(attempt, info -> {
                int s = info.statusCode();
                if ((s == 401 && hold401) || ((s == 429 || s == 503) && holdBusy)) {
                    return HttpResponse.BodySubscribers.<byte[], Object>mapping(HttpResponse.BodySubscribers.ofByteArray(), Held::new);
                }
                @SuppressWarnings("unchecked")
                HttpResponse.BodySubscriber<Object> own = (HttpResponse.BodySubscriber<Object>) (HttpResponse.BodySubscriber<?>) handler.apply(info);
                return own;
            });

            if (res.body() instanceof Held held) {
                if (res.statusCode() == 401) {
                    if (isInvalidToken(res.headers(), held.bytes)) {
                        refreshed = true;
                        String next;
                        try {
                            next = tokens.refresh(token);
                        } catch (RuntimeException e) {
                            throw ConsoleApiException.of(401, attempt.method(), attempt.uri(),
                                    operation != null ? operation.operationId : null, res.headers(),
                                    new String(held.bytes, StandardCharsets.UTF_8), e, mapper, clock.instant());
                        }
                        if (next != null) continue;
                    }
                } else {
                    Duration wait = retryDelay(res.headers(), retried);
                    if (wait != null) {
                        retried++;
                        sleeper.sleep(wait);
                        continue;
                    }
                }
                return remember(attempt, operation, replay(res, held.bytes, handler));
            }
            @SuppressWarnings("unchecked")
            HttpResponse<T> own = (HttpResponse<T>) (HttpResponse<?>) res;
            return remember(attempt, operation, own);
        }
    }

    /** The client's headers where the request has none; an Idempotency-Key on a write; no empty CSRF token. */
    private HttpRequest prepare(HttpRequest request) {
        HttpHeaders own = request.headers();
        // a bearer call needs no CSRF token: ConsoleClient.NO_CSRF leaves the header out
        HttpRequest.Builder b = HttpRequest.newBuilder(request,
                (name, value) -> !(name.equalsIgnoreCase("X-Csrf-Token") && value.isBlank()));
        defaults.forEach((name, value) -> {
            if (own.firstValue(name).isEmpty()) b.setHeader(name, value);
        });
        if (WRITES.contains(request.method()) && own.firstValue("Idempotency-Key").filter(v -> !v.isBlank()).isEmpty()) {
            b.setHeader("Idempotency-Key", idempotencyKeys.get());
        }
        return b.build();
    }

    private String token() throws IOException {
        if (tokens == null) return null;
        try {
            return tokens.token();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private boolean isInvalidToken(HttpHeaders headers, byte[] body) {
        List<String> challenges = headers.allValues("WWW-Authenticate");
        if (!challenges.isEmpty()) return challenges.stream().anyMatch(c -> INVALID_TOKEN.matcher(c).find());
        if (body.length == 0) return false;
        try {
            JsonNode node = mapper.readTree(body);
            return node != null && node.path("error").isTextual() && "invalid_token".equals(node.get("error").asText());
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code Retry-After}, else 1, 2, 4 seconds; null when the wait is longer than the most the client honours. */
    private Duration retryDelay(HttpHeaders headers, int retried) {
        Duration after = Http.retryAfter(headers.firstValue("Retry-After").orElse(null), clock.instant());
        Duration wait = after != null ? after : Duration.ofMillis(Math.min(1000L << Math.min(retried, 30), maxRetryDelay.toMillis()));
        return wait.compareTo(maxRetryDelay) <= 0 ? wait : null;
    }

    private static <T> HttpResponse<T> remember(HttpRequest attempt, Operation operation, HttpResponse<T> res) {
        if (res.statusCode() / 100 != 2) LAST.set(new Exchange(attempt.method(), attempt.uri(), operation, res.headers()));
        return res;
    }

    /** A body read before deciding what to do with its response. */
    private record Held(byte[] bytes) {}

    /** Hands a body already read to the caller's handler, as the connection would have. */
    private static <T> HttpResponse<T> replay(HttpResponse<?> res, byte[] bytes, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        HttpResponse.ResponseInfo info = new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return res.statusCode();
            }

            @Override
            public HttpHeaders headers() {
                return res.headers();
            }

            @Override
            public HttpClient.Version version() {
                return res.version();
            }
        };
        HttpResponse.BodySubscriber<T> subscriber = handler.apply(info);
        subscriber.onSubscribe(new Flow.Subscription() {
            private boolean done;

            @Override
            public synchronized void request(long n) {
                if (done) return;
                done = true;
                if (n <= 0) {
                    subscriber.onError(new IllegalArgumentException("a subscriber must request at least one item"));
                    return;
                }
                subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
                subscriber.onComplete();
            }

            @Override
            public synchronized void cancel() {
                done = true;
            }
        });
        try {
            return new Replayed<>(res, subscriber.getBody().toCompletableFuture().get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while reading the response");
        } catch (ExecutionException e) {
            throw new IOException("the response body could not be read", e.getCause());
        }
    }

    private record Replayed<T>(HttpResponse<?> original, T body) implements HttpResponse<T> {
        @Override
        public int statusCode() {
            return original.statusCode();
        }

        @Override
        public HttpRequest request() {
            return original.request();
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return original.headers();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return original.sslSession();
        }

        @Override
        public URI uri() {
            return original.uri();
        }

        @Override
        public HttpClient.Version version() {
            return original.version();
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        CompletableFuture<HttpResponse<T>> result = new CompletableFuture<>();
        Thread.ofVirtual().name("console-sdk-call").start(() -> {
            try {
                result.complete(send(request, handler));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return sendAsync(request, handler); // the BFF pushes nothing
    }

    /** What the generated ApiClient asks for: a builder that answers this client. */
    static HttpClient.Builder builderOf(PolicyHttpClient client) {
        return new HttpClient.Builder() {
            private HttpClient.Builder refuse() {
                throw new UnsupportedOperationException("configure the HttpClient given to ConsoleClient.builder().httpClient(...)");
            }

            @Override
            public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
                return refuse();
            }

            @Override
            public HttpClient.Builder connectTimeout(Duration duration) {
                return refuse();
            }

            @Override
            public HttpClient.Builder sslContext(SSLContext sslContext) {
                return refuse();
            }

            @Override
            public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
                return refuse();
            }

            @Override
            public HttpClient.Builder executor(Executor executor) {
                return refuse();
            }

            @Override
            public HttpClient.Builder followRedirects(Redirect policy) {
                return refuse();
            }

            @Override
            public HttpClient.Builder version(HttpClient.Version version) {
                return refuse();
            }

            @Override
            public HttpClient.Builder priority(int priority) {
                return refuse();
            }

            @Override
            public HttpClient.Builder proxy(ProxySelector proxySelector) {
                return refuse();
            }

            @Override
            public HttpClient.Builder authenticator(Authenticator authenticator) {
                return refuse();
            }

            @Override
            public HttpClient build() {
                return client;
            }
        };
    }

    // The rest is the delegate's.

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public HttpClient.Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
        return delegate.newWebSocketBuilder();
    }

    // A client given to ConsoleClient stays its owner's to close.

    @Override
    public void shutdown() {
        if (ownsDelegate) delegate.shutdown();
    }

    @Override
    public void shutdownNow() {
        if (ownsDelegate) delegate.shutdownNow();
    }

    @Override
    public boolean awaitTermination(Duration duration) throws InterruptedException {
        return delegate.awaitTermination(duration);
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public void close() {
        if (ownsDelegate) delegate.close();
    }
}
