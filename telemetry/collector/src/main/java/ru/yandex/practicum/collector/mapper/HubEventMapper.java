package ru.yandex.practicum.collector.mapper;

import org.apache.avro.specific.SpecificRecordBase;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.collector.model.hub.DeviceAction;
import ru.yandex.practicum.collector.model.hub.DeviceAddedEvent;
import ru.yandex.practicum.collector.model.hub.DeviceRemovedEvent;
import ru.yandex.practicum.collector.model.hub.HubEvent;
import ru.yandex.practicum.collector.model.hub.ScenarioAddedEvent;
import ru.yandex.practicum.collector.model.hub.ScenarioCondition;
import ru.yandex.practicum.collector.model.hub.ScenarioRemovedEvent;
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

@Component
public class HubEventMapper {

    public HubEventAvro toAvro(HubEvent event) {
        SpecificRecordBase payload = switch (event) {
            case DeviceAddedEvent data -> new DeviceAddedEventAvro(
                    data.getId(),
                    DeviceTypeAvro.valueOf(data.getDeviceType().name())
            );
            case DeviceRemovedEvent data -> new DeviceRemovedEventAvro(
                    data.getId()
            );
            case ScenarioAddedEvent data -> new ScenarioAddedEventAvro(
                    data.getName(),
                    data.getConditions().stream()
                            .map(this::toConditionAvro)
                            .toList(),
                    data.getActions().stream()
                            .map(this::toActionAvro)
                            .toList()
            );
            case ScenarioRemovedEvent data -> new ScenarioRemovedEventAvro(
                    data.getName()
            );
            case null -> throw new IllegalArgumentException(
                    "Событие хаба не должно быть null"
            );
            default -> throw new IllegalArgumentException(
                    "Неизвестный класс события хаба: "
                            + event.getClass().getSimpleName()
            );
        };

        return new HubEventAvro(
                event.getHubId(),
                event.getTimestamp(),
                payload
        );
    }

    private ScenarioConditionAvro toConditionAvro(
            ScenarioCondition condition) {

        Integer originalValue = condition.getValue();
        Object avroValue = originalValue;

        if (originalValue != null) {
            avroValue = switch (condition.getType()) {
                case MOTION, SWITCH -> originalValue != 0;
                default -> originalValue;
            };
        }

        return new ScenarioConditionAvro(
                condition.getSensorId(),
                ConditionTypeAvro.valueOf(condition.getType().name()),
                ConditionOperationAvro.valueOf(
                        condition.getOperation().name()
                ),
                avroValue
        );
    }

    private DeviceActionAvro toActionAvro(DeviceAction action) {
        return new DeviceActionAvro(
                action.getSensorId(),
                ActionTypeAvro.valueOf(action.getType().name()),
                action.getValue()
        );
    }
}