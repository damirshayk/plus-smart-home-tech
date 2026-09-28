package ru.yandex.practicum.analyzer.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.analyzer.config.SnapshotConsumerProperties;
import ru.yandex.practicum.kafka.telemetry.event.SensorsSnapshotAvro;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class SnapshotProcessor {

    private final Consumer<String, SensorsSnapshotAvro> consumer;
    private final SnapshotService service;
    private final SnapshotConsumerProperties properties;
    private final AtomicBoolean started = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile boolean running = true;
    private volatile Thread worker;

    public SnapshotProcessor(@Qualifier("snapshotConsumer") Consumer<String, SensorsSnapshotAvro> consumer,
                             SnapshotService service, SnapshotConsumerProperties properties) {
        this.consumer = consumer;
        this.service = service;
        this.properties = properties;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        worker = Thread.currentThread();
        boolean interrupted = false;
        try {
            if (!running) {
                return;
            }
            consumer.subscribe(List.of(properties.getTopic()));
            log.info("Запущена обработка снимков из топика {}", properties.getTopic());
            while (running && !Thread.currentThread().isInterrupted()) {
                ConsumerRecords<String, SensorsSnapshotAvro> records = consumer.poll(properties.getPollTimeout());
                for (ConsumerRecord<String, SensorsSnapshotAvro> record : records) {
                    if (!running) {
                        break;
                    }
                    try {
                        service.handle(record.value());
                    } catch (IllegalArgumentException e) {
                        log.warn("Отклонён снимок: topic={}, partition={}, offset={}: {}",
                                record.topic(), record.partition(), record.offset(), e.getMessage());
                    }
                    commit(record);
                }
            }
        } catch (WakeupException e) {
            if (running) {
                throw e;
            }
        } catch (InterruptException e) {
            interrupted = true;
            throw new IllegalStateException("Поток обработки снимков прерван", e);
        } finally {
            interrupted |= Thread.interrupted();
            try {
                consumer.close(properties.getCloseTimeout());
            } finally {
                stopped.countDown();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void commit(ConsumerRecord<String, SensorsSnapshotAvro> record) {
        Map<TopicPartition, OffsetAndMetadata> offsets = Map.of(
                new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset() + 1));
        try {
            consumer.commitSync(offsets);
        } catch (WakeupException e) {
            if (running) {
                throw e;
            }
            consumer.commitSync(offsets);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (stopped.getCount() == 0) {
            return;
        }
        consumer.wakeup();
        if (started.compareAndSet(false, true)) {
            try {
                consumer.close(properties.getCloseTimeout());
            } finally {
                stopped.countDown();
            }
        } else if (Thread.currentThread() != worker) {
            try {
                if (!stopped.await(properties.getShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("Обработчик снимков не завершился за {}", properties.getShutdownTimeout());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Ожидание остановки обработчика снимков прервано");
            }
        }
    }
}
