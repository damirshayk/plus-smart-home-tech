package ru.yandex.practicum.inventory.mapper;

import org.springframework.stereotype.Component;
import ru.yandex.practicum.inventory.dto.InventoryDto;
import ru.yandex.practicum.inventory.dto.UpdateInventoryRequest;
import ru.yandex.practicum.inventory.entity.Inventory;

@Component
public class InventoryMapper {

    public InventoryDto toDto(Inventory inventory) {
        return new InventoryDto(inventory.getId(), inventory.getProductId(), inventory.getQuantity(),
                inventory.getReservedQuantity(), inventory.getAvailableQuantity());
    }

    public Inventory toEntity(UpdateInventoryRequest request) {
        Inventory inventory = new Inventory();
        inventory.setProductId(request.productId());
        inventory.setQuantity(request.quantity());
        return inventory;
    }
}
