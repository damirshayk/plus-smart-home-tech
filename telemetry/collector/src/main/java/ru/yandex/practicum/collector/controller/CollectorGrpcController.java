package ru.yandex.practicum.collector.controller;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.yandex.practicum.collector.service.HubEventService;
import ru.yandex.practicum.collector.service.SensorEventService;
import ru.yandex.practicum.grpc.telemetry.collector.CollectorControllerGrpc;
import ru.yandex.practicum.grpc.telemetry.event.HubEventProto;
import ru.yandex.practicum.grpc.telemetry.event.SensorEventProto;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Slf4j
@GrpcService
public class CollectorGrpcController
        extends CollectorControllerGrpc.CollectorControllerImplBase {

    private final SensorEventService sensorEventService;
    private final HubEventService hubEventService;

    public CollectorGrpcController(
            SensorEventService sensorEventService,
            HubEventService hubEventService) {

        this.sensorEventService = sensorEventService;
        this.hubEventService = hubEventService;
    }

    @Override
    public void collectSensorEvent(
            SensorEventProto request,
            StreamObserver<Empty> responseObserver) {

        handle(() -> {
            if (request.getId().isBlank() || request.getHubId().isBlank()) {
                throw new IllegalArgumentException(
                        "Идентификаторы датчика и хаба должны быть указаны"
                );
            }

            return sensorEventService.collect(request);
        }, responseObserver);
    }

    @Override
    public void collectHubEvent(
            HubEventProto request,
            StreamObserver<Empty> responseObserver) {

        handle(() -> {
            if (request.getHubId().isBlank()) {
                throw new IllegalArgumentException(
                        "Идентификатор хаба должен быть указан"
                );
            }

            return hubEventService.collect(request);
        }, responseObserver);
    }

    private void handle(
            Supplier<CompletableFuture<Void>> operation,
            StreamObserver<Empty> responseObserver) {

        try {
            operation.get().whenComplete((result, exception) -> {
                if (exception != null) {
                    log.error("Ошибка отправки события в Kafka", exception);
                    responseObserver.onError(
                            Status.INTERNAL
                                    .withDescription("Не удалось сохранить событие")
                                    .asRuntimeException()
                    );
                    return;
                }

                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            });
        } catch (IllegalArgumentException exception) {
            log.warn("Некорректное событие: {}", exception.getMessage());
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription("Некорректные данные события")
                            .asRuntimeException()
            );
        } catch (RuntimeException exception) {
            log.error("Ошибка обработки события", exception);
            responseObserver.onError(
                    Status.INTERNAL
                            .withDescription("Не удалось обработать событие")
                            .asRuntimeException()
            );
        }
    }
}