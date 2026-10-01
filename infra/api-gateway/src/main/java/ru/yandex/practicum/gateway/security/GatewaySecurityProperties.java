package ru.yandex.practicum.gateway.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.security")
public record GatewaySecurityProperties(List<User> users) {

    public GatewaySecurityProperties {
        if (users == null || users.isEmpty()) {
            throw new IllegalArgumentException("Список пользователей Gateway не должен быть пустым");
        }
        users = List.copyOf(users);
    }

    public record User(String username, String password, List<String> roles) {
    }
}
