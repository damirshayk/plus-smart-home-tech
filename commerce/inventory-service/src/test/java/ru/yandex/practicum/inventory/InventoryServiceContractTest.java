package ru.yandex.practicum.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.yandex.practicum.inventory.dto.InventoryDto;
import ru.yandex.practicum.inventory.dto.ReserveRequest;
import ru.yandex.practicum.inventory.dto.UpdateInventoryRequest;
import ru.yandex.practicum.inventory.entity.Inventory;
import ru.yandex.practicum.inventory.repository.InventoryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:inventory_contract_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class InventoryServiceContractTest {

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final InventoryRepository inventoryRepository;

    @Autowired
    InventoryServiceContractTest(MockMvc mvc, ObjectMapper json, InventoryRepository inventoryRepository) {
        this.mvc = mvc;
        this.json = json;
        this.inventoryRepository = inventoryRepository;
    }

    @BeforeEach
    void clearInventory() {
        inventoryRepository.deleteAllInBatch();
    }

    @Test
    void shouldReturnEmptyInventoryAndRecordsInIdOrder() throws Exception {
        mvc.perform(get("/api/inventory"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        InventoryDto first = createInventory(102L, 3);
        InventoryDto second = createInventory(101L, 7);

        mvc.perform(get("/api/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.id()))
                .andExpect(jsonPath("$[0].productId").value(102))
                .andExpect(jsonPath("$[0].availableQuantity").value(3))
                .andExpect(jsonPath("$[1].id").value(second.id()))
                .andExpect(jsonPath("$[1].productId").value(101))
                .andExpect(jsonPath("$[1].availableQuantity").value(7));
    }

    @Test
    void shouldRejectDuplicateWithoutChangingExistingRecord() throws Exception {
        InventoryDto original = createInventory(101L, 5);

        mvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateInventoryRequest(101L, 9))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("status").value(409))
                .andExpect(jsonPath("message").isNotEmpty());

        assertThat(inventory(101L)).isEqualTo(original);
        assertThat(inventoryRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnNotFoundWithoutCreatingRecord() throws Exception {
        mvc.perform(get("/api/inventory/{productId}", Long.MAX_VALUE))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/inventory").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateInventoryRequest(Long.MAX_VALUE, 5))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/inventory/reserve").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReserveRequest(Long.MAX_VALUE, 1))))
                .andExpect(status().isNotFound());

        assertThat(inventoryRepository.count()).isZero();
    }

    @Test
    void shouldReplaceQuantityAndKeepReservationsAndOtherRecords() throws Exception {
        createInventory(101L, 10);
        InventoryDto untouched = createInventory(102L, 7);
        reserve(101L, 4);

        mvc.perform(put("/api/inventory").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateInventoryRequest(101L, 4))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("quantity").value(4))
                .andExpect(jsonPath("reservedQuantity").value(4))
                .andExpect(jsonPath("availableQuantity").value(0));

        assertThat(inventory(101L).quantity()).isEqualTo(4);
        assertThat(inventory(102L)).isEqualTo(untouched);
    }

    @Test
    void shouldKeepInventoryWhenUpdatedQuantityIsLessThanReserved() throws Exception {
        createInventory(101L, 10);
        reserve(101L, 4);
        InventoryDto original = inventory(101L);

        mvc.perform(put("/api/inventory").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateInventoryRequest(101L, 3))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("message").isNotEmpty());

        assertThat(inventory(101L)).isEqualTo(original);
    }

    @Test
    void shouldReserveRemainingStockAndLeaveFailedReservationUnchanged() throws Exception {
        createInventory(101L, 5);
        reserve(101L, 3);

        mvc.perform(post("/api/inventory/reserve").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReserveRequest(101L, 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("success").value(true))
                .andExpect(jsonPath("availableQuantity").value(0))
                .andExpect(jsonPath("message").isNotEmpty());
        InventoryDto original = inventory(101L);

        mvc.perform(post("/api/inventory/reserve").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReserveRequest(101L, 1))))
                .andExpect(status().isConflict());

        assertThat(original.quantity()).isEqualTo(5);
        assertThat(original.reservedQuantity()).isEqualTo(5);
        assertThat(inventory(101L)).isEqualTo(original);
    }

    @Test
    void shouldAcceptZeroStockAndReserveMaximumIntegerWithoutOverflow() throws Exception {
        InventoryDto empty = createInventory(101L, 0);
        assertThat(empty.availableQuantity()).isZero();
        createInventory(102L, Integer.MAX_VALUE);
        reserve(102L, Integer.MAX_VALUE - 1);
        reserve(102L, 1);

        InventoryDto full = inventory(102L);
        assertThat(full.quantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(full.reservedQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(full.availableQuantity()).isZero();
        assertThat(inventory(101L)).isEqualTo(empty);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"productId\":101}", "{\"productId\":101,\"quantity\":-1}",
            "{\"productId\":101,\"quantity\":2147483648}", "{\"productId\":\"ошибка\",\"quantity\":1}",
            "{", "null"})
    void shouldRejectInvalidCreateAndUpdatePayloads(String body) throws Exception {
        mvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("status").value(400));
        mvc.perform(put("/api/inventory").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("status").value(400));

        assertThat(inventoryRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"productId\":101}", "{\"quantity\":1}",
            "{\"productId\":101,\"quantity\":0}", "{\"productId\":101,\"quantity\":-1}",
            "{\"productId\":101,\"quantity\":\"ошибка\"}", "{", "null"})
    void shouldRejectInvalidReservationWithoutChangingStock(String body) throws Exception {
        InventoryDto original = createInventory(101L, 5);

        mvc.perform(post("/api/inventory/reserve").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("status").value(400));

        assertThat(inventory(101L)).isEqualTo(original);
    }

    @Test
    void shouldRejectMalformedProductId() throws Exception {
        mvc.perform(get("/api/inventory/invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("status").value(400))
                .andExpect(jsonPath("message").isNotEmpty());
    }

    @Test
    void shouldRejectStaleVersionWithoutOverwritingCommittedReservation() throws Exception {
        createInventory(101L, 5);
        Inventory first = inventoryRepository.findByProductId(101L).orElseThrow();
        Inventory stale = inventoryRepository.findByProductId(101L).orElseThrow();
        Long initialVersion = first.getVersion();
        first.setReservedQuantity(3);
        stale.setReservedQuantity(4);

        inventoryRepository.saveAndFlush(first);

        assertThatThrownBy(() -> inventoryRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        Inventory stored = inventoryRepository.findByProductId(101L).orElseThrow();
        assertThat(stored.getVersion()).isEqualTo(initialVersion + 1);
        assertThat(stored.getReservedQuantity()).isEqualTo(3);
        assertThat(stored.getAvailableQuantity()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"-1, 0", "1, -1", "1, 2"})
    void shouldEnforceStockConstraintsInDatabase(int quantity, int reservedQuantity) {
        Inventory invalid = new Inventory();
        invalid.setProductId(101L);
        invalid.setQuantity(quantity);
        invalid.setReservedQuantity(reservedQuantity);

        assertThatThrownBy(() -> inventoryRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(inventoryRepository.count()).isZero();
    }

    private InventoryDto createInventory(long productId, int quantity) throws Exception {
        MvcResult result = mvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateInventoryRequest(productId, quantity))))
                .andExpect(status().isCreated()).andReturn();
        return json.readValue(result.getResponse().getContentAsByteArray(), InventoryDto.class);
    }

    private InventoryDto inventory(long productId) throws Exception {
        MvcResult result = mvc.perform(get("/api/inventory/{productId}", productId))
                .andExpect(status().isOk()).andReturn();
        return json.readValue(result.getResponse().getContentAsByteArray(), InventoryDto.class);
    }

    private void reserve(long productId, int quantity) throws Exception {
        mvc.perform(post("/api/inventory/reserve").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReserveRequest(productId, quantity))))
                .andExpect(status().isOk()).andExpect(jsonPath("success").value(true));
    }
}
