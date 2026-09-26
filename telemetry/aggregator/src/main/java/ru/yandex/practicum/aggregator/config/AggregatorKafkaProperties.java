package ru.yandex.practicum.aggregator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "aggregator.kafka")
public class AggregatorKafkaProperties {

    private String bootstrapServers;
    private String groupId;
    private Duration pollTimeout;
    private Topics topics;

    @Getter
    @Setter
    public static class Topics {

        private String sensors;
        private String snapshots;
    }
}