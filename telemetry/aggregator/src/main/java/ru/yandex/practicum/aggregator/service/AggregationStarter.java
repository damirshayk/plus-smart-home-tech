package ru.yandex.practicum.aggregator.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.aggregator.config.AggregatorKafkaProperties;
import ru.yandex.practicum.kafka.telemetry.event.SensorEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.SensorsSnapshotAvro;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

@Slf4j
@Component
@RequiredArgsConstructor
public class AggregationStarter {

    private final Consumer<String, SensorEventAvro> consumer;
    private final Producer<String, SpecificRecordBase> producer;
    private final SnapshotService snapshotService;
    private final AggregatorKafkaProperties kafkaProperties;

    private final Map<TopicPartition, OffsetAndMetadata> currentOffsets =
            new HashMap<>();

    public void start() {
        CountDownLatch stopped = new CountDownLatch(1);
        Thread shutdownHook = new Thread(() -> {
            consumer.wakeup();
            try {
                // JVM должна дождаться flush, фиксации смещений и закрытия клиентов.
                stopped.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "aggregator-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        boolean interrupted = false;
        try {
            consumer.subscribe(List.of(kafkaProperties.getTopics().getSensors()));
            log.info("Запущена агрегация событий из топика {}",
                    kafkaProperties.getTopics().getSensors());

            while (true) {
                ConsumerRecords<String, SensorEventAvro> records =
                        consumer.poll(kafkaProperties.getPollTimeout());
                for (ConsumerRecord<String, SensorEventAvro> record : records) {
                    handleRecord(record);
                }
                commitOffsets();
            }
        } catch (WakeupException e) {
            log.info("Получен сигнал завершения агрегации");
        } catch (InterruptedException | InterruptException e) {
            interrupted = true;
            // Сначала освобождаем ресурсы, затем восстанавливаем флаг прерывания.
            Thread.interrupted();
            throw new IllegalStateException("Поток агрегации прерван", e);
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка во время обработки событий от датчиков", e);
        } finally {
            try {
                closeClients();
            } finally {
                stopped.countDown();
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException e) {
                    // При остановке JVM удалять уже запущенный hook нельзя.
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void closeClients() {
        try {
            producer.flush();
            commitOffsets();
        } finally {
            try {
                log.info("Закрываем консьюмер");
                consumer.close();
            } finally {
                log.info("Закрываем продюсер");
                producer.close();
            }
        }
    }

    private void commitOffsets() {
        if (!currentOffsets.isEmpty()) {
            consumer.commitSync(currentOffsets);
            currentOffsets.clear();
        }
    }

    private void handleRecord(ConsumerRecord<String, SensorEventAvro> record)
            throws ExecutionException, InterruptedException {

        Optional<SensorsSnapshotAvro> updatedSnapshot =
                snapshotService.updateState(record.value());

        if (updatedSnapshot.isPresent()) {
            SensorsSnapshotAvro snapshot = updatedSnapshot.get();

            ProducerRecord<String, SpecificRecordBase> snapshotRecord =
                    new ProducerRecord<>(
                            kafkaProperties.getTopics().getSnapshots(),
                            null,
                            snapshot.getTimestamp().toEpochMilli(),
                            snapshot.getHubId(),
                            snapshot
                    );

            producer.send(snapshotRecord).get();
        }

        TopicPartition partition =
                new TopicPartition(record.topic(), record.partition());

        currentOffsets.put(
                partition,
                new OffsetAndMetadata(record.offset() + 1)
        );
    }
}
