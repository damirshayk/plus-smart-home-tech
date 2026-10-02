package ru.yandex.practicum.order.client.dto;

public record InventoryRequest(Long productId, Integer quantity) {
}
