package ru.yandex.practicum.aggregator.service;

import org.springframework.stereotype.Service;
import ru.yandex.practicum.kafka.telemetry.event.SensorEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.SensorStateAvro;
import ru.yandex.practicum.kafka.telemetry.event.SensorsSnapshotAvro;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class SnapshotService {

    private final Map<String, SensorsSnapshotAvro> snapshots = new HashMap<>();

    public Optional<SensorsSnapshotAvro> updateState(SensorEventAvro event) {
        Objects.requireNonNull(event, "Событие не должно быть null");

        SensorEventAvro eventCopy = SensorEventAvro.newBuilder(event).build();

        SensorsSnapshotAvro snapshot = snapshots.get(eventCopy.getHubId());

        if (snapshot == null) {
            snapshot = new SensorsSnapshotAvro(
                    eventCopy.getHubId(),
                    eventCopy.getTimestamp(),
                    new HashMap<>()
            );
            snapshots.put(eventCopy.getHubId(), snapshot);
        }

        SensorStateAvro oldState =
                snapshot.getSensorsState().get(eventCopy.getId());

        if (oldState != null
                && (oldState.getTimestamp().isAfter(eventCopy.getTimestamp())
                || oldState.getData().equals(eventCopy.getPayload()))) {
            return Optional.empty();
        }

        SensorStateAvro newState = new SensorStateAvro(
                eventCopy.getTimestamp(),
                eventCopy.getPayload()
        );

        snapshot.getSensorsState().put(eventCopy.getId(), newState);
        snapshot.setTimestamp(eventCopy.getTimestamp());

        return Optional.of(SensorsSnapshotAvro.newBuilder(snapshot).build());
    }
}