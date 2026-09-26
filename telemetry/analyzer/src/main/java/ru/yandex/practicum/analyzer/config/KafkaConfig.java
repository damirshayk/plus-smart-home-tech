package ru.yandex.practicum.analyzer.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.yandex.practicum.kafka.deserializer.HubEventDeserializer;
import ru.yandex.practicum.kafka.telemetry.event.HubEventAvro;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Bean(destroyMethod = "")
    public Consumer<String, HubEventAvro> hubEventConsumer(HubConsumerProperties config) {
        if (config.getTopic() == null || config.getTopic().isBlank()) {
            throw new IllegalArgumentException("Топик событий хабов должен быть указан");
        }
        requirePositive(config.getPollTimeout(), "poll-timeout");
        requirePositive(config.getCloseTimeout(), "close-timeout");
        requirePositive(config.getShutdownTimeout(), "shutdown-timeout");

        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, config.getGroupId());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, HubEventDeserializer.class);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.getAutoOffsetReset());
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, config.getMaxPollRecords());
        return new KafkaConsumer<>(properties);
    }

    private void requirePositive(Duration duration, String property) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Настройка " + property + " должна быть положительной");
        }
    }
}
