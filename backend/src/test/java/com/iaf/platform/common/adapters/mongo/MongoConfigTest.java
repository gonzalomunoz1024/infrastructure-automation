package com.iaf.platform.common.adapters.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;

class MongoConfigTest {

    private static MongoProperties props(String uri, String database) {
        MongoProperties p = new MongoProperties();
        p.setUri(uri);
        p.setDatabase(database);
        return p;
    }

    @Test
    void theDatabaseIsTheOneNamedThenTheUrisThenPlatform() {
        assertThat(MongoConfig.database(props("mongodb://db:27017/infra", "other"))).isEqualTo("other");
        assertThat(MongoConfig.database(props("mongodb://user:secret@db:27017/infra?replicaSet=rs0", null))).isEqualTo("infra");
        assertThat(MongoConfig.database(props("mongodb://db:27017", null))).isEqualTo("platform");
        assertThat(MongoConfig.database(props(null, null))).isEqualTo("platform");
        assertThat(MongoConfig.uri(props(null, null))).isEqualTo("mongodb://localhost:27017/platform");
    }
}
