package ru.yandex.practicum.analyzer.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class AbstractKafkaProcessor<T> {

    protected final Consumer<String, T> consumer;
    private final String topic;
    private final Duration pollTimeout;
    private final Duration closeTimeout;
    private final Duration shutdownTimeout;
    private final AtomicBoolean started = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile boolean running = true;
    private volatile Thread worker;

    protected AbstractKafkaProcessor(Consumer<String, T> consumer, String topic, Duration pollTimeout,
                                     Duration closeTimeout, Duration shutdownTimeout) {
        this.consumer = consumer;
        this.topic = topic;
        this.pollTimeout = pollTimeout;
        this.closeTimeout = closeTimeout;
        this.shutdownTimeout = shutdownTimeout;
    }

    public final void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        worker = Thread.currentThread();
        boolean interrupted = false;
        try {
            if (!running) {
                return;
            }
            subscribe(topic);
            log.info("Запущена обработка сообщений из топика {}", topic);
            while (running && !Thread.currentThread().isInterrupted()) {
                processRecords(consumer.poll(getPollTimeout()));
            }
        } catch (WakeupException e) {
            if (running) {
                throw e;
            }
        } catch (InterruptException e) {
            interrupted = true;
            throw new IllegalStateException("Поток обработки топика " + topic + " прерван", e);
        } finally {
            interrupted |= Thread.interrupted();
            try {
                consumer.close(closeTimeout);
            } finally {
                stopped.countDown();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    protected abstract void processRecords(ConsumerRecords<String, T> records);

    protected void subscribe(String topic) {
        consumer.subscribe(List.of(topic));
    }

    protected Duration getPollTimeout() {
        return pollTimeout;
    }

    protected final boolean isRunning() {
        return running;
    }

    @PreDestroy
    public final void stop() {
        running = false;
        if (stopped.getCount() == 0) {
            return;
        }
        consumer.wakeup();
        if (started.compareAndSet(false, true)) {
            try {
                consumer.close(closeTimeout);
            } finally {
                stopped.countDown();
            }
        } else if (Thread.currentThread() != worker) {
            try {
                if (!stopped.await(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("Обработчик топика {} не завершился за {}", topic, shutdownTimeout);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Ожидание остановки обработчика топика {} прервано", topic);
            }
        }
    }
}
