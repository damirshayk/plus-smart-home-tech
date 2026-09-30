package ru.yandex.practicum.collector.mapper;

import org.apache.avro.specific.SpecificRecordBase;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.grpc.telemetry.event.DeviceActionProto;
import ru.yandex.practicum.grpc.telemetry.event.HubEventProto;
import ru.yandex.practicum.grpc.telemetry.event.ScenarioConditionProto;
import ru.yandex.practicum.kafka.telemetry.event.ActionTypeAvro;
import ru.yandex.practicum.kafka.telemetry.event.ConditionOperationAvro;
import ru.yandex.practicum.kafka.telemetry.event.ConditionTypeAvro;
import ru.yandex.practicum.kafka.telemetry.event.DeviceActionAvro;
import ru.yandex.practicum.kafka.telemetry.event.DeviceAddedEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.DeviceRemovedEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.DeviceTypeAvro;
import ru.yandex.practicum.kafka.telemetry.event.HubEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.ScenarioAddedEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.ScenarioConditionAvro;
import ru.yandex.practicum.kafka.telemetry.event.ScenarioRemovedEventAvro;

import java.time.Instant;

@Component
public class HubEventMapper {

    public HubEventAvro toAvro(HubEventProto event) {
        if (event == null) {
            throw new IllegalArgumentException("Событие хаба не должно быть null");
        }

        if (!event.hasTimestamp()) {
            throw new IllegalArgumentException("Время события хаба должно быть указано");
        }

        SpecificRecordBase payload = switch (event.getPayloadCase()) {
            case DEVICE_ADDED ->
                    new DeviceAddedEventAvro(event.getDeviceAdded().getId(), DeviceTypeAvro.valueOf(event.getDeviceAdded().getType().name()));
            case DEVICE_REMOVED -> new DeviceRemovedEventAvro(event.getDeviceRemoved().getId());
            case SCENARIO_ADDED ->
                    new ScenarioAddedEventAvro(event.getScenarioAdded().getName(), event.getScenarioAdded().getConditionList().stream().map(this::toConditionAvro).toList(), event.getScenarioAdded().getActionList().stream().map(this::toActionAvro).toList());
            case SCENARIO_REMOVED -> new ScenarioRemovedEventAvro(event.getScenarioRemoved().getName());
            case PAYLOAD_NOT_SET -> throw new IllegalArgumentException("Данные события хаба должны быть указаны");
        };

        Instant timestamp = Instant.ofEpochSecond(event.getTimestamp().getSeconds(), event.getTimestamp().getNanos());

        return new HubEventAvro(event.getHubId(), timestamp, payload);
    }

    private ScenarioConditionAvro toConditionAvro(ScenarioConditionProto condition) {

        Object value = switch (condition.getValueCase()) {
            case BOOL_VALUE -> condition.getBoolValue();
            case INT_VALUE -> condition.getIntValue();
            case VALUE_NOT_SET -> null;
        };

        return new ScenarioConditionAvro(condition.getSensorId(), ConditionTypeAvro.valueOf(condition.getType().name()), ConditionOperationAvro.valueOf(condition.getOperation().name()), value);
    }

    private DeviceActionAvro toActionAvro(DeviceActionProto action) {
        return new DeviceActionAvro(action.getSensorId(), ActionTypeAvro.valueOf(action.getType().name()), action.hasValue() ? action.getValue() : null);
    }
}