package ru.yandex.practicum.collector.mapper;

import org.apache.avro.specific.SpecificRecordBase;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.collector.model.sensor.ClimateSensorEvent;
import ru.yandex.practicum.collector.model.sensor.LightSensorEvent;
import ru.yandex.practicum.collector.model.sensor.MotionSensorEvent;
import ru.yandex.practicum.collector.model.sensor.SensorEvent;
import ru.yandex.practicum.collector.model.sensor.SwitchSensorEvent;
import ru.yandex.practicum.collector.model.sensor.TemperatureSensorEvent;
import ru.yandex.practicum.kafka.telemetry.event.ClimateSensorAvro;
import ru.yandex.practicum.kafka.telemetry.event.LightSensorAvro;
import ru.yandex.practicum.kafka.telemetry.event.MotionSensorAvro;
import ru.yandex.practicum.kafka.telemetry.event.SensorEventAvro;
import ru.yandex.practicum.kafka.telemetry.event.SwitchSensorAvro;
import ru.yandex.practicum.kafka.telemetry.event.TemperatureSensorAvro;

@Component
public class SensorEventMapper {

    public SensorEventAvro toAvro(SensorEvent event) {
        SpecificRecordBase payload = switch (event) {
            case ClimateSensorEvent data -> new ClimateSensorAvro(
                    data.getTemperatureC(),
                    data.getHumidity(),
                    data.getCo2Level()
            );
            case LightSensorEvent data -> new LightSensorAvro(
                    data.getLinkQuality(),
                    data.getLuminosity()
            );
            case MotionSensorEvent data -> new MotionSensorAvro(
                    data.getLinkQuality(),
                    data.getMotion(),
                    data.getVoltage()
            );
            case SwitchSensorEvent data -> new SwitchSensorAvro(
                    data.getState()
            );
            case TemperatureSensorEvent data -> new TemperatureSensorAvro(
                    data.getTemperatureC(),
                    data.getTemperatureF()
            );
            case null -> throw new IllegalArgumentException(
                    "Событие датчика не должно быть null"
            );
            default -> throw new IllegalArgumentException(
                    "Неизвестный класс события датчика: "
                            + event.getClass().getSimpleName()
            );
        };

        return new SensorEventAvro(
                event.getId(),
                event.getHubId(),
                event.getTimestamp(),
                payload
        );
    }
}