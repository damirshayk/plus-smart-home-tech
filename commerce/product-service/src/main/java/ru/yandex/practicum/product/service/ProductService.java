package ru.yandex.practicum.product.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.yandex.practicum.product.dto.CreateProductRequest;
import ru.yandex.practicum.product.dto.ProductDto;
import ru.yandex.practicum.product.dto.UpdateProductRequest;
import ru.yandex.practicum.product.entity.Category;
import ru.yandex.practicum.product.entity.Product;
import ru.yandex.practicum.product.exception.NotFoundException;
import ru.yandex.practicum.product.mapper.ProductMapper;
import ru.yandex.practicum.product.repository.CategoryRepository;
import ru.yandex.practicum.product.repository.ProductRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductMapper productMapper;

    public List<ProductDto> findAll() {
        return productRepository.findAllByActiveTrueOrderByIdAsc().stream().map(productMapper::toDto).toList();
    }

    public ProductDto findById(Long id) {
        return productMapper.toDto(findProduct(id));
    }

    public List<ProductDto> findByCategory(Long categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new NotFoundException("Категория с id " + categoryId + " не найдена");
        }
        return productRepository.findAllByCategoryIdAndActiveTrueOrderByIdAsc(categoryId)
                .stream().map(productMapper::toDto).toList();
    }

    public List<ProductDto> search(String query) {
        return productRepository.findAllByActiveTrueAndNameContainingIgnoreCaseOrderByIdAsc(query)
                .stream().map(productMapper::toDto).toList();
    }

    @Transactional
    public ProductDto create(CreateProductRequest request) {
        Category category = request.categoryId() == null ? null : findCategory(request.categoryId());
        Product product = productMapper.toEntity(request, category);
        return productMapper.toDto(productRepository.save(product));
    }

    @Transactional
    public ProductDto update(Long id, UpdateProductRequest request) {
        Product product = findProduct(id);
        if (request.categoryId() != null
                && (product.getCategory() == null || !request.categoryId().equals(product.getCategory().getId()))) {
            product.setCategory(findCategory(request.categoryId()));
        }
        if (request.name() != null) {
            product.setName(request.name());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.price() != null) {
            product.setPrice(request.price());
        }
        if (request.imageUrl() != null) {
            product.setImageUrl(request.imageUrl());
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }
        return productMapper.toDto(product);
    }

    private Product findProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Товар с id " + id + " не найден"));
    }

    private Category findCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Категория с id " + id + " не найдена"));
    }
}
