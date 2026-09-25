package ru.yandex.practicum.collector.kafka;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class KafkaEventSender {

    private final Producer<String, SpecificRecordBase> producer;

    public CompletableFuture<Void> send(
            String topic,
            String hubId,
            long timestampMillis,
            SpecificRecordBase event) {

        CompletableFuture<Void> result = new CompletableFuture<>();

        try {
            ProducerRecord<String, SpecificRecordBase> record =
                    new ProducerRecord<>(
                            topic,
                            null,
                            timestampMillis,
                            hubId,
                            event
                    );

            producer.send(record, (metadata, exception) -> {
                if (exception == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }

        return result;
    }

    @PreDestroy
    public void close() {
        try {
            producer.flush();
        } finally {
            producer.close();
        }
    }
}