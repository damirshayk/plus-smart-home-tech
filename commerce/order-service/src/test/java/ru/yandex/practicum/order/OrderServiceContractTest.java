package ru.yandex.practicum.order;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.yandex.practicum.order.dto.CreateOrderRequest;
import ru.yandex.practicum.order.dto.OrderDto;
import ru.yandex.practicum.order.dto.OrderItemDto;
import ru.yandex.practicum.order.dto.OrderItemRequest;
import ru.yandex.practicum.order.repository.OrderRepository;
import ru.yandex.practicum.order.service.OrderService;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order_contract_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
class OrderServiceContractTest {

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final JdbcTemplate jdbc;
    private final Statistics statistics;

    @Autowired
    OrderServiceContractTest(MockMvc mvc, ObjectMapper json, OrderRepository orderRepository,
                             OrderService orderService, JdbcTemplate jdbc,
                             EntityManagerFactory entityManagerFactory) {
        this.mvc = mvc;
        this.json = json;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.jdbc = jdbc;
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void clearOrders() {
        orderRepository.deleteAllInBatch();
        statistics.clear();
    }

    @Test
    void shouldReturnEmptyListsAndKeepEachCustomersOrdersAndItemsSeparate() throws Exception {
        assertThat(orders()).isEmpty();
        assertThat(byEmail("missing@example.com")).isEmpty();
        OrderDto first = create("buyer@example.com", List.of(
                item(21L, "Лампа", 2, "12.34"), item(22L, "Розетка", 1, "56.78")));
        OrderDto second = create("other@example.com", List.of(item(31L, "Датчик", 3, "1.23")));
        OrderDto third = create("buyer@example.com", List.of(item(41L, "Выключатель", 1, "5")));

        List<OrderDto> all = orders();

        assertThat(all).extracting(OrderDto::id).containsExactly(first.id(), second.id(), third.id());
        assertThat(all.get(0).items()).isEqualTo(first.items());
        assertThat(all.get(1).items()).isEqualTo(second.items());
        assertThat(all.get(2).items()).isEqualTo(third.items());
        assertThat(byId(first.id()).items()).isEqualTo(first.items());
        assertThat(byEmail("buyer@example.com")).extracting(OrderDto::id)
                .containsExactly(first.id(), third.id());
        assertThat(byEmail("BUYER@example.com")).isEmpty();
        assertThat(byEmail("buyer@example.com ")).isEmpty();
        assertThat(byEmail("")).isEmpty();
    }

    @Test
    void shouldStoreDuplicateProductSnapshotsAndExactDecimalsWithoutOverflow() throws Exception {
        String longName = "Название ".repeat(100);
        BigDecimal price = new BigDecimal("12345678901234567890.123456789");
        CreateOrderRequest request = new CreateOrderRequest(longName, "buyer@example.com", List.of(
                new OrderItemRequest(Long.MAX_VALUE, longName, Integer.MAX_VALUE, price),
                item(Long.MAX_VALUE, "Другой снимок того же товара", 3, "0.0123456789")));
        BigDecimal total = price.multiply(BigDecimal.valueOf(Integer.MAX_VALUE))
                .add(new BigDecimal("0.0370370367"));

        OrderDto created = create(request);
        OrderDto stored = byId(created.id());

        assertThat(created.totalPrice()).isEqualByComparingTo(total);
        assertThat(stored.totalPrice()).isEqualByComparingTo(total);
        assertThat(stored.customerName()).isEqualTo(longName);
        assertThat(stored.customerEmail()).isEqualTo(request.customerEmail());
        assertThat(stored.status()).isEqualTo("CREATED");
        assertThat(stored.statusDetails()).isNull();
        assertThat(stored.createdAt()).isNotNull().isEqualTo(created.createdAt());
        assertThat(stored.items()).extracting(OrderItemDto::id).doesNotContainNull().isSorted();
        assertThat(stored.items()).extracting(OrderItemDto::productId)
                .containsExactly(Long.MAX_VALUE, Long.MAX_VALUE);
        assertThat(stored.items().get(0).productName()).isEqualTo(longName);
        assertThat(stored.items().get(0).price()).isEqualByComparingTo(price);
        assertThat(stored.items().get(0).quantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(stored.items().get(1).price()).isEqualByComparingTo("0.0123456789");
    }

    @Test
    void shouldLoadItemsWithOneQueryRegardlessOfOrderCount() throws Exception {
        OrderDto first = create("buyer@example.com", List.of(item(1L, "Лампа", 1, "1")));
        statistics.clear();
        assertThat(orders()).hasSize(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        create("buyer@example.com", List.of(item(2L, "Розетка", 2, "2"), item(3L, "Датчик", 3, "3")));
        create("other@example.com", List.of(item(4L, "Выключатель", 4, "4")));

        statistics.clear();
        assertThat(orders()).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);

        statistics.clear();
        assertThat(byEmail("buyer@example.com")).hasSize(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);

        statistics.clear();
        assertThat(byId(first.id()).items()).hasSize(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[null]", "[{}]",
            "[{\"productName\":\"Лампа\",\"quantity\":1,\"price\":1}]",
            "[{\"productId\":1,\"productName\":\" \",\"quantity\":1,\"price\":1}]",
            "[{\"productId\":1,\"productName\":\"Лампа\",\"quantity\":0,\"price\":1}]",
            "[{\"productId\":1,\"productName\":\"Лампа\",\"quantity\":-1,\"price\":1}]",
            "[{\"productId\":1,\"productName\":\"Лампа\",\"quantity\":null,\"price\":1}]",
            "[{\"productId\":1,\"productName\":\"Лампа\",\"quantity\":1,\"price\":null}]",
            "[{\"productId\":1,\"productName\":\"Лампа\",\"quantity\":1,\"price\":0}]",
            "[{\"productId\":1,\"productName\":\"Лампа\",\"quantity\":1,\"price\":0.001}]"})
    void shouldRejectInvalidItemsWithoutPersistingOrder(String items) throws Exception {
        String body = """
                {"customerName":"Покупатель","customerEmail":"buyer@example.com","items":%s}
                """.formatted(items);

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("validationErrors").isNotEmpty());
        assertThat(orderRepository.count()).isZero();
        assertThat(itemCount()).isZero();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void shouldRejectInvalidCustomerNameWithoutPersistingOrder(String customerName) throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(customerName, "buyer@example.com",
                List.of(item(1L, "Лампа", 1, "1")));

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("validationErrors.customerName").isNotEmpty())
                .andExpect(jsonPath("validationErrors.customerEmail").doesNotExist());
        assertThat(orderRepository.count()).isZero();
        assertThat(itemCount()).isZero();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-an-email"})
    void shouldRejectInvalidCustomerEmailWithoutPersistingOrder(String customerEmail) throws Exception {
        CreateOrderRequest request = new CreateOrderRequest("Покупатель", customerEmail,
                List.of(item(1L, "Лампа", 1, "1")));

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("validationErrors.customerEmail").isNotEmpty())
                .andExpect(jsonPath("validationErrors.customerName").doesNotExist());
        assertThat(orderRepository.count()).isZero();
        assertThat(itemCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "null",
            "{\"customerName\":\"Покупатель\",\"customerEmail\":\"buyer@example.com\","
                    + "\"items\":[{\"productId\":\"ошибка\",\"productName\":\"Лампа\",\"quantity\":1,\"price\":1}]}"})
    void shouldRejectInvalidOrderAndMalformedJson(String body) throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("message").isNotEmpty());
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void shouldReturnMethodNotAllowedWithSupportedMethods() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest("Покупатель", "buyer@example.com",
                List.of(item(1L, "Лампа", 1, "1")));

        MvcResult result = mvc.perform(delete("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("status").value(405))
                .andExpect(jsonPath("message").isNotEmpty())
                .andReturn();

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(HttpHeaders.ALLOW, result.getResponse().getHeaders(HttpHeaders.ALLOW));
        assertThat(headers.getAllow()).contains(HttpMethod.GET, HttpMethod.POST);
        assertThat(orderRepository.count()).isZero();
        assertThat(itemCount()).isZero();
    }

    @Test
    void shouldReturnUnsupportedMediaTypeWithAcceptedTypes() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest("Покупатель", "buyer@example.com",
                List.of(item(1L, "Лампа", 1, "1")));

        MvcResult result = mvc.perform(post("/api/orders").contentType(MediaType.TEXT_PLAIN)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("status").value(415))
                .andExpect(jsonPath("message").isNotEmpty())
                .andReturn();

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(HttpHeaders.ACCEPT, result.getResponse().getHeaders(HttpHeaders.ACCEPT));
        assertThat(headers.getAccept()).anyMatch(MediaType.APPLICATION_JSON::isCompatibleWith);
        assertThat(orderRepository.count()).isZero();
        assertThat(itemCount()).isZero();
    }

    @Test
    void shouldReturnNotFoundAndRejectMalformedIdAndMissingEmail() throws Exception {
        mvc.perform(get("/api/orders/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound()).andExpect(jsonPath("status").value(404));
        mvc.perform(get("/api/orders/invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("status").value(400));
        mvc.perform(get("/api/orders/by-email"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("status").value(400));
    }

    @Test
    void shouldRollBackWholeOrderIfOneItemViolatesDatabaseConstraint() throws Exception {
        OrderDto original = create("buyer@example.com", List.of(item(1L, "Лампа", 1, "1")));
        CreateOrderRequest invalid = new CreateOrderRequest("Покупатель", "other@example.com", List.of(
                item(2L, "Розетка", 1, "2"), item(3L, "Датчик", 0, "3")));

        assertThatThrownBy(() -> orderService.create(invalid)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(itemCount()).isEqualTo(1);
        assertThat(byId(original.id()).items()).isEqualTo(original.items());
        assertThat(byEmail("other@example.com")).isEmpty();
    }

    @Test
    void shouldCascadeDeletionOnlyToItemsOfDeletedOrder() throws Exception {
        OrderDto deleted = create("buyer@example.com", List.of(item(1L, "Лампа", 1, "1")));
        OrderDto retained = create("other@example.com", List.of(item(2L, "Розетка", 2, "2")));

        orderRepository.deleteAllByIdInBatch(List.of(deleted.id()));

        assertThat(orders()).extracting(OrderDto::id).containsExactly(retained.id());
        assertThat(byId(retained.id()).items()).isEqualTo(retained.items());
        assertThat(itemCount()).isEqualTo(1);
    }

    private OrderItemRequest item(long productId, String name, int quantity, String price) {
        return new OrderItemRequest(productId, name, quantity, new BigDecimal(price));
    }

    private OrderDto create(String email, List<OrderItemRequest> items) throws Exception {
        return create(new CreateOrderRequest("Покупатель", email, items));
    }

    private OrderDto create(CreateOrderRequest request) throws Exception {
        MvcResult result = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn();
        return json.readValue(result.getResponse().getContentAsByteArray(), OrderDto.class);
    }

    private OrderDto byId(long id) throws Exception {
        MvcResult result = mvc.perform(get("/api/orders/{id}", id)).andExpect(status().isOk()).andReturn();
        return json.readValue(result.getResponse().getContentAsByteArray(), OrderDto.class);
    }

    private List<OrderDto> orders() throws Exception {
        MvcResult result = mvc.perform(get("/api/orders")).andExpect(status().isOk()).andReturn();
        return readOrders(result);
    }

    private List<OrderDto> byEmail(String email) throws Exception {
        MvcResult result = mvc.perform(get("/api/orders/by-email").param("email", email))
                .andExpect(status().isOk()).andReturn();
        return readOrders(result);
    }

    private List<OrderDto> readOrders(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() {
        });
    }

    private long itemCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Long.class);
    }
}
