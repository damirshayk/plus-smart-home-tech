package ru.yandex.practicum.product.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.yandex.practicum.product.entity.Product;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Override
    @EntityGraph(attributePaths = "category")
    Optional<Product> findById(Long id);

    @EntityGraph(attributePaths = "category")
    List<Product> findAllByActiveTrueOrderByIdAsc();

    @EntityGraph(attributePaths = "category")
    List<Product> findAllByCategoryIdAndActiveTrueOrderByIdAsc(Long categoryId);

    @EntityGraph(attributePaths = "category")
    List<Product> findAllByActiveTrueAndNameContainingIgnoreCaseOrderByIdAsc(String query);
}
