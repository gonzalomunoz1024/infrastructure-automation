package com.iaf.platform.notifications.adapters.outbound;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.notifications.application.NotificationProperties;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.outbound.InboxStore;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

/**
 * The inbox of the {@code mongo} profile: one document per notification in
 * {@code inbox_notifications}, keyed by its id, its doc the {@link Notification}. What the JDBC
 * store kept in two child tables is embedded, and stays as small as the notification itself:
 * {@code audience}, each of its users as {@code user:<id>} and each of its teams as
 * {@code team:<name>} (indexed, so the inbox of a caller is one query), and {@code readers}, who
 * read it and when, each person once (the inbox lets only the people it is addressed to mark it
 * read). {@code seq} is the order of arrival, handed out by one counter shared by every replica
 * (the {@code inbox_notifications} document of {@code store_sequences}): the inbox lists the
 * newest first, and a save deletes the oldest beyond {@code platform.notifications.keep}, their
 * audience and readers with them. Kept across restarts.
 */
@Component
@Primary
@Profile("mongo")
public class MongoInboxStore implements InboxStore {

    static final String COLLECTION = "inbox_notifications";
    static final String SEQUENCES = "store_sequences";

    private final Mongo mongo;
    private final int keep;
    private final Clock clock;

    @Autowired
    public MongoInboxStore(Mongo mongo, NotificationProperties props, Clock clock) {
        this(mongo, props.getKeep(), clock);
    }

    public MongoInboxStore(Mongo mongo, int keep, Clock clock) {
        this.mongo = mongo;
        this.keep = Math.max(1, keep);
        this.clock = clock;
        mongo.index(COLLECTION, Indexes.descending("seq"));
        mongo.index(COLLECTION, Indexes.compoundIndex(Indexes.ascending("audience"), Indexes.descending("seq")));
        counterAboveTheNewest();
    }

    /** The first save of an id wins; a later one changes nothing. */
    @Override
    public void save(Notification n) {
        Objects.requireNonNull(n.id(), "a notification needs an id");
        Document d = new Document("_id", n.id())
                .append("seq", nextSeq())
                .append("audience", audience(n))
                .append(Mongo.DOC, mongo.doc(n));
        try {
            mongo.collection(COLLECTION).insertOne(d);
        } catch (MongoException e) {
            if (Mongo.duplicateKey(e)) {
                return;   // the number it took stays unused, as a sequence's would
            }
            throw e;
        }
        dropBeyondKeep();
    }

    @Override
    public Optional<Notification> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongo.collection(COLLECTION).find(Filters.eq("_id", id)).projection(Projections.include(Mongo.DOC)).first())
                .map(d -> mongo.read(d, Notification.class));
    }

    @Override
    public List<Notification> visibleTo(Identity who) {
        if (who == null) {
            return List.of();
        }
        List<String> members = new ArrayList<>();
        members.add("user:" + who.id());
        if (who.teams() != null) {
            who.teams().stream().filter(t -> t != null).forEach(t -> members.add("team:" + t));
        }
        List<Notification> visible = new ArrayList<>();
        mongo.collection(COLLECTION).find(Filters.in("audience", members)).sort(Sorts.descending("seq")).projection(Projections.include(Mongo.DOC))
                .forEach(d -> visible.add(mongo.read(d, Notification.class)));
        return visible;
    }

    /** Marks it read by {@code userId} in one write, once (reading it again keeps the first time); false when it is not kept. */
    @Override
    public boolean markRead(String id, String userId) {
        if (id == null || userId == null) {
            return false;
        }
        if (mongo.collection(COLLECTION).updateOne(Filters.and(Filters.eq("_id", id), Filters.ne("readers.user", userId)),
                Updates.push("readers", new Document("user", userId).append("at", Mongo.date(clock.instant())))).getMatchedCount() == 1) {
            return true;
        }
        return mongo.collection(COLLECTION).find(Filters.eq("_id", id)).projection(Projections.include("_id")).first() != null;
    }

    @Override
    public boolean readBy(String id, String userId) {
        if (id == null || userId == null) {
            return false;
        }
        return mongo.collection(COLLECTION).find(Filters.and(Filters.eq("_id", id), Filters.eq("readers.user", userId)))
                .projection(Projections.include("_id")).first() != null;
    }

    /** For tests: how many notifications are kept. */
    int size() {
        return (int) mongo.collection(COLLECTION).countDocuments();
    }

    /** Who a notification is addressed to, as the members an inbox looks for. */
    private static List<String> audience(Notification n) {
        List<String> members = new ArrayList<>();
        n.users().forEach(u -> members.add("user:" + u));
        n.teams().forEach(t -> members.add("team:" + t));
        return members;
    }

    /** The next place in the order of arrival. */
    private long nextSeq() {
        Document counter = mongo.collection(SEQUENCES).findOneAndUpdate(Filters.eq("_id", COLLECTION), Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return counter.get("seq", Number.class).longValue();
    }

    /**
     * The counter never behind the notifications kept, should it have been lost or restored from
     * an older backup than them: a new notification must not count as older than the inbox.
     */
    private void counterAboveTheNewest() {
        Document newest = mongo.collection(COLLECTION).find().sort(Sorts.descending("seq")).limit(1)
                .projection(Projections.fields(Projections.include("seq"), Projections.excludeId())).first();
        if (newest != null) {
            mongo.collection(SEQUENCES).updateOne(Filters.eq("_id", COLLECTION), Updates.max("seq", newest.get("seq", Number.class).longValue()),
                    new UpdateOptions().upsert(true));
        }
    }

    /** Deletes the notifications older than the newest {@code keep}, with their audience and readers. */
    private void dropBeyondKeep() {
        Document oldestKept = mongo.collection(COLLECTION).find().sort(Sorts.descending("seq")).skip(keep - 1).limit(1)
                .projection(Projections.fields(Projections.include("seq"), Projections.excludeId())).first();
        if (oldestKept != null) {
            mongo.collection(COLLECTION).deleteMany(Filters.lt("seq", oldestKept.get("seq", Number.class).longValue()));
        }
    }
}
