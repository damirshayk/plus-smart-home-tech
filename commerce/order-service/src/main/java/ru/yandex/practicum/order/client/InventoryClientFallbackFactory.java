package ru.yandex.practicum.order.client;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.order.client.dto.InventoryRequest;
import ru.yandex.practicum.order.client.dto.ReserveResponse;
import ru.yandex.practicum.order.exception.InventoryServiceUnavailableException;

@Component
@Slf4j
public class InventoryClientFallbackFactory implements FallbackFactory<InventoryClient> {

    @Override
    public InventoryClient create(Throwable cause) {
        return new InventoryClient() {
            @Override
            public ReserveResponse reserve(InventoryRequest request) {
                throw failure("reserve", request.productId(), cause);
            }

            @Override
            public ReserveResponse release(InventoryRequest request) {
                throw failure("release", request.productId(), cause);
            }
        };
    }

    private RuntimeException failure(String operation, Long productId, Throwable cause) {
        Throwable originalCause = cause;
        while (originalCause != null && originalCause.getCause() != null
                && !(originalCause instanceof FeignException)) {
            originalCause = originalCause.getCause();
        }
        log.warn("Сервис inventory-service, операция {}, товар {}, причина {}, HTTP-статус {}",
                operation, productId, originalCause == null ? "неизвестна" : originalCause.getClass().getSimpleName(),
                originalCause instanceof FeignException error ? error.status() : null);
        if (originalCause instanceof FeignException error && error.status() >= 400 && error.status() < 500) {
            return error;
        }
        return new InventoryServiceUnavailableException(productId, cause);
    }
}
