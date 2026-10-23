package com.iaf.console.fake;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.iaf.console.fake.audit.adapters.AuditRoutes;
import com.iaf.console.fake.audit.application.Audit;
import com.iaf.console.fake.common.ErrorFilter;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Ids;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.content.adapters.ContentRoutes;
import com.iaf.console.fake.content.application.ContentStore;
import com.iaf.console.fake.demo.DemoSeed;
import com.iaf.console.fake.events.adapters.EventRoutes;
import com.iaf.console.fake.events.application.EventFeed;
import com.iaf.console.fake.events.domain.EventRing;
import com.iaf.console.fake.identity.adapters.IdentityFilter;
import com.iaf.console.fake.identity.adapters.IdentityRoutes;
import com.iaf.console.fake.identity.application.OnBehalfOfVerifier;
import com.iaf.console.fake.identity.application.ServiceAccounts;
import com.iaf.console.fake.identity.domain.DevUsers;
import com.iaf.console.fake.providers.adapters.DemoRoutes;
import com.iaf.console.fake.providers.domain.RecordedWorld;
import com.iaf.console.fake.quotas.adapters.QuotaRoutes;
import com.iaf.console.fake.quotas.application.Quotas;
import com.iaf.console.fake.registry.adapters.ObserveRoutes;
import com.iaf.console.fake.registry.adapters.ResourceRoutes;
import com.iaf.console.fake.registry.application.Observer;
import com.iaf.console.fake.registry.application.Registry;
import com.iaf.console.fake.runs.adapters.JobRoutes;
import com.iaf.console.fake.runs.application.Commands;
import com.iaf.console.fake.runs.application.Jobs;
import com.nimbusds.jose.jwk.JWKSet;

import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The fake platform: every slice wired together behind one router function, to embed in a Spring
 * application or to start on a port of its own. Built with {@link #builder()}.
 */
public final class PlatformFake implements AutoCloseable {

    /** The port the runnable application listens on unless told otherwise. */
    public static final int DEFAULT_PORT = 18090;

    /** What a fake is built from; every setting has a default fit for a demo. */
    public static final class Builder {

        private Clock clock = Clock.systemUTC();
        private Duration stepDelay = Duration.ofMillis(250);
        private String defaultUser = DevUsers.DEFAULT;
        private String issuer = "console-bff";
        private String audience = "platform";
        private JWKSet trustedKeys;
        private boolean generateDemoKey = true;
        private long seed = 20261023L;
        private int ringCapacity = EventRing.DEFAULT_CAPACITY;
        private boolean demo = true;
        private String contentBase = ContentStore.DEFAULT_BASE;

        private Builder() {
        }

        /** The clock every timestamp comes from. */
        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        /** How long a job waits per step, so a demo can watch it move; zero for tests. */
        public Builder stepDelay(Duration value) {
            this.stepDelay = value;
            return this;
        }

        /** The dev user a request without credentials runs as; null makes such requests 401. */
        public Builder defaultUser(String value) {
            this.defaultUser = value;
            return this;
        }

        /** The issuer an on-behalf-of assertion must name. */
        public Builder issuer(String value) {
            this.issuer = value;
            return this;
        }

        /** The audience an on-behalf-of assertion must name. */
        public Builder audience(String value) {
            this.audience = value;
            return this;
        }

        /** The public keys assertions are verified against, beside the generated demo key. */
        public Builder trustedKeys(JWKSet value) {
            this.trustedKeys = value;
            return this;
        }

        /** Whether to generate a signing key at start, for demos that sign their own assertions. */
        public Builder generateDemoKey(boolean value) {
            this.generateDemoKey = value;
            return this;
        }

        /** The seed of the demo's deterministic ids and tokens. */
        public Builder seed(long value) {
            this.seed = value;
            return this;
        }

        /** How many events the feed keeps. */
        public Builder ringCapacity(int value) {
            this.ringCapacity = value;
            return this;
        }

        /** Whether to seed the demo world: two console accounts and their resources. */
        public Builder demo(boolean value) {
            this.demo = value;
            return this;
        }

        /** The classpath base the content is loaded from. */
        public Builder contentBase(String value) {
            this.contentBase = value;
            return this;
        }

        public PlatformFake build() {
            return new PlatformFake(this);
        }
    }

    private final FakeClock clock;
    private final Ids ids;
    private final long seed;
    private final ContentStore content;
    private final ServiceAccounts serviceAccounts;
    private final OnBehalfOfVerifier verifier;
    private final Commands commands;
    private final EventFeed events;
    private final RecordedWorld world;
    private final Quotas quotas;
    private final Registry registry;
    private final Observer observer;
    private final Audit audit;
    private final Jobs jobs;
    private final RouterFunction<ServerResponse> routes;
    private final DemoSeed.Result demo;
    private DisposableServer server;

    private PlatformFake(Builder b) {
        this.clock = new FakeClock(b.clock);
        this.ids = new Ids(b.clock, new SecureRandom());
        this.seed = b.seed;
        this.content = ContentStore.load(b.contentBase);
        this.serviceAccounts = new ServiceAccounts(clock, ids);
        this.verifier = new OnBehalfOfVerifier(clock, b.issuer, b.audience, b.trustedKeys, b.generateDemoKey);
        this.commands = new Commands(clock);
        this.events = new EventFeed(clock, ids, b.ringCapacity);
        this.world = new RecordedWorld(1000);
        this.quotas = new Quotas(clock);
        this.registry = new Registry(clock, ids, content, events);
        this.observer = new Observer(clock, content, registry, world, quotas);
        this.audit = new Audit(clock, events);
        this.jobs = new Jobs(clock, ids, content, registry, events, world, quotas, audit, b.stepDelay);
        RouterFunction<ServerResponse> all = new IdentityRoutes(serviceAccounts, commands).routes()
                .and(new ContentRoutes(content).routes())
                .and(new ResourceRoutes(registry, content, commands, jobs::holds).routes())
                .and(new ObserveRoutes(observer, registry, content).routes())
                .and(new JobRoutes(jobs, content, commands, events).routes())
                .and(new EventRoutes(events, registry, jobs).routes())
                .and(new QuotaRoutes(quotas, commands).routes())
                .and(new AuditRoutes(audit).routes())
                .and(new DemoRoutes(world, commands).routes());
        this.routes = all.filter(new IdentityFilter(serviceAccounts, verifier, b.defaultUser)).filter(new ErrorFilter());
        this.demo = b.demo ? DemoSeed.seed(this) : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Every route of the fake, identity and error handling included; a Spring application registers it as a bean. */
    public RouterFunction<ServerResponse> routes() {
        return routes;
    }

    /** The routes as an HTTP handler with the fake's JSON codecs, for a server of one's own. */
    public HttpHandler httpHandler() {
        HandlerStrategies strategies = HandlerStrategies.builder().codecs(codecs -> {
            codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(Json.mapper()));
            codecs.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(Json.mapper()));
        }).build();
        return RouterFunctions.toHttpHandler(routes, strategies);
    }

    /** Starts the fake on a port (0 picks a free one) and answers the port it listens on. */
    public synchronized int start(int port) {
        if (server != null) {
            return server.port();
        }
        server = HttpServer.create().port(port).handle(new ReactorHttpHandlerAdapter(httpHandler())).bindNow();
        return server.port();
    }

    /** The port of a started fake, -1 when it was never started. */
    public synchronized int port() {
        return server == null ? -1 : server.port();
    }

    /** The base URL of a started fake. */
    public String baseUrl() {
        return "http://localhost:" + port();
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.disposeNow();
            server = null;
        }
        jobs.close();
    }

    public FakeClock clock() {
        return clock;
    }

    public Ids ids() {
        return ids;
    }

    /** The seed of the demo's deterministic ids and tokens. */
    public long seed() {
        return seed;
    }

    public ContentStore content() {
        return content;
    }

    public ServiceAccounts serviceAccounts() {
        return serviceAccounts;
    }

    public OnBehalfOfVerifier verifier() {
        return verifier;
    }

    public Commands commands() {
        return commands;
    }

    public EventFeed events() {
        return events;
    }

    public RecordedWorld world() {
        return world;
    }

    public Quotas quotas() {
        return quotas;
    }

    public Registry registry() {
        return registry;
    }

    public Observer observer() {
        return observer;
    }

    public Audit audit() {
        return audit;
    }

    public Jobs jobs() {
        return jobs;
    }

    /** What the demo seeded, null when the fake was built without the demo. */
    public DemoSeed.Result demo() {
        return demo;
    }
}
