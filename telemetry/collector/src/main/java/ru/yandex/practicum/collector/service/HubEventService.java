package ru.yandex.practicum.collector.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.yandex.practicum.collector.kafka.KafkaEventSender;
import ru.yandex.practicum.collector.mapper.HubEventMapper;
import ru.yandex.practicum.collector.model.hub.HubEvent;
import ru.yandex.practicum.kafka.telemetry.event.HubEventAvro;

import java.util.concurrent.CompletableFuture;

@Service
public class HubEventService {

    private final HubEventMapper mapper;
    private final KafkaEventSender sender;
    private final String hubsTopic;

    public HubEventService(
            HubEventMapper mapper,
            KafkaEventSender sender,
            @Value("${collector.kafka.topics.hubs}") String hubsTopic) {

        this.mapper = mapper;
        this.sender = sender;
        this.hubsTopic = hubsTopic;
    }

    public CompletableFuture<Void> collect(HubEvent event) {
        HubEventAvro avroEvent = mapper.toAvro(event);

        return sender.send(
                hubsTopic,
                avroEvent.getHubId(),
                avroEvent
        );
    }
}