package ru.yandex.practicum.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.config.server.native.search-locations="
                + "file:../config-repo,file:../config-repo/telemetry,file:../config-repo/commerce"
})
class ConfigServerTest {

    private final TestRestTemplate http;

    @Autowired
    ConfigServerTest(TestRestTemplate http) {
        this.http = http;
    }

    @ParameterizedTest
    @CsvSource({
            "collector, collector.kafka.topics.sensors, telemetry.sensors.v1",
            "aggregator, aggregator.kafka.topics.snapshots, telemetry.snapshots.v1",
            "analyzer, analyzer.kafka.hubs.topic, telemetry.hubs.v1",
            "product-service, spring.datasource.url, jdbc:postgresql://localhost:5432/product_db",
            "inventory-service, spring.datasource.url, jdbc:postgresql://localhost:5432/inventory_db",
            "order-service, spring.datasource.url, jdbc:postgresql://localhost:5432/order_db"
    })
    void shouldServeCommonAndServiceConfiguration(String application, String key, String value) {
        ResponseEntity<Environment> response = http.getForEntity(
                "/{application}/default", Environment.class, application);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Environment configuration = response.getBody();
        assertThat(configuration).isNotNull();
        assertThat(configuration.getName()).isEqualTo(application);
        assertThat(configuration.getProfiles()).containsExactly("default");
        assertThat(configuration.getPropertySources()).hasSize(2);
        assertThat(configuration.getPropertySources()).anySatisfy(source ->
                assertThat(source.getSource().get("kafka.bootstrap-servers")).isEqualTo("localhost:9092"));
        assertThat(configuration.getPropertySources()).anySatisfy(source ->
                assertThat(source.getSource().get("eureka.client.serviceUrl.defaultZone"))
                        .isEqualTo("http://localhost:8761/eureka/"));
        assertThat(configuration.getPropertySources()).anySatisfy(source ->
                assertThat(source.getSource().get(key)).isEqualTo(value));
    }

    @ParameterizedTest
    @CsvSource({"product-service", "inventory-service", "order-service"})
    void shouldServeCommercePersistenceSettings(String application) {
        ResponseEntity<Environment> response = http.getForEntity(
                "/{application}/default", Environment.class, application);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Environment configuration = response.getBody();
        assertThat(configuration).isNotNull();
        assertThat(configuration.getPropertySources()).anySatisfy(source -> {
            assertThat(source.getName()).contains("/commerce/" + application + ".yml");
            assertThat(source.getSource().get("server.port")).isEqualTo(0);
            assertThat(source.getSource().get("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(source.getSource().get("spring.jpa.open-in-view")).isEqualTo(false);
            assertThat(source.getSource().get("spring.sql.init.mode")).isEqualTo("always");
        });
    }

    @Test
    void shouldServeHubRouterDiscoveryAddress() {
        ResponseEntity<Environment> response = http.getForEntity("/analyzer/default", Environment.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Environment configuration = response.getBody();
        assertThat(configuration).isNotNull();
        assertThat(configuration.getPropertySources()).anySatisfy(source ->
                assertThat(source.getSource().get("grpc.client.hub-router.address"))
                        .isEqualTo("discovery:///hub-router"));
    }
}
