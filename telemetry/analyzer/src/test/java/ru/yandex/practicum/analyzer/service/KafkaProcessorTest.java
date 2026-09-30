package ru.yandex.practicum.analyzer.service;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import ru.yandex.practicum.analyzer.config.HubConsumerProperties;
import ru.yandex.practicum.analyzer.config.SnapshotConsumerProperties;
import ru.yandex.practicum.kafka.telemetry.event.HubEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.SensorsSnapshotAvro;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaProcessorTest {

    private static final String TOPIC = "test-events";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void processorsShareLifecycleImplementation() throws Exception {
        assertThat(HubEventProcessor.class.getMethod("stop").getDeclaringClass())
                .isSameAs(SnapshotProcessor.class.getMethod("stop").getDeclaringClass());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopBeforeStartClosesConsumerOnlyOnce(boolean snapshot) {
        Fixture fixture = fixture(snapshot);
        fixture.stop().run();
        fixture.start().run();
        fixture.stop().run();

        verify(fixture.consumer()).close(TIMEOUT);
        verify(fixture.consumer(), never()).poll(any(Duration.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyPollDoesNotCommitAndSecondStartDoesNotRestartConsumer(boolean snapshot) {
        Fixture fixture = fixture(snapshot);
        doAnswer(invocation -> {
            fixture.stop().run();
            return ConsumerRecords.empty();
        }).when(fixture.consumer()).poll(TIMEOUT);

        fixture.start().run();
        fixture.start().run();
        fixture.stop().run();

        if (snapshot) {
            verify(fixture.consumer()).subscribe(eq(List.of(TOPIC)), any(ConsumerRebalanceListener.class));
        } else {
            verify(fixture.consumer()).subscribe(List.of(TOPIC));
        }
        verify(fixture.consumer()).poll(TIMEOUT);
        verify(fixture.consumer()).close(TIMEOUT);
        verify(fixture.consumer(), never()).commitSync(anyMap());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unexpectedWakeupPropagatesAndClosesConsumer(boolean snapshot) {
        Fixture fixture = fixture(snapshot);
        when(fixture.consumer().poll(TIMEOUT)).thenThrow(new WakeupException());

        assertThatThrownBy(fixture.start()::run).isInstanceOf(WakeupException.class);
        verify(fixture.consumer()).close(TIMEOUT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptionRestoresFlagAndClosesConsumer(boolean snapshot) {
        Fixture fixture = fixture(snapshot);
        when(fixture.consumer().poll(TIMEOUT)).thenAnswer(invocation -> {
            throw new InterruptException(new InterruptedException());
        });

        assertThatThrownBy(fixture.start()::run)
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(InterruptException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(fixture.consumer()).close(TIMEOUT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void externalStopWakesWorkerAndClosesConsumerOnWorkerThread(boolean snapshot) throws Exception {
        Fixture fixture = fixture(snapshot);
        CountDownLatch polling = new CountDownLatch(1);
        CountDownLatch wakeup = new CountDownLatch(1);
        AtomicReference<Thread> closingThread = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        doAnswer(invocation -> {
            polling.countDown();
            if (!wakeup.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Не получен сигнал остановки");
            }
            throw new WakeupException();
        }).when(fixture.consumer()).poll(TIMEOUT);
        doAnswer(invocation -> {
            wakeup.countDown();
            return null;
        }).when(fixture.consumer()).wakeup();
        doAnswer(invocation -> {
            closingThread.set(Thread.currentThread());
            return null;
        }).when(fixture.consumer()).close(TIMEOUT);
        Thread worker = new Thread(fixture.start());
        worker.setUncaughtExceptionHandler((thread, error) -> failure.set(error));

        try {
            worker.start();
            assertThat(polling.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.stop().run();
            worker.join(5000);

            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(closingThread.get()).isSameAs(worker);
            verify(fixture.consumer()).close(TIMEOUT);
        } finally {
            fixture.stop().run();
            wakeup.countDown();
            worker.join(5000);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void springContextCallsInheritedPreDestroy(boolean snapshot) {
        Fixture fixture = fixture(snapshot);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("processor", Object.class, fixture::processor);
            context.refresh();
        }

        verify(fixture.consumer()).close(TIMEOUT);
    }

    @Test
    void hubEventsCommitAfterWholeBatch() {
        Consumer<String, HubEventAvro> consumer = mock();
        HubEventService service = mock();
        HubEventProcessor processor = new HubEventProcessor(consumer, service, hubProperties());
        HubEventAvro first = new HubEventAvro();
        HubEventAvro second = new HubEventAvro();
        ConsumerRecords<String, HubEventAvro> records = records(first, second);
        when(consumer.poll(TIMEOUT)).thenReturn(records).thenThrow(new WakeupException());

        assertThatThrownBy(processor::run).isInstanceOf(WakeupException.class);

        var order = inOrder(service, consumer);
        order.verify(service).handle(same(first));
        order.verify(service).handle(same(second));
        order.verify(consumer).commitSync(offset(2));
        verify(consumer, times(1)).commitSync(anyMap());
    }

    @Test
    void snapshotsCommitAfterEachRecord() {
        Consumer<String, SensorsSnapshotAvro> consumer = mock();
        SnapshotService service = mock();
        SnapshotProcessor processor = new SnapshotProcessor(consumer, service, snapshotProperties());
        SensorsSnapshotAvro first = new SensorsSnapshotAvro();
        SensorsSnapshotAvro second = new SensorsSnapshotAvro();
        when(consumer.poll(any(Duration.class)))
                .thenReturn(records(first, second), ConsumerRecords.empty()).thenThrow(new WakeupException());

        assertThatThrownBy(processor::start).isInstanceOf(WakeupException.class);

        var order = inOrder(service, consumer);
        order.verify(service).prepare(same(first));
        order.verify(consumer).commitSync(offset(1));
        order.verify(service).prepare(same(second));
        order.verify(consumer).commitSync(offset(2));
    }

    @Test
    void failedSnapshotIsNotCommitted() {
        Consumer<String, SensorsSnapshotAvro> consumer = mock();
        SnapshotService service = mock();
        SnapshotProcessor processor = new SnapshotProcessor(consumer, service, snapshotProperties());
        SensorsSnapshotAvro snapshot = new SensorsSnapshotAvro();
        when(consumer.poll(TIMEOUT)).thenReturn(records(snapshot));
        doThrow(new IllegalStateException("Ошибка обработки")).when(service).prepare(snapshot);

        assertThatThrownBy(processor::start).isInstanceOf(IllegalStateException.class);

        verify(consumer, never()).commitSync(anyMap());
        verify(consumer).close(TIMEOUT);
    }

    @Test
    void snapshotStopCommitsCurrentRecordEvenWhenWakeupInterruptsCommit() {
        Consumer<String, SensorsSnapshotAvro> consumer = mock();
        SnapshotService service = mock();
        SnapshotProcessor processor = new SnapshotProcessor(consumer, service, snapshotProperties());
        SensorsSnapshotAvro first = new SensorsSnapshotAvro();
        SensorsSnapshotAvro second = new SensorsSnapshotAvro();
        when(consumer.poll(TIMEOUT)).thenReturn(records(first, second));
        doAnswer(invocation -> {
            processor.stop();
            return List.of();
        }).when(service).prepare(same(first));
        doThrow(new WakeupException()).doNothing().when(consumer).commitSync(offset(1));

        processor.start();

        verify(consumer, times(2)).commitSync(offset(1));
        verify(consumer, never()).commitSync(offset(2));
        verify(service, times(1)).prepare(any());
        verify(consumer).close(TIMEOUT);
    }

    private Fixture fixture(boolean snapshot) {
        if (snapshot) {
            Consumer<String, SensorsSnapshotAvro> consumer = mock();
            SnapshotProcessor processor = new SnapshotProcessor(consumer, mock(), snapshotProperties());
            return new Fixture(consumer, processor::start, processor::stop, processor);
        }
        Consumer<String, HubEventAvro> consumer = mock();
        HubEventProcessor processor = new HubEventProcessor(consumer, mock(), hubProperties());
        return new Fixture(consumer, processor::run, processor::stop, processor);
    }

    private HubConsumerProperties hubProperties() {
        HubConsumerProperties properties = new HubConsumerProperties();
        properties.setTopic(TOPIC);
        properties.setPollTimeout(TIMEOUT);
        properties.setCloseTimeout(TIMEOUT);
        properties.setShutdownTimeout(TIMEOUT);
        return properties;
    }

    private SnapshotConsumerProperties snapshotProperties() {
        SnapshotConsumerProperties properties = new SnapshotConsumerProperties();
        properties.setTopic(TOPIC);
        properties.setPollTimeout(TIMEOUT);
        properties.setCloseTimeout(TIMEOUT);
        properties.setShutdownTimeout(TIMEOUT);
        return properties;
    }

    @SafeVarargs
    private <T> ConsumerRecords<String, T> records(T... values) {
        var records = new ArrayList<ConsumerRecord<String, T>>();
        for (int i = 0; i < values.length; i++) {
            records.add(new ConsumerRecord<>(TOPIC, 0, i, "hub-1", values[i]));
        }
        return new ConsumerRecords<>(Map.of(PARTITION, records));
    }

    private Map<TopicPartition, OffsetAndMetadata> offset(long value) {
        return Map.of(PARTITION, new OffsetAndMetadata(value));
    }

    private record Fixture(Consumer<String, ?> consumer, Runnable start, Runnable stop, Object processor) {
    }
}
