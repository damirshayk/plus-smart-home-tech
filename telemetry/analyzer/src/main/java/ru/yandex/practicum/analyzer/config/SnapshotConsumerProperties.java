package ru.yandex.practicum.analyzer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "analyzer.kafka.snapshots")
public class SnapshotConsumerProperties {

    private String bootstrapServers;
    private String groupId;
    private String topic;
    private String autoOffsetReset;
    private int maxPollRecords;
    private Duration pollTimeout;
    private Duration closeTimeout;
    private Duration shutdownTimeout = Duration.ofSeconds(30);
    private Duration retryDelay = Duration.ofSeconds(1);
}
