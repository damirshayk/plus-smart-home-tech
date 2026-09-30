package ru.yandex.practicum.inventory.exception;

public class InventoryAlreadyExistsException extends RuntimeException {

    public InventoryAlreadyExistsException(Long productId) {
        this(productId, null);
    }

    public InventoryAlreadyExistsException(Long productId, Throwable cause) {
        super("Складская запись для товара с id " + productId + " уже существует", cause);
    }
}
