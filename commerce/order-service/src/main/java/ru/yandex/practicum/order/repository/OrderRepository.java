package ru.yandex.practicum.order.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.yandex.practicum.order.entity.Order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Override
    @EntityGraph(attributePaths = "items")
    Optional<Order> findById(Long id);

    @EntityGraph(attributePaths = "items")
    List<Order> findAllByOrderByIdAsc();

    @EntityGraph(attributePaths = "items")
    List<Order> findAllByCustomerEmailOrderByIdAsc(String customerEmail);
}
