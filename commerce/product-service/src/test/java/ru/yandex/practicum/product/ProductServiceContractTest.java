package ru.yandex.practicum.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.yandex.practicum.product.dto.CategoryDto;
import ru.yandex.practicum.product.dto.CreateCategoryRequest;
import ru.yandex.practicum.product.dto.CreateProductRequest;
import ru.yandex.practicum.product.dto.ProductDto;
import ru.yandex.practicum.product.dto.UpdateProductRequest;
import ru.yandex.practicum.product.repository.CategoryRepository;
import ru.yandex.practicum.product.repository.ProductRepository;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:product_contract_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
class ProductServiceContractTest {

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final Statistics statistics;

    @Autowired
    ProductServiceContractTest(MockMvc mvc, ObjectMapper json, ProductRepository productRepository,
                               CategoryRepository categoryRepository, EntityManagerFactory entityManagerFactory) {
        this.mvc = mvc;
        this.json = json;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void clearCatalog() {
        productRepository.deleteAllInBatch();
        categoryRepository.deleteAllInBatch();
        statistics.clear();
    }

    @Test
    void shouldReturnEmptyCatalogAndCategoriesInStableOrder() throws Exception {
        assertThat(products("/api/products")).isEmpty();
        mvc.perform(get("/api/categories")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        CategoryDto first = createCategory("Первая");
        CategoryDto second = createCategory("Вторая");

        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(first.id()))
                .andExpect(jsonPath("$[1].id").value(second.id()));
        assertThat(products("/api/products/category/" + first.id())).isEmpty();
    }

    @Test
    void shouldKeepOptionalCategoryAndExactPriceAndLongImageUrl() throws Exception {
        String imageUrl = "https://example.com/" + "a".repeat(600);
        ProductDto product = createProduct(new CreateProductRequest(
                "Датчик", null, new BigDecimal("12.34567"), null, imageUrl));

        ProductDto stored = product(product.id());

        assertThat(stored.category()).isNull();
        assertThat(stored.price()).isEqualByComparingTo("12.34567");
        assertThat(stored.imageUrl()).isEqualTo(imageUrl);
        assertThat(stored.active()).isTrue();
        assertThat(products("/api/products")).extracting(ProductDto::id).containsExactly(product.id());
    }

    @Test
    void shouldPreserveAllFieldsWhenPatchContainsNullsOrNoFields() throws Exception {
        CategoryDto category = createCategory("Освещение");
        ProductDto original = createProduct(new CreateProductRequest(
                "Лампа", "Описание", new BigDecimal("25.15"), category.id(), "https://example.com/lamp"));

        ProductDto afterNulls = patchProduct(original.id(), new UpdateProductRequest(
                null, null, null, null, null, null));
        MvcResult emptyPatch = mvc.perform(patch("/api/products/{id}", original.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn();

        assertThat(afterNulls).isEqualTo(original);
        assertThat(json.readValue(emptyPatch.getResponse().getContentAsByteArray(), ProductDto.class))
                .isEqualTo(original);
        assertThat(product(original.id())).isEqualTo(original);
    }

    @Test
    void shouldUpdateOnlyRequestedProductAndMoveItToAnotherCategory() throws Exception {
        CategoryDto first = createCategory("Первая");
        CategoryDto second = createCategory("Вторая");
        ProductDto changing = createProduct("Лампа", first.id());
        ProductDto untouched = createProduct("Датчик", first.id());

        ProductDto updated = patchProduct(changing.id(), new UpdateProductRequest(
                "Новая лампа", "Новое описание", new BigDecimal("99.123"), second.id(), "новая ссылка", true));

        assertThat(updated.name()).isEqualTo("Новая лампа");
        assertThat(updated.description()).isEqualTo("Новое описание");
        assertThat(updated.price()).isEqualByComparingTo("99.123");
        assertThat(updated.category()).isEqualTo(second);
        assertThat(updated.imageUrl()).isEqualTo("новая ссылка");
        assertThat(product(untouched.id())).isEqualTo(untouched);
        assertThat(products("/api/products/category/" + first.id()))
                .extracting(ProductDto::id).containsExactly(untouched.id());
        assertThat(products("/api/products/category/" + second.id()))
                .extracting(ProductDto::id).containsExactly(changing.id());
    }

    @Test
    void shouldHideInactiveProductFromAllCatalogListsButKeepItById() throws Exception {
        CategoryDto category = createCategory("Освещение");
        ProductDto hidden = createProduct("Лампа скрытая", category.id());
        ProductDto visible = createProduct("Лампа видимая", category.id());

        patchProduct(hidden.id(), new UpdateProductRequest(null, null, null, null, null, false));

        assertThat(product(hidden.id()).active()).isFalse();
        assertThat(productRepository.count()).isEqualTo(2);
        assertThat(products("/api/products")).extracting(ProductDto::id).containsExactly(visible.id());
        assertThat(products("/api/products/category/" + category.id()))
                .extracting(ProductDto::id).containsExactly(visible.id());
        assertThat(search("Лампа")).extracting(ProductDto::id).containsExactly(visible.id());

        patchProduct(hidden.id(), new UpdateProductRequest(null, null, null, null, null, true));

        assertThat(products("/api/products"))
                .extracting(ProductDto::id).containsExactly(hidden.id(), visible.id());
    }

    @Test
    void shouldSearchLiteralSubstringIgnoringCase() throws Exception {
        ProductDto literal = createProduct("Smart 50%_Lamp", null);
        createProduct("Smart 500XLamp", null);

        assertThat(search("%_lAmP")).extracting(ProductDto::id).containsExactly(literal.id());
        assertThat(search("несуществующий")).isEmpty();
    }

    @Test
    void shouldLoadCategoriesWithoutExtraQueriesForEveryProduct() throws Exception {
        CategoryDto first = createCategory("Освещение");
        CategoryDto second = createCategory("Датчики");
        ProductDto lamp = createProduct("Smart Lamp", first.id());
        ProductDto sensor = createProduct("Smart Sensor", second.id());
        createProduct("Smart Controller", null);

        statistics.clear();
        List<ProductDto> catalog = products("/api/products");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(catalog).extracting(ProductDto::id).isSorted();
        assertThat(catalog.get(0).category()).isEqualTo(first);
        assertThat(catalog.get(1).category()).isEqualTo(second);
        assertThat(catalog.get(2).category()).isNull();

        statistics.clear();
        assertThat(search("Smart")).extracting(ProductDto::id).contains(lamp.id(), sensor.id());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);

        statistics.clear();
        assertThat(products("/api/products/category/" + first.id()))
                .extracting(ProductDto::id).containsExactly(lamp.id());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);

        statistics.clear();
        assertThat(product(sensor.id()).category()).isEqualTo(second);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void shouldReturnNotFoundForMissingResourcesAndKeepProductOnFailedPatch() throws Exception {
        long missingId = Long.MAX_VALUE;
        mvc.perform(get("/api/categories/{id}", missingId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/products/{id}", missingId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/products/category/{id}", missingId)).andExpect(status().isNotFound());
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateProductRequest(
                                "Лампа", null, BigDecimal.ONE, missingId, null))))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/products/{id}", missingId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        ProductDto original = createProduct("Лампа", null);
        mvc.perform(patch("/api/products/{id}", original.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateProductRequest(
                                "Изменённая лампа", null, null, missingId, null, false))))
                .andExpect(status().isNotFound());

        assertThat(product(original.id())).isEqualTo(original);
        assertThat(productRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":\" \"}", "{\"name\":\"Лампа\",\"price\":0}",
            "{\"name\":\"Лампа\",\"price\":-1}", "{\"name\":\"Лампа\",\"price\":0.001}",
            "{\"name\":\"Лампа\",\"price\":\"ошибка\"}", "{", "null"})
    void shouldRejectInvalidProductPayload(String body) throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("message").isNotEmpty());
        assertThat(productRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\"\"}", "{\"name\":\"  \"}", "{\"price\":0}",
            "{\"price\":-1}", "{\"price\":0.001}", "{\"active\":{}}", "{"})
    void shouldRejectInvalidPatchWithoutChangingProduct(String body) throws Exception {
        ProductDto original = createProduct("Лампа", null);

        mvc.perform(patch("/api/products/{id}", original.id())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertThat(product(original.id())).isEqualTo(original);
    }

    @Test
    void shouldRejectInvalidCategoryAndMalformedRequestParameters() throws Exception {
        mvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateCategoryRequest(" ", null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("validationErrors.name").exists());
        mvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateCategoryRequest("a".repeat(256), null))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/search")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/categories/invalid")).andExpect(status().isBadRequest());
        assertThat(categoryRepository.count()).isZero();
    }

    private CategoryDto createCategory(String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateCategoryRequest(name, null))))
                .andExpect(status().isCreated()).andReturn();
        return json.readValue(result.getResponse().getContentAsByteArray(), CategoryDto.class);
    }

    private ProductDto createProduct(String name, Long categoryId) throws Exception {
        return createProduct(new CreateProductRequest(name, null, BigDecimal.ONE, categoryId, null));
    }

    private ProductDto createProduct(CreateProductRequest request) throws Exception {
        MvcResult result = mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn();
        return json.readValue(result.getResponse().getContentAsByteArray(), ProductDto.class);
    }

    private ProductDto patchProduct(Long id, UpdateProductRequest request) throws Exception {
        MvcResult result = mvc.perform(patch("/api/products/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn();
        return json.readValue(result.getResponse().getContentAsByteArray(), ProductDto.class);
    }

    private ProductDto product(Long id) throws Exception {
        MvcResult result = mvc.perform(get("/api/products/{id}", id)).andExpect(status().isOk()).andReturn();
        return json.readValue(result.getResponse().getContentAsByteArray(), ProductDto.class);
    }

    private List<ProductDto> products(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return readProducts(result);
    }

    private List<ProductDto> search(String query) throws Exception {
        MvcResult result = mvc.perform(get("/api/products/search").param("query", query))
                .andExpect(status().isOk()).andReturn();
        return readProducts(result);
    }

    private List<ProductDto> readProducts(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() {
        });
    }
}
