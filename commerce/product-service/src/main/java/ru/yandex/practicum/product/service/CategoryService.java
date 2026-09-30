package ru.yandex.practicum.product.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.yandex.practicum.product.dto.CategoryDto;
import ru.yandex.practicum.product.dto.CreateCategoryRequest;
import ru.yandex.practicum.product.exception.NotFoundException;
import ru.yandex.practicum.product.mapper.CategoryMapper;
import ru.yandex.practicum.product.repository.CategoryRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    public List<CategoryDto> findAll() {
        return categoryRepository.findAllByOrderByIdAsc().stream().map(categoryMapper::toDto).toList();
    }

    public CategoryDto findById(Long id) {
        return categoryMapper.toDto(categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Категория с id " + id + " не найдена")));
    }

    public CategoryDto create(CreateCategoryRequest request) {
        return categoryMapper.toDto(categoryRepository.save(categoryMapper.toEntity(request)));
    }
}
