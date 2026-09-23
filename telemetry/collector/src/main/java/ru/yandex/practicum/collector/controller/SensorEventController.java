package ru.yandex.practicum.collector.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.yandex.practicum.collector.model.sensor.SensorEvent;
import ru.yandex.practicum.collector.service.SensorEventService;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/events/sensors")
@RequiredArgsConstructor
public class SensorEventController {

    private final SensorEventService service;

    @PostMapping
    public CompletableFuture<Void> collect(
            @Valid @RequestBody SensorEvent event) {

        return service.collect(event);
    }
}