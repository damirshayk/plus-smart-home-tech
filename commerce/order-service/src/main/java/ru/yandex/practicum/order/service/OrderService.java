package ru.yandex.practicum.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.yandex.practicum.order.dto.CreateOrderRequest;
import ru.yandex.practicum.order.dto.OrderDto;
import ru.yandex.practicum.order.entity.Order;
import ru.yandex.practicum.order.exception.NotFoundException;
import ru.yandex.practicum.order.mapper.OrderMapper;
import ru.yandex.practicum.order.repository.OrderRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    public OrderDto create(CreateOrderRequest request) {
        Order order = orderMapper.toEntity(request);
        order.setStatus("CREATED");
        order.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        order.setTotalPrice(order.getItems().stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return orderMapper.toDto(orderRepository.save(order));
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
