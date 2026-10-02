package ru.yandex.practicum.order.service;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.yandex.practicum.order.client.InventoryClient;
import ru.yandex.practicum.order.client.ProductClient;
import ru.yandex.practicum.order.client.dto.InventoryRequest;
import ru.yandex.practicum.order.client.dto.ProductResponse;
import ru.yandex.practicum.order.client.dto.ReserveResponse;
import ru.yandex.practicum.order.dto.CreateOrderRequest;
import ru.yandex.practicum.order.dto.OrderDto;
import ru.yandex.practicum.order.entity.Order;
import ru.yandex.practicum.order.entity.OrderStatus;
import ru.yandex.practicum.order.exception.InventoryServiceUnavailableException;
import ru.yandex.practicum.order.exception.NotFoundException;
import ru.yandex.practicum.order.exception.OrderProcessingException;
import ru.yandex.practicum.order.exception.ProductServiceUnavailableException;
import ru.yandex.practicum.order.mapper.OrderMapper;
import ru.yandex.practicum.order.repository.OrderRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final ProductClient productClient;
    private final InventoryClient inventoryClient;
    private final OrderPersistenceService orderPersistenceService;

    public OrderDto create(CreateOrderRequest request) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        try {
            request.items().forEach(item -> quantities.merge(item.productId(), item.quantity(), Math::addExact));
        } catch (ArithmeticException e) {
            throw new OrderProcessingException("Суммарное количество одного товара превышает допустимое", e);
        }
        Map<Long, ProductResponse> products = new LinkedHashMap<>();
        Set<String> degradationReasons = new LinkedHashSet<>();
        for (Long id : quantities.keySet()) {
            try {
                products.put(id, loadProduct(id));
            } catch (ProductServiceUnavailableException e) {
                products.put(id, new ProductResponse(id, "Товар #" + id + " (ожидает проверки)",
                        BigDecimal.ZERO, null));
                degradationReasons.add("Каталог недоступен для товара #" + id);
            }
        }
        List<InventoryRequest> reservations = new ArrayList<>();
        try {
            for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
                InventoryRequest reservation = new InventoryRequest(entry.getKey(), entry.getValue());
                try {
                    reserve(reservation);
                    reservations.add(reservation);
                } catch (InventoryServiceUnavailableException e) {
                    degradationReasons.add("Резерв товара #" + entry.getKey() + " не подтверждён: склад недоступен");
                }
            }
            Order order = orderMapper.toEntity(request, products);
            order.setStatus(degradationReasons.isEmpty() ? OrderStatus.CONFIRMED : OrderStatus.PENDING_CONFIRMATION);
            if (!degradationReasons.isEmpty()) {
                order.setStatusDetails("Требуется ручная проверка заказа. " + String.join("; ", degradationReasons));
            }
            order.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
            order.setTotalPrice(order.getItems().stream()
                    .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            return orderPersistenceService.save(order);
        } catch (RuntimeException e) {
            releaseReservations(reservations, e);
            throw e;
        }
    }

    private ProductResponse loadProduct(Long id) {
        ProductResponse product;
        try {
            product = productClient.findById(id);
        } catch (FeignException e) {
            String message = e.status() == 404 ? "Товар с id " + id + " не найден"
                    : "Не удалось получить товар с id " + id + " для заказа";
            throw new OrderProcessingException(message, e);
        }
        if (product == null || !id.equals(product.id()) || product.name() == null || product.name().isBlank()
                || product.price() == null || product.price().compareTo(new BigDecimal("0.01")) < 0
                || product.active() == null) {
            throw new OrderProcessingException("Каталог вернул некорректные данные товара с id " + id);
        }
        if (!product.active()) {
            throw new OrderProcessingException("Товар с id " + id + " снят с продажи");
        }
        return product;
    }

    private void reserve(InventoryRequest request) {
        ReserveResponse response;
        try {
            response = inventoryClient.reserve(request);
        } catch (FeignException e) {
            String message = switch (e.status()) {
                case 404 -> "Складская запись для товара с id " + request.productId() + " не найдена";
                case 409 -> "Склад отклонил резерв товара с id " + request.productId()
                        + ": недостаточный остаток или конфликт одновременных изменений";
                default -> "Не удалось зарезервировать товар с id " + request.productId();
            };
            throw new OrderProcessingException(message, e);
        }
        if (response == null || !response.success()) {
            throw new OrderProcessingException("Склад не подтвердил резерв товара с id " + request.productId());
        }
    }

    private void releaseReservations(List<InventoryRequest> reservations, RuntimeException failure) {
        for (InventoryRequest reservation : reservations) {
            try {
                ReserveResponse response = inventoryClient.release(reservation);
                if (response == null || !response.success()) {
                    throw new OrderProcessingException("Склад не подтвердил снятие резерва");
                }
            } catch (RuntimeException e) {
                failure.addSuppressed(e);
                log.error("Не удалось снять резерв товара с id {}, количество {}",
                        reservation.productId(), reservation.quantity());
            }
        }
    }

    public List<OrderDto> findAll() {
        return orderRepository.findAllByOrderByIdAsc().stream().map(orderMapper::toDto).toList();
    }

    public OrderDto findById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Заказ с id " + id + " не найден"));
        return orderMapper.toDto(order);
    }

    public List<OrderDto> findByEmail(String email) {
        return orderRepository.findAllByCustomerEmailOrderByIdAsc(email).stream().map(orderMapper::toDto).toList();
    }
}
