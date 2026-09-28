package ru.yandex.practicum.analyzer.service;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.analyzer.config.SnapshotConsumerProperties;
import ru.yandex.practicum.grpc.telemetry.event.DeviceActionRequest;
import ru.yandex.practicum.kafka.telemetry.event.SensorsSnapshotAvro;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class SnapshotProcessor extends AbstractKafkaProcessor<SensorsSnapshotAvro> {

    private final SnapshotService service;
    private final long retryDelayNanos;
    private final Deque<ConsumerRecord<String, SensorsSnapshotAvro>> pending = new ArrayDeque<>();
    private List<DeviceActionRequest> commands;
    private int nextCommand;
    private boolean retryPending;
    private long retryAt;

    public SnapshotProcessor(@Qualifier("snapshotConsumer") Consumer<String, SensorsSnapshotAvro> consumer,
                             SnapshotService service, SnapshotConsumerProperties properties) {
        super(consumer, properties.getTopic(), properties.getPollTimeout(),
                properties.getCloseTimeout(), properties.getShutdownTimeout());
        this.service = service;
        Duration retryDelay = properties.getRetryDelay();
        if (retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("Задержка повтора должна быть положительной");
        }
        this.retryDelayNanos = retryDelay.toNanos();
    }

    @Override
    protected void subscribe(String topic) {
        consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                discard(partitions);
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                updatePause();
            }

            @Override
            public void onPartitionsLost(Collection<TopicPartition> partitions) {
                discard(partitions);
            }
        });
    }

    @Override
    protected Duration getPollTimeout() {
        if (pending.isEmpty()) {
            return super.getPollTimeout();
        }
        if (!retryPending) {
            return Duration.ZERO;
        }
        long remaining = Math.max(0, retryAt - System.nanoTime());
        return Duration.ofNanos(Math.min(remaining, super.getPollTimeout().toNanos()));
    }

    @Override
    protected void processRecords(ConsumerRecords<String, SensorsSnapshotAvro> records) {
        records.forEach(pending::addLast);
        updatePause();
        if (!isRunning() || pending.isEmpty() || retryPending && System.nanoTime() - retryAt < 0) {
            return;
        }
        ConsumerRecord<String, SensorsSnapshotAvro> record = pending.getFirst();
        if (commands == null) {
            try {
                commands = service.prepare(record.value());
            } catch (IllegalArgumentException e) {
                log.warn("Отклонён снимок: topic={}, partition={}, offset={}: {}",
                        record.topic(), record.partition(), record.offset(), e.getMessage());
                complete(record);
                return;
            }
        }
        if (nextCommand < commands.size()) {
            DeviceActionRequest command = commands.get(nextCommand);
            try {
                service.send(command);
            } catch (StatusRuntimeException e) {
                Status.Code code = e.getStatus().getCode();
                if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                    retryPending = true;
                    retryAt = System.nanoTime() + retryDelayNanos;
                    log.warn("Команда ожидает повтора: hubId={}, sensorId={}, status={}, partition={}, offset={}",
                            command.getHubId(), command.getAction().getSensorId(),
                            code, record.partition(), record.offset());
                    return;
                }
                if (code != Status.Code.INVALID_ARGUMENT && code != Status.Code.NOT_FOUND) {
                    throw e;
                }
                log.warn("Пропущена отклонённая команда: hubId={}, sensorId={}, status={}, partition={}, offset={}",
                        command.getHubId(), command.getAction().getSensorId(),
                        code, record.partition(), record.offset());
            }
            nextCommand++;
            retryPending = false;
        }
        if (nextCommand == commands.size()) {
            complete(record);
        }
    }

    private void complete(ConsumerRecord<String, SensorsSnapshotAvro> record) {
        commit(record);
        pending.removeFirst();
        resetProgress();
        updatePause();
    }

    private void resetProgress() {
        commands = null;
        nextCommand = 0;
        retryPending = false;
    }

    private void discard(Collection<TopicPartition> partitions) {
        Set<TopicPartition> revoked = Set.copyOf(partitions);
        ConsumerRecord<String, SensorsSnapshotAvro> current = pending.peekFirst();
        if (current != null && revoked.contains(new TopicPartition(current.topic(), current.partition()))) {
            resetProgress();
        }
        pending.removeIf(record -> revoked.contains(new TopicPartition(record.topic(), record.partition())));
    }

    private void updatePause() {
        if (pending.isEmpty()) {
            Set<TopicPartition> paused = consumer.paused();
            if (!paused.isEmpty()) {
                consumer.resume(paused);
            }
        } else {
            consumer.pause(consumer.assignment());
        }
    }

    private void commit(ConsumerRecord<String, SensorsSnapshotAvro> record) {
        // Синхронная фиксация каждого снимка уменьшает повторную отправку команд после сбоя.
        // Ради этого принимаем ожидание ответа Kafka на каждой записи.
        Map<TopicPartition, OffsetAndMetadata> offsets = Map.of(
                new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset() + 1));
        try {
            consumer.commitSync(offsets);
        } catch (WakeupException e) {
            if (isRunning()) {
                throw e;
            }
            consumer.commitSync(offsets);
        }
    }
}
