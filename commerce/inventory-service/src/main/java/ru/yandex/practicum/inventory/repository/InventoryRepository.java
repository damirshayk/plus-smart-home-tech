package ru.yandex.practicum.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.yandex.practicum.inventory.entity.Inventory;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    List<Inventory> findAllByOrderByIdAsc();

    Optional<Inventory> findByProductId(Long productId);

    boolean existsByProductId(Long productId);
}
