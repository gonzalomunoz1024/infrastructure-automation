package com.iaf.console.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.sdk.client.ApiClient;
import com.iaf.console.sdk.client.ApiException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The BFF's API as one client. Every call carries the bearer token (renewed once after a 401
 * {@code invalid_token}, then sent again), the account and region when set, and on a write an
 * {@code Idempotency-Key}. A 429 or 503 of an idempotent call is retried up to 3 times, after
 * {@code Retry-After}. Calls go through the generated API classes, which all send through this
 * client; {@link #call} turns what they throw into a {@link ConsoleApiException}.
 *
 * <pre>{@code
 * ConsoleClient console = ConsoleClient.builder()
 *         .endpoint("https://console.example.com")
 *         .credentials(credentials)
 *         .build();
 * JobsApi jobs = console.api(JobsApi.class);
 * JobDetail job = console.call(() -> jobs.getJob("job-1"));
 * console.items(after -> jobs.listJobs(null, "failed", null, null, 200, after), ListJobs200Response::getItems)
 *         .forEach(j -> System.out.println(j.getJobId()));
 * }</pre>
 */
public final class ConsoleClient implements AutoCloseable {

    /**
     * Where a generated write asks for {@code X-Csrf-Token}: a bearer call needs none, and the
     * header is left out.
     */
    public static final String NO_CSRF = "";

    /** {@code console-sdk-java/<version>}, the {@code User-Agent} the approval page and the API sessions list show. */
    public static final String DEFAULT_USER_AGENT = "console-sdk-java/" + ContractInfo.SDK_VERSION;

    /** One generated API method that answers something: {@code () -> jobs.getJob(id)}. */
    @FunctionalInterface
    public interface ApiCall<T> {
        T call() throws ApiException;
    }

    /** One generated API method that answers nothing: {@code () -> jobs.cancelJob(NO_CSRF, id)}. */
    @FunctionalInterface
    public interface ApiRun {
        void run() throws ApiException;
    }

    /** One page of a list, given the cursor of the page before (null for the first). */
    @FunctionalInterface
    public interface PageCall<P> {
        P fetch(String after) throws ApiException;
    }

    private static final ClassValue<Method> NEXT = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                return type.getMethod("getNext");
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    };

    private final String endpoint;
    private final PolicyHttpClient http;
    private final ApiClient apiClient;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ConcurrentMap<Class<?>, Object> apis = new ConcurrentHashMap<>();

    private ConsoleClient(Builder b) {
        this.endpoint = trimEndpoint(Objects.requireNonNull(b.endpoint, "endpoint: the BFF's address"));
        this.mapper = ApiClient.createDefaultObjectMapper();
        this.clock = b.clock;
        Map<String, String> defaults = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (b.userAgent != null) defaults.put("User-Agent", b.userAgent);
        defaults.putAll(b.headers);
        if (b.account != null) defaults.put("X-Console-Account", b.account);
        if (b.region != null) defaults.put("X-Console-Region", b.region);
        boolean owned = b.http == null;
        String basePath = URI.create(endpoint).getRawPath();
        this.http = new PolicyHttpClient(owned ? defaultHttpClient() : b.http, owned, b.tokens, Map.copyOf(defaults),
                b.idempotencyKeys, b.retries, b.maxRetryDelay, b.sleeper, b.clock, basePath == null ? "" : basePath, mapper);
        this.apiClient = new ApiClient(PolicyHttpClient.builderOf(http), mapper, endpoint);
        if (b.readTimeout != null) apiClient.setReadTimeout(b.readTimeout);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A client of the BFF at {@code endpoint}, with these credentials. */
    public static ConsoleClient create(String endpoint, TokenProvider credentials) {
        return builder().endpoint(endpoint).credentials(credentials).build();
    }

    /** The BFF's address, without a trailing slash. */
    public String endpoint() {
        return endpoint;
    }

    /** The generated API client, configured for this one: the generated API classes take it. */
    public ApiClient apiClient() {
        return apiClient;
    }

    /** The HttpClient every call goes through, with the bearer token, idempotency keys and retries. */
    public HttpClient http() {
        return http;
    }

    /** One of the generated API classes, such as {@code JobsApi}, sending through this client. */
    public <A> A api(Class<A> type) {
        return type.cast(apis.computeIfAbsent(type, t -> {
            try {
                return t.getConstructor(ApiClient.class).newInstance(apiClient);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException(t.getName() + " is not one of the generated API classes (com.iaf.console.sdk.api)", e);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("could not make " + t.getName(), e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("could not make " + t.getName(), e);
            }
        }));
    }

    /**
     * Runs one generated API method and answers what it answers. A refusal throws a
     * {@link ConsoleApiException} (the BFF's error, problems and Denial); a connection failure an
     * {@link UncheckedIOException}; a required parameter left null an {@link IllegalArgumentException}.
     */
    public <T> T call(ApiCall<T> call) {
        PolicyHttpClient.forget();
        try {
            return call.call();
        } catch (ApiException e) {
            throw translate(e);
        } finally {
            PolicyHttpClient.forget();
        }
    }

    /** {@link #call} for a method that answers nothing. */
    public void run(ApiRun run) {
        call(() -> {
            run.run();
            return null;
        });
    }

    /** Every page of a list, following {@code next} until it is null; a page is a generated model with {@code getNext()}. */
    public <P> Stream<P> pages(PageCall<P> fetch) {
        return pages(fetch, ConsoleClient::nextOf);
    }

    /** Every page of a list, reading the next cursor with {@code next}. */
    public <P> Stream<P> pages(PageCall<P> fetch, Function<? super P, String> next) {
        Objects.requireNonNull(fetch, "fetch");
        Objects.requireNonNull(next, "next");
        Iterator<P> pages = new Iterator<>() {
            private final Set<String> seen = new HashSet<>();
            private String after;
            private String repeated;
            private boolean last;
            private P ahead;

            @Override
            public boolean hasNext() {
                if (ahead != null) return true;
                if (last) return false;
                if (repeated != null) throw new IllegalStateException("the list answered the cursor " + repeated + " twice");
                String cursor = after;
                P page = call(() -> fetch.fetch(cursor));
                if (page == null) {
                    last = true;
                    return false;
                }
                String n = next.apply(page);
                if (n == null || n.isEmpty()) last = true;
                else if (!seen.add(n)) repeated = n;
                else after = n;
                ahead = page;
                return true;
            }

            @Override
            public P next() {
                if (!hasNext()) throw new NoSuchElementException();
                P page = ahead;
                ahead = null;
                return page;
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(pages, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /** Every item of a list, page after page: {@code items(after -> jobs.listJobs(..., after), ListJobs200Response::getItems)}. */
    public <P, I> Stream<I> items(PageCall<P> fetch, Function<? super P, ? extends Collection<? extends I>> items) {
        Objects.requireNonNull(items, "items");
        return pages(fetch).<I>flatMap(page -> {
            Collection<? extends I> c = items.apply(page);
            if (c == null) return Stream.empty();
            return c.stream();
        });
    }

    /**
     * A call by method and path, for what the generated classes do not read (the chargeback CSV):
     * {@code request("GET", "/console/v1/cost/chargeback.csv", Map.of("month", "2026-09"), null, Map.of("Accept", "text/csv"))}.
     * The body, when not null, is written as JSON. A refusal throws a {@link ConsoleApiException}.
     */
    public ConsoleResponse request(String method, String path, Map<String, ?> query, Object body, Map<String, String> headers) {
        String m = method.toUpperCase(Locale.ROOT);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path, query)).header("Accept", "application/json");
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            try {
                publisher = HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("the body does not write as JSON: " + e.getOriginalMessage(), e);
            }
            b.header("Content-Type", "application/json");
        }
        if (headers != null) headers.forEach(b::setHeader);
        b.method(m, publisher);
        HttpRequest request = b.build();
        HttpResponse<String> res = exchange(request, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2 && res.statusCode() != 304) throw refusal(request, res.statusCode(), res.headers(), res.body());
        return new ConsoleResponse(res.statusCode(), res.headers(), res.body(), mapper);
    }

    public ConsoleResponse request(String method, String path, Map<String, ?> query, Object body) {
        return request(method, path, query, body, Map.of());
    }

    /** The server-sent events of {@code /console/v1/events} as they come (its query: {@code wfrn}, {@code types}). */
    public Stream<ServerSentEvent> events(Map<String, ?> query) {
        return events(query, Map.of());
    }

    /** The events, with headers such as {@code Last-Event-ID}; close the stream to stop. */
    public Stream<ServerSentEvent> events(Map<String, ?> query, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(Operation.byId("streamEvents").path, query))
                .header("Accept", "text/event-stream")
                .GET();
        if (headers != null) headers.forEach(b::setHeader);
        HttpRequest request = b.build();
        HttpResponse<Stream<String>> res = exchange(request, HttpResponse.BodyHandlers.ofLines());
        if (res.statusCode() / 100 != 2) {
            String text;
            try (Stream<String> lines = res.body()) {
                text = lines.collect(Collectors.joining("\n"));
            }
            throw refusal(request, res.statusCode(), res.headers(), text);
        }
        return ServerSentEvent.parse(res.body());
    }

    /** An address below the endpoint: {@code uri("/console/v1/jobs", Map.of("status", "failed"))}. */
    public URI uri(String path, Map<String, ?> query) {
        String p = path.startsWith("/") ? path : "/" + path;
        String q = Http.query(query);
        return URI.create(endpoint + p + (q.isEmpty() ? "" : (p.contains("?") ? "&" : "?") + q));
    }

    /** Closes the HttpClient this client made; one given to the builder stays open. */
    @Override
    public void close() {
        http.close();
    }

    // -----------------------------------------------------------------------------------------

    private RuntimeException translate(ApiException e) {
        if (e.getResponseHeaders() == null) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) return new UncheckedIOException(io.getMessage(), io);
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException("interrupted");
                interrupted.initCause(cause);
                return new UncheckedIOException(interrupted);
            }
            // the generated code checks a call's required parameters before it sends it
            return new IllegalArgumentException(e.getMessage(), e);
        }
        PolicyHttpClient.Exchange x = PolicyHttpClient.last();
        boolean known = x != null && x.headers() == e.getResponseHeaders();
        String operationId = known && x.operation() != null ? x.operation().operationId : ConsoleApiException.operationIdOf(e);
        return ConsoleApiException.of(e.getCode(), known ? x.method() : null, known ? x.uri() : null, operationId,
                e.getResponseHeaders(), e.getResponseBody(), e, mapper, clock.instant());
    }

    private ConsoleApiException refusal(HttpRequest request, int status, HttpHeaders headers, String body) {
        Operation op = http.operation(request);
        return ConsoleApiException.of(status, request.method(), request.uri(), op != null ? op.operationId : null,
                headers, body, null, mapper, clock.instant());
    }

    private <T> HttpResponse<T> exchange(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (IOException e) {
            throw new UncheckedIOException(request.method() + " " + request.uri() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("interrupted: " + request.method() + " " + request.uri());
            interrupted.initCause(e);
            throw new UncheckedIOException(interrupted);
        }
    }

    private static String nextOf(Object page) {
        Method next = NEXT.get(page.getClass());
        if (next == null) {
            throw new IllegalArgumentException(page.getClass().getSimpleName() + " has no getNext(): pass the cursor's getter to pages(fetch, next)");
        }
        try {
            Object cursor = next.invoke(page);
            return cursor == null ? null : cursor.toString();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(page.getClass().getSimpleName() + ".getNext() failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static String trimEndpoint(String endpoint) {
        String e = endpoint.trim();
        while (e.endsWith("/")) e = e.substring(0, e.length() - 1);
        URI uri;
        try {
            uri = URI.create(e);
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException("the endpoint is not an address: " + endpoint, bad);
        }
        boolean web = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
        if (!web || uri.getHost() == null) {
            throw new IllegalArgumentException("the endpoint must be an http or https address, such as https://console.example.com: " + endpoint);
        }
        return e;
    }

    static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** How a {@link ConsoleClient} calls the BFF. */
    public static final class Builder {
        private String endpoint;
        private TokenProvider tokens;
        private String account;
        private String region;
        private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private String userAgent = DEFAULT_USER_AGENT;
        private HttpClient http;
        private int retries = 3;
        private Duration maxRetryDelay = Duration.ofSeconds(60);
        private Supplier<String> idempotencyKeys = () -> UUID.randomUUID().toString();
        private Duration readTimeout;
        private Sleeper sleeper = Sleeper.system();
        private Clock clock = Clock.systemUTC();

        private Builder() {}

        /** The BFF's address, such as {@code https://console.example.com}; a path prefix is kept. */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** Where the bearer token comes from: {@link RefreshingCredentials}, or {@link TokenProvider#of}. */
        public Builder credentials(TokenProvider credentials) {
            this.tokens = credentials;
            return this;
        }

        /** A static access token, never renewed. */
        public Builder accessToken(String accessToken) {
            return credentials(TokenProvider.of(accessToken));
        }

        /** {@code X-Console-Account} on every call (else the API session's account). */
        public Builder account(String account) {
            this.account = account;
            return this;
        }

        /** {@code X-Console-Region} on every call (else the API session's region). */
        public Builder region(String region) {
            this.region = region;
            return this;
        }

        /** A header for every call; one a call sets itself wins. */
        public Builder header(String name, String value) {
            headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** The {@code User-Agent} ({@link #DEFAULT_USER_AGENT}); null sends Java's own. */
        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        /** The HttpClient to send with (one with a 10-second connect timeout by default); it stays the caller's to close. */
        public Builder httpClient(HttpClient http) {
            this.http = http;
            return this;
        }

        /** Retries of a 429 or 503, for idempotent calls only (3); 0 turns them off. */
        public Builder retries(int retries) {
            if (retries < 0) throw new IllegalArgumentException("retries must be 0 or more");
            this.retries = retries;
            return this;
        }

        /** The longest wait a retry honours; a longer {@code Retry-After} is not retried (60 seconds). */
        public Builder maxRetryDelay(Duration maxRetryDelay) {
            this.maxRetryDelay = Objects.requireNonNull(maxRetryDelay, "maxRetryDelay");
            return this;
        }

        /** Makes the {@code Idempotency-Key} of a write that has none (a random UUID). */
        public Builder idempotencyKeys(Supplier<String> idempotencyKeys) {
            this.idempotencyKeys = Objects.requireNonNull(idempotencyKeys, "idempotencyKeys");
            return this;
        }

        /** How long a generated call waits for its answer (no limit by default). */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public Builder sleeper(Sleeper sleeper) {
            this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
            return this;
        }

        /** The time, for a {@code Retry-After} that is a date. */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public ConsoleClient build() {
            return new ConsoleClient(this);
        }
    }
}
