package ru.yandex.practicum.collector.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.yandex.practicum.collector.kafka.KafkaEventSender;
import ru.yandex.practicum.collector.mapper.SensorEventMapper;
import ru.yandex.practicum.collector.model.sensor.SensorEvent;
import ru.yandex.practicum.kafka.telemetry.event.SensorEventAvro;

import java.util.concurrent.CompletableFuture;

@Service
public class SensorEventService {

    private final SensorEventMapper mapper;
    private final KafkaEventSender sender;
    private final String sensorsTopic;

    public SensorEventService(
            SensorEventMapper mapper,
            KafkaEventSender sender,
            @Value("${collector.kafka.topics.sensors}") String sensorsTopic) {

        this.mapper = mapper;
        this.sender = sender;
        this.sensorsTopic = sensorsTopic;
    }

    public CompletableFuture<Void> collect(SensorEvent event) {
        SensorEventAvro avroEvent = mapper.toAvro(event);

        return sender.send(
                sensorsTopic,
                avroEvent.getHubId(),
                avroEvent.getTimestamp().toEpochMilli(),
                avroEvent
        );
    }
}