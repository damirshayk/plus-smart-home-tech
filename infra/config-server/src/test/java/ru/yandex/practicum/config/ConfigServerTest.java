package ru.yandex.practicum.config;

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
        "spring.cloud.config.server.native.search-locations=file:../config-repo,file:../config-repo/telemetry"
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
            "analyzer, analyzer.kafka.hubs.topic, telemetry.hubs.v1"
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
                assertThat(source.getSource().get(key)).isEqualTo(value));
    }
}
