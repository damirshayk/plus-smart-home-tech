package ru.yandex.practicum.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

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
            "'commerce,product-service', spring.datasource.url, jdbc:postgresql://localhost:5432/product_db",
            "'commerce,inventory-service', spring.datasource.url, jdbc:postgresql://localhost:5432/inventory_db",
            "'commerce,order-service', spring.datasource.url, jdbc:postgresql://localhost:5432/order_db"
    })
    void shouldServeCommonAndServiceConfiguration(String application, String key, String value) {
        ResponseEntity<Environment> response = http.getForEntity(
                "/{application}/default", Environment.class, application);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Environment configuration = response.getBody();
        assertThat(configuration).isNotNull();
        assertThat(configuration.getName()).isEqualTo(application);
        assertThat(configuration.getProfiles()).containsExactly("default");
        assertThat(property(configuration, "kafka.bootstrap-servers")).isEqualTo("localhost:9092");
        assertThat(property(configuration, "eureka.client.serviceUrl.defaultZone"))
                .isEqualTo("http://localhost:8761/eureka/");
        assertThat(property(configuration, key)).isEqualTo(value);
    }

    @ParameterizedTest
    @CsvSource({"product-service", "inventory-service", "order-service"})
    void shouldServeCommercePersistenceSettings(String application) {
        ResponseEntity<Environment> response = http.getForEntity(
                "/commerce,{application}/default", Environment.class, application);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Environment configuration = response.getBody();
        assertThat(configuration).isNotNull();
        List<String> sourceNames = configuration.getPropertySources().stream().map(PropertySource::getName).toList();
        assertThat(sourceNames).anyMatch(name -> name.endsWith("/commerce/commerce.yml"));
        assertThat(sourceNames).anyMatch(name -> name.endsWith("/commerce/" + application + ".yml"));
        String commonSource = sourceNames.stream().filter(name -> name.endsWith("/commerce/commerce.yml"))
                .findFirst().orElseThrow();
        String serviceSource = sourceNames.stream().filter(name -> name.endsWith("/commerce/" + application + ".yml"))
                .findFirst().orElseThrow();
        assertThat(sourceNames.indexOf(serviceSource)).isLessThan(sourceNames.indexOf(commonSource));
        assertThat(property(configuration, "server.port")).isEqualTo(0);
        assertThat(property(configuration, "spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(property(configuration, "spring.jpa.open-in-view")).isEqualTo(false);
        assertThat(property(configuration, "spring.sql.init.mode")).isEqualTo("always");
        assertThat(property(configuration, "spring.datasource.username")).isNotNull();
        assertThat(property(configuration, "spring.datasource.password")).isNotNull();
        assertThat(configuration.getPropertySources().stream().filter(source -> source.getName().equals(serviceSource))
                .flatMap(source -> source.getSource().keySet().stream()).map(Object::toString).toList())
                .doesNotContain("spring.datasource.username", "spring.datasource.password",
                        "spring.jpa.hibernate.ddl-auto", "spring.jpa.open-in-view", "spring.sql.init.mode");
    }

    @ParameterizedTest
    @CsvSource({"collector", "aggregator", "analyzer"})
    void shouldKeepCommercePersistenceSettingsOutOfTelemetry(String application) {
        ResponseEntity<Environment> response = http.getForEntity(
                "/{application}/default", Environment.class, application);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Environment configuration = response.getBody();
        assertThat(configuration).isNotNull();
        assertThat(configuration.getPropertySources().stream().map(PropertySource::getName).toList())
                .noneMatch(name -> name.endsWith("/commerce/commerce.yml"));
        if (application.equals("analyzer")) {
            assertThat(property(configuration, "spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://localhost:5432/telemetry_analyzer");
        } else {
            for (String key : List.of("spring.datasource.url", "spring.datasource.username",
                    "spring.datasource.password", "spring.jpa.hibernate.ddl-auto",
                    "spring.jpa.open-in-view", "spring.sql.init.mode")) {
                assertThat(property(configuration, key) == null).as("Отсутствует настройка %s", key).isTrue();
            }
        }
    }

    @Test
    void shouldServeHubRouterDiscoveryAddress() {
        ResponseEntity<Environment> response = http.getForEntity("/analyzer/default", Environment.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Environment configuration = response.getBody();
        assertThat(configuration).isNotNull();
        assertThat(property(configuration, "grpc.client.hub-router.address")).isEqualTo("discovery:///hub-router");
    }

    private Object property(Environment configuration, String key) {
        for (PropertySource source : configuration.getPropertySources()) {
            if (source.getSource().containsKey(key)) {
                return source.getSource().get(key);
            }
        }
        return null;
    }
}
