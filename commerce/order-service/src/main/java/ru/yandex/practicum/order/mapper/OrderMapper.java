package ru.yandex.practicum.order.mapper;

import org.springframework.stereotype.Component;
import ru.yandex.practicum.order.dto.CreateOrderRequest;
import ru.yandex.practicum.order.dto.OrderDto;
import ru.yandex.practicum.order.dto.OrderItemDto;
import ru.yandex.practicum.order.dto.OrderItemRequest;
import ru.yandex.practicum.order.entity.Order;
import ru.yandex.practicum.order.entity.OrderItem;

@Component
public class OrderMapper {

    public Order toEntity(CreateOrderRequest request) {
        Order order = new Order();
        order.setCustomerName(request.customerName());
        order.setCustomerEmail(request.customerEmail());
        for (OrderItemRequest itemRequest : request.items()) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(itemRequest.productId());
            item.setProductName(itemRequest.productName());
            item.setQuantity(itemRequest.quantity());
            item.setPrice(itemRequest.price());
            order.getItems().add(item);
        }
        return order;
    }

    public OrderDto toDto(Order order) {
        return new OrderDto(order.getId(), order.getCustomerName(), order.getCustomerEmail(), order.getStatus().name(),
                order.getTotalPrice(), order.getStatusDetails(), order.getCreatedAt(),
                order.getItems().stream().map(this::toItemDto).toList());
    }

    private OrderItemDto toItemDto(OrderItem item) {
        return new OrderItemDto(item.getId(), item.getProductId(), item.getProductName(),
                item.getQuantity(), item.getPrice());
    }
}
