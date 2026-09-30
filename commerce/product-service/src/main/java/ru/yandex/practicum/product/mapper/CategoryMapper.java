package ru.yandex.practicum.product.mapper;

import org.springframework.stereotype.Component;
import ru.yandex.practicum.product.dto.CategoryDto;
import ru.yandex.practicum.product.dto.CreateCategoryRequest;
import ru.yandex.practicum.product.entity.Category;

@Component
public class CategoryMapper {

    public CategoryDto toDto(Category category) {
        if (category == null) {
            return null;
        }
        return new CategoryDto(category.getId(), category.getName(), category.getDescription());
    }

    public Category toEntity(CreateCategoryRequest request) {
        Category category = new Category();
        category.setName(request.name());
        category.setDescription(request.description());
        return category;
    }
}
