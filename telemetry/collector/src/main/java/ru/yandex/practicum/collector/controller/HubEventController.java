package ru.yandex.practicum.collector.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.yandex.practicum.collector.model.hub.HubEvent;
import ru.yandex.practicum.collector.service.HubEventService;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/events/hubs")
@RequiredArgsConstructor
public class HubEventController {

    private final HubEventService service;

    @PostMapping
    public CompletableFuture<Void> collect(
            @Valid @RequestBody HubEvent event) {

        return service.collect(event);
    }
}