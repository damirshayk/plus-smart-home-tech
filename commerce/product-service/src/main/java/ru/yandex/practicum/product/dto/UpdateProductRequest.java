package ru.yandex.practicum.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record UpdateProductRequest(

        @Pattern(regexp = "(?s).*\\S.*", message = "Название товара не должно быть пустым")
        @Size(max = 255, message = "Название не может быть длиннее 255 символов")
        String name,

        @Size(max = 2000, message = "Описание не может быть длиннее 2000 символов")
        String description,

        @DecimalMin(value = "0.01", message = "Цена должна быть больше нуля")
        BigDecimal price,

        Long categoryId,

        String imageUrl,

        Boolean active
) {
}
