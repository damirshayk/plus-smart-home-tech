package ru.yandex.practicum.analyzer.service;

import org.springframework.stereotype.Component;
import ru.yandex.practicum.analyzer.model.Condition;
import ru.yandex.practicum.analyzer.model.ConditionType;
import ru.yandex.practicum.analyzer.model.ScenarioCondition;
import ru.yandex.practicum.kafka.telemetry.event.ClimateSensorAvro;
import ru.yandex.practicum.kafka.telemetry.event.LightSensorAvro;
import ru.yandex.practicum.kafka.telemetry.event.MotionSensorAvro;
import ru.yandex.practicum.kafka.telemetry.event.SensorStateAvro;
import ru.yandex.practicum.kafka.telemetry.event.SwitchSensorAvro;
import ru.yandex.practicum.kafka.telemetry.event.TemperatureSensorAvro;

import java.util.Map;

@Component
public class ConditionEvaluator {

    public boolean matches(ScenarioCondition link, Map<String, SensorStateAvro> states) {
        if (link == null || link.getSensor() == null || link.getCondition() == null || states == null) {
            return false;
        }
        Condition condition = link.getCondition();
        if (condition.getType() == null || condition.getOperation() == null || condition.getValue() == null) {
            return false;
        }
        String sensorId = link.getSensor().getId();
        SensorStateAvro state = sensorId == null ? null : states.get(sensorId);
        if (state == null || state.getData() == null) {
            return false;
        }
        Integer actual = value(condition.getType(), state.getData());
        if (actual == null) {
            return false;
        }
        int comparison = Integer.compare(actual, condition.getValue());
        return switch (condition.getOperation()) {
            case EQUALS -> comparison == 0;
            case GREATER_THAN -> comparison > 0;
            case LOWER_THAN -> comparison < 0;
        };
    }

    private Integer value(ConditionType type, Object data) {
        return switch (type) {
            case MOTION -> data instanceof MotionSensorAvro sensor ? (sensor.getMotion() ? 1 : 0) : null;
            case SWITCH -> data instanceof SwitchSensorAvro sensor ? (sensor.getState() ? 1 : 0) : null;
            case LUMINOSITY -> data instanceof LightSensorAvro sensor ? sensor.getLuminosity() : null;
            case HUMIDITY -> data instanceof ClimateSensorAvro sensor ? sensor.getHumidity() : null;
            case CO2LEVEL -> data instanceof ClimateSensorAvro sensor ? sensor.getCo2Level() : null;
            case TEMPERATURE -> switch (data) {
                case TemperatureSensorAvro sensor -> sensor.getTemperatureC();
                case ClimateSensorAvro sensor -> sensor.getTemperatureC();
                default -> null;
            };
        };
    }
}
