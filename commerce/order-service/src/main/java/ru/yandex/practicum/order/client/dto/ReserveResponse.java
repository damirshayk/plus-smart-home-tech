package ru.yandex.practicum.order.client.dto;

public record ReserveResponse(boolean success, Integer availableQuantity, String message) {
}
