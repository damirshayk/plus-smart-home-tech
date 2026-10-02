package ru.yandex.practicum.order.client;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;
import ru.yandex.practicum.order.exception.ProductServiceUnavailableException;

@Component
@Slf4j
public class ProductClientFallbackFactory implements FallbackFactory<ProductClient> {

    @Override
    public ProductClient create(Throwable cause) {
        return id -> {
            Throwable originalCause = cause;
            while (originalCause != null && originalCause.getCause() != null
                    && !(originalCause instanceof FeignException)) {
                originalCause = originalCause.getCause();
            }
            log.warn("Сервис product-service, операция findById, товар {}, причина {}, HTTP-статус {}",
                    id, originalCause == null ? "неизвестна" : originalCause.getClass().getSimpleName(),
                    originalCause instanceof FeignException error ? error.status() : null);
            if (originalCause instanceof FeignException error && error.status() >= 400 && error.status() < 500) {
                throw error;
            }
            throw new ProductServiceUnavailableException(id, cause);
        };
    }
}
