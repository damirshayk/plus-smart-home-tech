package ru.yandex.practicum.analyzer.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.analyzer.config.HubConsumerProperties;
import ru.yandex.practicum.kafka.telemetry.event.HubEventAvro;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class HubEventProcessor extends AbstractKafkaProcessor<HubEventAvro> implements Runnable {

    private final HubEventService service;

    public HubEventProcessor(@Qualifier("hubEventConsumer") Consumer<String, HubEventAvro> consumer,
                             HubEventService service, HubConsumerProperties properties) {
        super(consumer, properties.getTopic(), properties.getPollTimeout(),
                properties.getCloseTimeout(), properties.getShutdownTimeout());
        this.service = service;
    }

    @Override
    public void run() {
        start();
    }

    @Override
    protected void processRecords(ConsumerRecords<String, HubEventAvro> records) {
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (ConsumerRecord<String, HubEventAvro> record : records) {
            try {
                service.handle(record.value());
            } catch (IllegalArgumentException e) {
                log.warn("Отклонено событие хаба: topic={}, partition={}, offset={}: {}",
                        record.topic(), record.partition(), record.offset(), e.getMessage());
            }
            offsets.put(new TopicPartition(record.topic(), record.partition()),
                    new OffsetAndMetadata(record.offset() + 1));
        }
        if (!offsets.isEmpty()) {
            consumer.commitSync(offsets);
        }
    }
}
