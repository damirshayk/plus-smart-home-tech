package ru.yandex.practicum.order.exception;

public class ProductServiceUnavailableException extends RuntimeException {

    public ProductServiceUnavailableException(Long productId, Throwable cause) {
        super("Сервис товаров недоступен для товара с id " + productId, cause);
    }
}
