package ru.yandex.practicum.inventory.service;

import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.yandex.practicum.inventory.dto.InventoryDto;
import ru.yandex.practicum.inventory.dto.ReserveRequest;
import ru.yandex.practicum.inventory.dto.ReserveResponse;
import ru.yandex.practicum.inventory.dto.UpdateInventoryRequest;
import ru.yandex.practicum.inventory.entity.Inventory;
import ru.yandex.practicum.inventory.exception.InsufficientStockException;
import ru.yandex.practicum.inventory.exception.InventoryAlreadyExistsException;
import ru.yandex.practicum.inventory.exception.NotFoundException;
import ru.yandex.practicum.inventory.mapper.InventoryMapper;
import ru.yandex.practicum.inventory.repository.InventoryRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryMapper inventoryMapper;

    public List<InventoryDto> findAll() {
        return inventoryRepository.findAllByOrderByIdAsc().stream().map(inventoryMapper::toDto).toList();
    }

    public InventoryDto findByProductId(Long productId) {
        return inventoryMapper.toDto(findInventory(productId));
    }

    public InventoryDto create(UpdateInventoryRequest request) {
        if (inventoryRepository.existsByProductId(request.productId())) {
            throw new InventoryAlreadyExistsException(request.productId());
        }
        Inventory inventory = inventoryMapper.toEntity(request);
        try {
            return inventoryMapper.toDto(inventoryRepository.saveAndFlush(inventory));
        } catch (DataIntegrityViolationException e) {
            if (isProductIdConflict(e)) {
                throw new InventoryAlreadyExistsException(request.productId(), e);
            }
            throw e;
        }
    }

    @Transactional
    public InventoryDto update(UpdateInventoryRequest request) {
        Inventory inventory = findInventory(request.productId());
        if (request.quantity() < inventory.getReservedQuantity()) {
            throw new IllegalArgumentException("Общее количество не может быть меньше зарезервированного");
        }
        inventory.setQuantity(request.quantity());
        return inventoryMapper.toDto(inventory);
    }

    @Transactional
    public ReserveResponse reserve(ReserveRequest request) {
        Inventory inventory = findInventory(request.productId());
        if (request.quantity() > inventory.getAvailableQuantity()) {
            throw new InsufficientStockException("Недостаточно доступного товара с id " + request.productId());
        }
        inventory.setReservedQuantity(inventory.getReservedQuantity() + request.quantity());
        return new ReserveResponse(true, inventory.getAvailableQuantity(), "Товар успешно зарезервирован");
    }

    private boolean isProductIdConflict(DataIntegrityViolationException exception) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())) {
                String constraintName = violation.getConstraintName();
                // H2 возвращает имя индекса, PostgreSQL — имя ограничения UNIQUE.
                return "uk_inventory_product_id".equalsIgnoreCase(constraintName)
                        || constraintName != null
                        && constraintName.matches("(?i)PUBLIC\\.UK_INVENTORY_PRODUCT_ID_INDEX_[0-9A-F]+");
            }
        }
        return false;
    }

    @Transactional
    public ReserveResponse release(ReserveRequest request) {
        Inventory inventory = findInventory(request.productId());
        if (request.quantity() > inventory.getReservedQuantity()) {
            throw new IllegalArgumentException("Количество для снятия резерва превышает зарезервированное "
                    + "количество товара с id " + request.productId());
        }
        inventory.setReservedQuantity(inventory.getReservedQuantity() - request.quantity());
        return new ReserveResponse(true, inventory.getAvailableQuantity(), "Резерв товара успешно снят");
    }

    private Inventory findInventory(Long productId) {
        return inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new NotFoundException("Складская запись для товара с id " + productId + " не найдена"));
    }
}
