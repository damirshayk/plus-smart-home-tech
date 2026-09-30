package ru.yandex.practicum.analyzer.service;

import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.yandex.practicum.grpc.telemetry.event.DeviceActionRequest;
import ru.yandex.practicum.grpc.telemetry.hubrouter.HubRouterControllerGrpc.HubRouterControllerBlockingStub;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class HubRouterClient {

    private final HubRouterControllerBlockingStub client;
    private final Duration requestTimeout;

    public HubRouterClient(@GrpcClient("hub-router") HubRouterControllerBlockingStub client,
                           @Value("${analyzer.hub-router.request-timeout:5s}") Duration requestTimeout) {
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("Таймаут запроса к Hub Router должен быть положительным");
        }
        this.client = client;
        this.requestTimeout = requestTimeout;
    }

    public void send(DeviceActionRequest request) {
        client.withDeadlineAfter(requestTimeout.toNanos(), TimeUnit.NANOSECONDS).handleDeviceAction(request);
    }
}
