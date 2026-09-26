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
import ru.yandex.practicum.analyzer.config.HubConsumerProperties;
import ru.yandex.practicum.kafka.telemetry.event.HubEventAvro;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class HubEventProcessor implements Runnable {

    private final Consumer<String, HubEventAvro> consumer;
    private final HubEventService service;
    private final HubConsumerProperties properties;
    private final AtomicBoolean started = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile boolean running = true;
    private volatile Thread worker;

    public HubEventProcessor(@Qualifier("hubEventConsumer") Consumer<String, HubEventAvro> consumer,
                             HubEventService service, HubConsumerProperties properties) {
        this.consumer = consumer;
        this.service = service;
        this.properties = properties;
    }

    @Override
    public void run() {
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
            log.info("Запущена обработка событий хабов из топика {}", properties.getTopic());
            while (running && !Thread.currentThread().isInterrupted()) {
                ConsumerRecords<String, HubEventAvro> records = consumer.poll(properties.getPollTimeout());
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
        } catch (WakeupException e) {
            if (running) {
                throw e;
            }
        } catch (InterruptException e) {
            interrupted = true;
            throw new IllegalStateException("Поток обработки событий хабов прерван", e);
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
                    log.warn("Обработчик событий хабов не завершился за {}", properties.getShutdownTimeout());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Ожидание остановки обработчика событий хабов прервано");
            }
        }
    }
}
