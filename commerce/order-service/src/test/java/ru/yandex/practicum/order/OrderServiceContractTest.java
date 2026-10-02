package ru.yandex.practicum.order;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Response;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.yandex.practicum.order.client.InventoryClient;
import ru.yandex.practicum.order.client.InventoryClientFallbackFactory;
import ru.yandex.practicum.order.client.ProductClient;
import ru.yandex.practicum.order.client.ProductClientFallbackFactory;
import ru.yandex.practicum.order.client.dto.InventoryRequest;
import ru.yandex.practicum.order.client.dto.ProductResponse;
import ru.yandex.practicum.order.client.dto.ReserveResponse;
import ru.yandex.practicum.order.dto.CreateOrderRequest;
import ru.yandex.practicum.order.dto.OrderDto;
import ru.yandex.practicum.order.dto.OrderItemDto;
import ru.yandex.practicum.order.dto.OrderItemRequest;
import ru.yandex.practicum.order.entity.Order;
import ru.yandex.practicum.order.entity.OrderStatus;
import ru.yandex.practicum.order.mapper.OrderMapper;
import ru.yandex.practicum.order.repository.OrderRepository;
import ru.yandex.practicum.order.service.OrderService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
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
@SpyBean(OrderMapper.class)
@MockBean({ProductClient.class, InventoryClient.class})
class OrderServiceContractTest {

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final JdbcTemplate jdbc;
    private final Statistics statistics;
    private final ProductClient productClient;
    private final InventoryClient inventoryClient;
    private final RequestInterceptor requestInterceptor;

    @Autowired
    OrderServiceContractTest(MockMvc mvc, ObjectMapper json, OrderRepository orderRepository,
                             OrderService orderService, OrderMapper orderMapper, JdbcTemplate jdbc,
                             EntityManagerFactory entityManagerFactory, ProductClient productClient,
                             InventoryClient inventoryClient, RequestInterceptor requestInterceptor) {
        this.mvc = mvc;
        this.json = json;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.orderMapper = orderMapper;
        this.jdbc = jdbc;
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        this.productClient = productClient;
        this.inventoryClient = inventoryClient;
        this.requestInterceptor = requestInterceptor;
    }

    @BeforeEach
    void clearOrders() {
        orderRepository.deleteAllInBatch();
        statistics.clear();
        when(productClient.findById(anyLong())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new ProductResponse(invocation.getArgument(0), "Товар из каталога", BigDecimal.ONE, true);
        });
        when(inventoryClient.reserve(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new ReserveResponse(true, 0, "Резерв создан");
        });
        when(inventoryClient.release(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new ReserveResponse(true, 1, "Резерв снят");
        });
    }

    @Test
    void shouldCreateConfirmedOrderUsingOnlyProductIdAndQuantity() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
                {"customerName":"Покупатель","customerEmail":"buyer@example.com",
                 "items":[{"productId":1,"quantity":2}]}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("status").value("CONFIRMED"))
                .andExpect(jsonPath("items[0].productName").value("Товар из каталога"))
                .andExpect(jsonPath("totalPrice").value(2));
    }

    @Test
    void shouldIgnoreClientProductNameAndPrice() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
                {"customerName":"Покупатель","customerEmail":"buyer@example.com",
                 "items":[{"productId":1,"quantity":2,"productName":"Подменённое название","price":0.01}]}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("items[0].productName").value("Товар из каталога"))
                .andExpect(jsonPath("items[0].price").value(1))
                .andExpect(jsonPath("totalPrice").value(2));
    }

    @Test
    void shouldRejectQuantityOverflowBeforeCallingOtherServices() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
                {"customerName":"Покупатель","customerEmail":"buyer@example.com",
                 "items":[{"productId":1,"quantity":2147483647},{"productId":1,"quantity":1}]}
                """))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(productClient, inventoryClient);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void shouldRejectInactiveProductWithoutCreatingReservations() throws Exception {
        when(productClient.findById(1L)).thenReturn(new ProductResponse(1L, "Лампа", BigDecimal.ONE, false));

        postSingleItem().andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("message").value("Товар с id 1 снят с продажи"));

        verifyNoInteractions(inventoryClient);
        assertThat(orderRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500, 503})
    void shouldDistinguishCatalogBusinessErrorsFromDegradation(int remoteStatus) throws Exception {
        when(productClient.findById(1L)).thenAnswer(invocation ->
                new ProductClientFallbackFactory().create(remoteFailure(remoteStatus)).findById(1L));

        MvcResult result;
        if (remoteStatus == 404) {
            result = postSingleItem().andExpect(status().isUnprocessableEntity()).andReturn();
            verifyNoInteractions(inventoryClient);
            assertThat(orderRepository.count()).isZero();
        } else {
            result = postSingleItem().andExpect(status().isCreated())
                    .andExpect(jsonPath("status").value("PENDING_CONFIRMATION"))
                    .andExpect(jsonPath("statusDetails").isNotEmpty())
                    .andExpect(jsonPath("items[0].productId").value(1))
                    .andExpect(jsonPath("items[0].productName").value("Товар #1 (ожидает проверки)"))
                    .andExpect(jsonPath("items[0].price").value(0))
                    .andExpect(jsonPath("totalPrice").value(0)).andReturn();
            assertThat(orderRepository.count()).isEqualTo(1);
        }

        assertThat(result.getResponse().getContentAsString()).doesNotContain("internal-sensitive-detail");
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 500, 503})
    void shouldDistinguishReservationBusinessErrorsFromDegradation(int remoteStatus) throws Exception {
        when(inventoryClient.reserve(any())).thenAnswer(invocation ->
                new InventoryClientFallbackFactory().create(remoteFailure(remoteStatus))
                        .reserve(invocation.getArgument(0)));

        if (remoteStatus == 404 || remoteStatus == 409) {
            postSingleItem().andExpect(status().isUnprocessableEntity());
            assertThat(orderRepository.count()).isZero();
        } else {
            postSingleItem().andExpect(status().isCreated())
                    .andExpect(jsonPath("status").value("PENDING_CONFIRMATION"))
                    .andExpect(jsonPath("statusDetails").isNotEmpty())
                    .andExpect(jsonPath("items[0].price").value(1));
            assertThat(orderRepository.count()).isEqualTo(1);
        }

        verify(inventoryClient, times(0)).release(any());
    }

    @Test
    void shouldRejectIncompleteCatalogResponseWithoutCreatingReservation() throws Exception {
        when(productClient.findById(1L)).thenReturn(new ProductResponse(2L, null, null, null));

        postSingleItem().andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(inventoryClient);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void shouldRejectUnconfirmedReservation() throws Exception {
        when(inventoryClient.reserve(any())).thenReturn(new ReserveResponse(false, 0, "internal-sensitive-detail"));

        MvcResult result = postSingleItem().andExpect(status().isUnprocessableEntity()).andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("internal-sensitive-detail");
        assertThat(orderRepository.count()).isZero();
        verify(inventoryClient, times(0)).release(any());
    }

    @Test
    void shouldReleaseOnlySuccessfulReservationsWhenNextReservationFails() throws Exception {
        when(inventoryClient.reserve(new InventoryRequest(2L, 1))).thenThrow(remoteFailure(409));
        CreateOrderRequest request = new CreateOrderRequest("Покупатель", "buyer@example.com", List.of(
                new OrderItemRequest(1L, 2), new OrderItemRequest(2L, 1)));

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("message").value("Склад отклонил резерв товара с id 2: "
                        + "недостаточный остаток или конфликт одновременных изменений"));

        verify(inventoryClient).release(new InventoryRequest(1L, 2));
        verify(inventoryClient, times(0)).release(new InventoryRequest(2L, 1));
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldContinueCompensationAndKeepOriginalErrorWhenReleaseFails(CapturedOutput output) throws Exception {
        when(inventoryClient.reserve(new InventoryRequest(3L, 1))).thenThrow(remoteFailure(409));
        when(inventoryClient.release(new InventoryRequest(1L, 2))).thenThrow(remoteFailure(503));
        CreateOrderRequest request = new CreateOrderRequest("Покупатель", "buyer@example.com", List.of(
                new OrderItemRequest(1L, 2), new OrderItemRequest(2L, 1), new OrderItemRequest(3L, 1)));

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("message").value("Склад отклонил резерв товара с id 3: "
                        + "недостаточный остаток или конфликт одновременных изменений"));

        verify(inventoryClient).release(new InventoryRequest(1L, 2));
        verify(inventoryClient).release(new InventoryRequest(2L, 1));
        verify(inventoryClient, times(0)).release(new InventoryRequest(3L, 1));
        assertThat(orderRepository.count()).isZero();
        assertThat(output.getAll()).contains("Не удалось снять резерв товара с id 1, количество 2")
                .doesNotContain("internal-sensitive-detail");
    }

    @Test
    void shouldSendSourceServiceHeader() {
        RequestTemplate template = new RequestTemplate();

        requestInterceptor.apply(template);

        assertThat(template.headers()).containsEntry("X-Source-Service", List.of("order-service"));
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
        when(productClient.findById(Long.MAX_VALUE)).thenReturn(
                new ProductResponse(Long.MAX_VALUE, longName, price, true));
        CreateOrderRequest request = new CreateOrderRequest(longName, "buyer@example.com", List.of(
                new OrderItemRequest(Long.MAX_VALUE, Integer.MAX_VALUE - 3),
                new OrderItemRequest(Long.MAX_VALUE, 3)));
        BigDecimal total = price.multiply(BigDecimal.valueOf(Integer.MAX_VALUE));

        OrderDto created = create(request);
        OrderDto stored = byId(created.id());

        assertThat(created.totalPrice()).isEqualByComparingTo(total);
        assertThat(stored.totalPrice()).isEqualByComparingTo(total);
        assertThat(stored.customerName()).isEqualTo(longName);
        assertThat(stored.customerEmail()).isEqualTo(request.customerEmail());
        assertThat(stored.status()).isEqualTo("CONFIRMED");
        assertThat(stored.statusDetails()).isNull();
        assertThat(stored.createdAt()).isNotNull().isEqualTo(created.createdAt());
        assertThat(stored.items()).extracting(OrderItemDto::id).doesNotContainNull().isSorted();
        assertThat(stored.items()).extracting(OrderItemDto::productId)
                .containsExactly(Long.MAX_VALUE, Long.MAX_VALUE);
        assertThat(stored.items().get(0).productName()).isEqualTo(longName);
        assertThat(stored.items().get(0).price()).isEqualByComparingTo(price);
        assertThat(stored.items().get(0).quantity()).isEqualTo(Integer.MAX_VALUE - 3);
        assertThat(stored.items().get(1).price()).isEqualByComparingTo(price);
        verify(productClient, times(1)).findById(Long.MAX_VALUE);
        verify(inventoryClient, times(1)).reserve(new InventoryRequest(Long.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void shouldStoreStatusAsTextAndLoadItAsEnum() throws Exception {
        OrderDto created = create("buyer@example.com", List.of(item(1L, "Лампа", 1, "1")));

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, created.id()))
                .isEqualTo("CONFIRMED");
        OrderStatus storedStatus = orderRepository.findById(created.id()).orElseThrow().getStatus();
        assertThat(storedStatus).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(byId(created.id()).status()).isEqualTo("CONFIRMED");
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
            "[{\"productId\":null,\"quantity\":1}]",
            "[{\"productId\":1,\"productName\":\"Лампа\",\"quantity\":0,\"price\":1}]",
            "[{\"productId\":1,\"productName\":\"Лампа\",\"quantity\":-1,\"price\":1}]",
            "[{\"productId\":1,\"productName\":\"Лампа\",\"quantity\":null,\"price\":1}]"})
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
                    + "\"items\":[{\"productId\":1,\"quantity\":2147483648}]}",
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
        verify(inventoryClient).release(new InventoryRequest(2L, 1));
        verify(inventoryClient).release(new InventoryRequest(3L, 0));
    }

    @Test
    void shouldRollBackOrderAndItemsWhenResponseMappingFails() throws Exception {
        OrderDto original = create("buyer@example.com", List.of(item(1L, "Лампа", 1, "1")));
        CreateOrderRequest request = new CreateOrderRequest("Покупатель", "other@example.com", List.of(
                item(2L, "Розетка", 1, "2"), item(3L, "Датчик", 2, "3")));
        IllegalStateException mappingFailure = new IllegalStateException("Ошибка построения ответа заказа");
        doAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            assertThat(saved.getId()).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE id = ?", Long.class, saved.getId()))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ?",
                    Long.class, saved.getId())).isEqualTo(2);
            throw mappingFailure;
        }).when(orderMapper).toDto(any(Order.class));

        try {
            assertThatThrownBy(() -> orderService.create(request)).isSameAs(mappingFailure);
        } finally {
            doCallRealMethod().when(orderMapper).toDto(any(Order.class));
        }

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(itemCount()).isEqualTo(1);
        assertThat(byId(original.id())).isEqualTo(original);
        assertThat(byEmail("other@example.com")).isEmpty();
        verify(inventoryClient).release(new InventoryRequest(2L, 1));
        verify(inventoryClient).release(new InventoryRequest(3L, 2));
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
        when(productClient.findById(productId)).thenReturn(
                new ProductResponse(productId, name, new BigDecimal(price), true));
        return new OrderItemRequest(productId, quantity);
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

    private org.springframework.test.web.servlet.ResultActions postSingleItem() throws Exception {
        return mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
                {"customerName":"Покупатель","customerEmail":"buyer@example.com",
                 "items":[{"productId":1,"quantity":1}]}
                """));
    }

    private FeignException remoteFailure(int remoteStatus) {
        Request request = Request.create(Request.HttpMethod.POST, "http://internal-service/api", Map.of(),
                new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        return FeignException.errorStatus("test-call", Response.builder().request(request)
                .status(remoteStatus).reason("Ошибка сервиса")
                .body("internal-sensitive-detail", StandardCharsets.UTF_8).build());
    }
}
