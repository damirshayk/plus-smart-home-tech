package ru.yandex.practicum.analyzer.service;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.yandex.practicum.analyzer.config.SnapshotConsumerProperties;
import ru.yandex.practicum.analyzer.model.Action;
import ru.yandex.practicum.analyzer.model.ActionType;
import ru.yandex.practicum.analyzer.model.Scenario;
import ru.yandex.practicum.analyzer.model.ScenarioAction;
import ru.yandex.practicum.analyzer.model.Sensor;
import ru.yandex.practicum.analyzer.repository.ScenarioRepository;
import ru.yandex.practicum.grpc.telemetry.event.DeviceActionRequest;
import ru.yandex.practicum.kafka.telemetry.event.SensorsSnapshotAvro;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(10)
class SnapshotProcessingTest {

    private static final String TOPIC = "snapshots";
    private static final TopicPartition FIRST_PARTITION = new TopicPartition(TOPIC, 0);
    private static final TopicPartition SECOND_PARTITION = new TopicPartition(TOPIC, 1);

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"UNAVAILABLE", "DEADLINE_EXCEEDED"})
    void retriesOnlyFailedCommandAndCommitsAfterAllCommands(Status.Code code) {
        Fixture fixture = new Fixture();
        fixture.scenario("hub-1", "first", "second", "third");
        fixture.input.add(records(new ConsumerRecord<>(TOPIC, 0, 7, "hub-1", snapshot("hub-1"))));
        List<String> attempts = new ArrayList<>();
        AtomicBoolean failed = new AtomicBoolean();
        AtomicInteger pollsWhileWaiting = new AtomicInteger();
        fixture.onPoll = () -> {
            if (failed.get() && attempts.size() == 2) {
                verify(fixture.consumer, never()).commitSync(anyMap());
                assertThat(fixture.paused).contains(FIRST_PARTITION);
                pollsWhileWaiting.incrementAndGet();
            }
        };
        doAnswer(invocation -> {
            DeviceActionRequest request = invocation.getArgument(0);
            attempts.add(request.getAction().getSensorId());
            if (request.getAction().getSensorId().equals("second") && failed.compareAndSet(false, true)) {
                throw Status.fromCode(code).asRuntimeException();
            }
            return null;
        }).when(fixture.client).send(any());

        assertThatCode(fixture.processor::start).doesNotThrowAnyException();

        assertThat(attempts).containsExactly("first", "second", "second", "third");
        assertThat(pollsWhileWaiting.get()).isPositive();
        assertThat(fixture.commits).containsExactly(Map.of(FIRST_PARTITION, new OffsetAndMetadata(8)));
        verify(fixture.repository).findByHubId("hub-1");
        verify(fixture.consumer, atLeastOnce()).resume(anyCollection());
    }

    @Test
    void malformedActionDoesNotBlockValidActions() {
        Fixture fixture = new Fixture();
        Scenario scenario = fixture.scenario("hub-1", "first", "broken", "third");
        scenario.getActions().get(1).getAction().setType(null);
        fixture.input.add(records(new ConsumerRecord<>(TOPIC, 0, 0, "hub-1", snapshot("hub-1"))));
        List<String> sent = new ArrayList<>();
        doAnswer(invocation -> {
            DeviceActionRequest request = invocation.getArgument(0);
            sent.add(request.getAction().getSensorId());
            return null;
        }).when(fixture.client).send(any());

        assertThatCode(fixture.processor::start).doesNotThrowAnyException();

        assertThat(sent).containsExactly("first", "third");
        assertThat(fixture.commits).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"INVALID_ARGUMENT", "NOT_FOUND"})
    void rejectedCommandDoesNotBlockFollowingCommands(Status.Code code) {
        Fixture fixture = new Fixture();
        fixture.scenario("hub-1", "broken", "valid");
        fixture.input.add(records(new ConsumerRecord<>(TOPIC, 0, 0, "hub-1", snapshot("hub-1"))));
        List<String> attempts = new ArrayList<>();
        doAnswer(invocation -> {
            DeviceActionRequest request = invocation.getArgument(0);
            attempts.add(request.getAction().getSensorId());
            if (request.getAction().getSensorId().equals("broken")) {
                throw Status.fromCode(code).asRuntimeException();
            }
            return null;
        }).when(fixture.client).send(any());

        assertThatCode(fixture.processor::start).doesNotThrowAnyException();

        assertThat(attempts).containsExactly("broken", "valid");
        assertThat(fixture.commits).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"OUT_OF_RANGE", "INTERNAL", "PERMISSION_DENIED"})
    void unclassifiedGrpcFailureIsNotSilentlySkipped(Status.Code code) {
        Fixture fixture = new Fixture();
        fixture.scenario("hub-1", "first");
        fixture.input.add(records(new ConsumerRecord<>(TOPIC, 0, 0, "hub-1", snapshot("hub-1"))));
        doThrow(Status.fromCode(code).asRuntimeException()).when(fixture.client).send(any());

        assertThatThrownBy(fixture.processor::start).isInstanceOf(StatusRuntimeException.class);

        assertThat(fixture.commits).isEmpty();
    }

    @Test
    void nullSnapshotIsSkippedWithoutSendingCommands() {
        Fixture fixture = new Fixture();
        fixture.input.add(records(new ConsumerRecord<>(TOPIC, 0, 5, "hub-1", null)));

        assertThatCode(fixture.processor::start).doesNotThrowAnyException();

        verify(fixture.client, never()).send(any());
        verify(fixture.repository, never()).findByHubId(any());
        assertThat(fixture.commits).containsExactly(Map.of(FIRST_PARTITION, new OffsetAndMetadata(6)));
    }

    @Test
    void snapshotWithoutScenariosIsCommitted() {
        Fixture fixture = new Fixture();
        fixture.input.add(records(new ConsumerRecord<>(TOPIC, 0, 5, "hub-1", snapshot("hub-1"))));

        assertThatCode(fixture.processor::start).doesNotThrowAnyException();

        verify(fixture.client, never()).send(any());
        assertThat(fixture.commits).containsExactly(Map.of(FIRST_PARTITION, new OffsetAndMetadata(6)));
    }

    @Test
    void unexpectedFailureIsNotSilentlySkipped() {
        Fixture fixture = new Fixture();
        fixture.scenario("hub-1", "first");
        fixture.input.add(records(new ConsumerRecord<>(TOPIC, 0, 0, "hub-1", snapshot("hub-1"))));
        doThrow(new IllegalStateException("Неожиданная ошибка программы")).when(fixture.client).send(any());

        assertThatThrownBy(fixture.processor::start).isInstanceOf(IllegalStateException.class);

        assertThat(fixture.commits).isEmpty();
        verify(fixture.consumer).close(Duration.ofSeconds(2));
    }

    @Test
    void stoppingDuringFailureDoesNotCommitPendingSnapshot() {
        Fixture fixture = new Fixture();
        fixture.scenario("hub-1", "first");
        fixture.input.add(records(new ConsumerRecord<>(TOPIC, 0, 0, "hub-1", snapshot("hub-1"))));
        doAnswer(invocation -> {
            fixture.processor.stop();
            throw Status.UNAVAILABLE.asRuntimeException();
        }).when(fixture.client).send(any());

        assertThatCode(fixture.processor::start).doesNotThrowAnyException();

        assertThat(fixture.commits).isEmpty();
        verify(fixture.consumer).close(Duration.ofSeconds(2));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rebalanceDropsRevokedWorkButKeepsOtherPartition(boolean lost) {
        Fixture fixture = new Fixture();
        fixture.scenario("hub-1", "first", "failed", "must-not-send");
        fixture.scenario("hub-2", "retained");
        fixture.input.add(records(
                new ConsumerRecord<>(TOPIC, 0, 7, "hub-1", snapshot("hub-1")),
                new ConsumerRecord<>(TOPIC, 1, 12, "hub-2", snapshot("hub-2"))));
        List<String> attempts = new ArrayList<>();
        AtomicBoolean failed = new AtomicBoolean();
        fixture.onPoll = () -> {
            if (failed.compareAndSet(true, false)) {
                if (lost) {
                    fixture.listener.onPartitionsLost(Set.of(FIRST_PARTITION));
                } else {
                    fixture.listener.onPartitionsRevoked(Set.of(FIRST_PARTITION));
                }
                fixture.assigned = Set.of(SECOND_PARTITION);
                fixture.paused.retainAll(fixture.assigned);
                fixture.listener.onPartitionsAssigned(fixture.assigned);
            }
        };
        doAnswer(invocation -> {
            DeviceActionRequest request = invocation.getArgument(0);
            attempts.add(request.getAction().getSensorId());
            if (request.getAction().getSensorId().equals("failed")) {
                failed.set(true);
                throw Status.UNAVAILABLE.asRuntimeException();
            }
            return null;
        }).when(fixture.client).send(any());

        assertThatCode(fixture.processor::start).doesNotThrowAnyException();

        assertThat(attempts).containsExactly("first", "failed", "retained");
        assertThat(fixture.commits).containsExactly(Map.of(SECOND_PARTITION, new OffsetAndMetadata(13)));
    }

    private static SensorsSnapshotAvro snapshot(String hubId) {
        return new SensorsSnapshotAvro(hubId, Instant.parse("2026-09-29T00:00:00Z"), Map.of());
    }

    @SafeVarargs
    private static ConsumerRecords<String, SensorsSnapshotAvro> records(
            ConsumerRecord<String, SensorsSnapshotAvro>... records) {
        Map<TopicPartition, List<ConsumerRecord<String, SensorsSnapshotAvro>>> byPartition = new LinkedHashMap<>();
        for (var record : records) {
            byPartition.computeIfAbsent(new TopicPartition(record.topic(), record.partition()),
                    key -> new ArrayList<>()).add(record);
        }
        return new ConsumerRecords<>(byPartition);
    }

    private static class Fixture {
        final Consumer<String, SensorsSnapshotAvro> consumer = mock();
        final ScenarioRepository repository = mock();
        final HubRouterClient client = mock();
        final SnapshotProcessor processor;
        final Deque<ConsumerRecords<String, SensorsSnapshotAvro>> input = new ArrayDeque<>();
        final List<Map<TopicPartition, OffsetAndMetadata>> commits = new ArrayList<>();
        final Set<TopicPartition> paused = new HashSet<>();
        Set<TopicPartition> assigned = Set.of(FIRST_PARTITION, SECOND_PARTITION);
        ConsumerRebalanceListener listener;
        Runnable onPoll = () -> { };

        Fixture() {
            SnapshotConsumerProperties properties = new SnapshotConsumerProperties();
            properties.setTopic(TOPIC);
            properties.setPollTimeout(Duration.ofMillis(20));
            properties.setCloseTimeout(Duration.ofSeconds(2));
            properties.setShutdownTimeout(Duration.ofSeconds(2));
            SnapshotService service = new SnapshotService(repository, new ConditionEvaluator(), client);
            processor = new SnapshotProcessor(consumer, service, properties);
            when(consumer.assignment()).thenAnswer(invocation -> assigned);
            when(consumer.paused()).thenAnswer(invocation -> Set.copyOf(paused));
            doAnswer(invocation -> {
                listener = invocation.getArgument(1);
                return null;
            }).when(consumer).subscribe(anyCollection(), any(ConsumerRebalanceListener.class));
            doAnswer(invocation -> {
                Collection<TopicPartition> partitions = invocation.getArgument(0);
                paused.addAll(partitions);
                return null;
            }).when(consumer).pause(anyCollection());
            doAnswer(invocation -> {
                Collection<TopicPartition> partitions = invocation.getArgument(0);
                paused.removeAll(partitions);
                return null;
            }).when(consumer).resume(anyCollection());
            when(consumer.poll(any(Duration.class))).thenAnswer(invocation -> {
                onPoll.run();
                if (!input.isEmpty()) {
                    return input.removeFirst();
                }
                Duration timeout = invocation.getArgument(0);
                LockSupport.parkNanos(timeout.toNanos());
                return ConsumerRecords.empty();
            });
            doAnswer(invocation -> {
                Map<TopicPartition, OffsetAndMetadata> offsets = invocation.getArgument(0);
                commits.add(Map.copyOf(offsets));
                processor.stop();
                return null;
            }).when(consumer).commitSync(anyMap());
        }

        Scenario scenario(String hubId, String... sensors) {
            Scenario scenario = new Scenario();
            scenario.setId(1L);
            scenario.setName("scenario-1");
            scenario.setHubId(hubId);
            for (String sensorId : sensors) {
                Sensor sensor = new Sensor();
                sensor.setId(sensorId);
                sensor.setHubId(hubId);
                Action action = new Action();
                action.setType(ActionType.ACTIVATE);
                ScenarioAction link = new ScenarioAction();
                link.setSensor(sensor);
                link.setAction(action);
                link.setScenario(scenario);
                scenario.getActions().add(link);
            }
            when(repository.findByHubId(hubId)).thenReturn(List.of(scenario));
            return scenario;
        }
    }
}
