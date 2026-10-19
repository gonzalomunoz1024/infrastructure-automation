package com.iaf.platform.common.adapters.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;

import com.mongodb.ConnectionString;
import com.mongodb.MongoCredential;

class TestMongoTest {

    @Test
    void aSpringContextKeepsTheCredentialsAndAuthDatabaseOfTheUriAndUsesTheTestDatabase() {
        String uri = "mongodb://tester:p%40ss@db.example:27017/admin?replicaSet=rs0";
        Map<String, String> spring = TestMongo.springProperties(uri, "t_0123456789abcdef");
        MongoProperties properties = new MongoProperties();
        properties.setUri(spring.get("spring.data.mongodb.uri"));
        properties.setDatabase(spring.get("spring.data.mongodb.database"));

        assertThat(MongoConfig.database(properties)).isEqualTo("t_0123456789abcdef");
        ConnectionString connection = new ConnectionString(MongoConfig.uri(properties));
        MongoCredential credential = connection.getCredential();
        assertThat(credential.getUserName()).isEqualTo("tester");
        assertThat(credential.getPassword()).isEqualTo("p@ss".toCharArray());
        assertThat(credential.getSource()).as("the auth database stays the one the URI names").isEqualTo("admin");
        assertThat(connection.getRequiredReplicaSetName()).isEqualTo("rs0");
        assertThat(connection.getHosts()).containsExactly("db.example:27017");
    }

    @Test
    void aUserWithoutAPasswordIsKept() {
        String uri = "mongodb://CN=tester@db.example:27017/?authMechanism=MONGODB-X509&tls=true";
        MongoProperties properties = new MongoProperties();
        properties.setUri(TestMongo.springProperties(uri, "t_1").get("spring.data.mongodb.uri"));
        assertThat(new ConnectionString(MongoConfig.uri(properties)).getCredential().getUserName()).isEqualTo("CN=tester");
    }
}
