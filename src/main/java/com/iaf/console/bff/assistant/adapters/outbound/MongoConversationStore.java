package com.iaf.console.bff.assistant.adapters.outbound;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.console.bff.assistant.domain.Conversation;
import com.iaf.console.bff.assistant.ports.outbound.ConversationStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.mongo.MongoIndexed;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import reactor.core.publisher.Mono;

/**
 * Conversations shared by every instance, in the {@code assistant_conversations} collection:
 * {@code _id} the conversation id, the record under {@code doc}, and beside it what the queries
 * read: {@code userId}, {@code accountId}, {@code region}, {@code version}, and {@code expiresAt}
 * as a date, whose TTL index drops a conversation when its time is up (reads check it too, as the
 * TTL monitor runs once a minute).
 */
@Component
@Profile("mongo")
public class MongoConversationStore implements ConversationStore, MongoIndexed {

    static final String COLLECTION = "assistant_conversations";

    private final Mongo mongo;
    private final Clock clock;

    public MongoConversationStore(Mongo mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    @Override
    public Mono<Void> ensureIndexes() {
        return mongo.indexes(COLLECTION, List.of(new IndexModel(Indexes.ascending("expiresAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))));
    }

    @Override
    public Mono<Conversation> find(AccountScope scope, String conversationId) {
        Bson filter = Filters.and(Filters.eq("_id", conversationId), mine(scope), Filters.gt("expiresAt", Mongo.date(clock.instant())));
        return Mono.from(mongo.collection(COLLECTION).find(filter).first()).map(this::read);
    }

    @Override
    public Mono<Conversation> insert(AccountScope scope, Conversation conversation) {
        return Mono.defer(() -> Mono.from(mongo.collection(COLLECTION).insertOne(document(scope, conversation)))).thenReturn(conversation);
    }

    @Override
    public Mono<Conversation> replace(AccountScope scope, Conversation conversation, long expectedVersion) {
        return Mono.defer(() -> {
            Bson current = Filters.and(Filters.eq("_id", conversation.conversationId()), mine(scope), Filters.eq("version", expectedVersion));
            return Mono.from(mongo.collection(COLLECTION).replaceOne(current, document(scope, conversation)))
                    .flatMap(r -> r.getMatchedCount() > 0 ? Mono.just(conversation) : Mono.empty());
        });
    }

    /** A conversation as it is stored, the scope's principal's in the scope's account and region. */
    Document document(AccountScope scope, Conversation conversation) {
        if (!conversation.of(scope.principal(), scope.accountId(), scope.region())) {
            throw new IllegalStateException("the conversation " + conversation.conversationId() + " is not the scope's");
        }
        return new Document("_id", conversation.conversationId())
                .append("userId", conversation.userId())
                .append("accountId", conversation.accountId())
                .append("region", conversation.region())
                .append("version", conversation.version())
                .append("expiresAt", Mongo.date(conversation.expiresAt()))
                .append(Mongo.DOC, mongo.doc(conversation));
    }

    Conversation read(Document d) {
        return mongo.read(d, Conversation.class);
    }

    private static Bson mine(AccountScope scope) {
        return Filters.and(Filters.eq("userId", scope.principal()), Filters.eq("accountId", scope.accountId()), Filters.eq("region", scope.region()));
    }
}
