package ru.yandex.practicum.gateway.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import ru.yandex.practicum.gateway.ApiGatewayApp;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ApiGatewayApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Import(GatewaySecurityConfigTest.BackendConfiguration.class)
class GatewaySecurityConfigTest {

    private final WebTestClient http;
    private final GatewaySecurityProperties properties;
    private final ReactiveUserDetailsService users;

    @Autowired
    GatewaySecurityConfigTest(WebTestClient http, GatewaySecurityProperties properties,
                              ReactiveUserDetailsService users) {
        this.http = http;
        this.properties = properties;
        this.users = users;
    }

    @ParameterizedTest
    @CsvSource({"ivan", "anna"})
    void shouldUseConfiguredPasswordHashWithoutEncodingAgain(String username) {
        var password = properties.users().stream()
                .filter(user -> user.username().equals(username))
                .map(GatewaySecurityProperties.User::password)
                .findFirst()
                .orElseThrow();

        assertThat(password).startsWith("{bcrypt}");
        assertThat(users.findByUsername(username).map(UserDetails::getPassword).block()).isEqualTo(password);
    }

    @ParameterizedTest
    @CsvSource({
            "OPTIONS, /api/products",
            "OPTIONS, /api/orders",
            "OPTIONS, /unknown",
            "GET, /api/products",
            "GET, /api/products/product-1",
            "GET, /api/categories",
            "GET, /api/categories/category-1",
            "GET, /api/inventory",
            "GET, /api/inventory/product-1",
            "GET, /swagger-ui.html",
            "GET, /swagger-ui/index.html",
            "GET, /v3/api-docs",
            "GET, /v3/api-docs/swagger-config"
    })
    void shouldAllowPublicRequestsWithoutCredentials(String method, String path) {
        http.method(HttpMethod.valueOf(method))
                .uri(path)
                .exchange()
                .expectStatus().isOk();
    }

    @ParameterizedTest
    @CsvSource({
            "HEAD, /api/products",
            "HEAD, /api/categories",
            "HEAD, /api/inventory",
            "POST, /api/orders",
            "GET, /api/orders/by-email?email=ivan@example.com",
            "GET, /api/orders/order-1",
            "GET, /api/orders",
            "POST, /api/products",
            "PUT, /api/products/product-1",
            "PATCH, /api/products/product-1",
            "DELETE, /api/products/product-1",
            "POST, /api/categories",
            "PUT, /api/categories/category-1",
            "PATCH, /api/categories/category-1",
            "DELETE, /api/categories/category-1",
            "POST, /api/inventory",
            "PUT, /api/inventory/product-1",
            "PATCH, /api/inventory/product-1",
            "DELETE, /api/inventory/product-1"
    })
    void shouldRejectProtectedRequestsWithoutCredentials(String method, String path) {
        http.method(HttpMethod.valueOf(method))
                .uri(path)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @ParameterizedTest
    @CsvSource({
            "ivan, POST, /api/orders, 200",
            "ivan, POST, /api/orders/order-1, 200",
            "ivan, GET, /api/orders/by-email?email=ivan@example.com, 200",
            "ivan, GET, /api/orders/order-1, 200",
            "ivan, GET, /api/orders, 403",
            "ivan, HEAD, /api/products, 403",
            "ivan, HEAD, /api/categories, 403",
            "ivan, HEAD, /api/inventory, 403",
            "ivan, POST, /api/products, 403",
            "ivan, PUT, /api/products/product-1, 403",
            "ivan, PATCH, /api/products/product-1, 403",
            "ivan, DELETE, /api/products/product-1, 403",
            "ivan, POST, /api/categories, 403",
            "ivan, PUT, /api/categories/category-1, 403",
            "ivan, PATCH, /api/categories/category-1, 403",
            "ivan, DELETE, /api/categories/category-1, 403",
            "ivan, POST, /api/inventory, 403",
            "ivan, PUT, /api/inventory/product-1, 403",
            "ivan, PATCH, /api/inventory/product-1, 403",
            "ivan, DELETE, /api/inventory/product-1, 403",
            "anna, POST, /api/orders, 200",
            "anna, GET, /api/orders/by-email?email=anna@example.com, 200",
            "anna, GET, /api/orders/order-1, 200",
            "anna, GET, /api/orders, 200",
            "anna, HEAD, /api/products, 403",
            "anna, HEAD, /api/categories, 403",
            "anna, HEAD, /api/inventory, 403",
            "anna, POST, /api/products, 200",
            "anna, PUT, /api/products/product-1, 200",
            "anna, PATCH, /api/products/product-1, 200",
            "anna, DELETE, /api/products/product-1, 200",
            "anna, POST, /api/categories, 200",
            "anna, PUT, /api/categories/category-1, 200",
            "anna, PATCH, /api/categories/category-1, 200",
            "anna, DELETE, /api/categories/category-1, 200",
            "anna, POST, /api/inventory, 200",
            "anna, PUT, /api/inventory/product-1, 200",
            "anna, PATCH, /api/inventory/product-1, 200",
            "anna, DELETE, /api/inventory/product-1, 200",
            "anna, GET, /unknown, 403",
            "anna, POST, /v3/api-docs, 403",
            "anna, DELETE, /swagger-ui/index.html, 403",
            "anna, PUT, /api/orders/order-1, 403",
            "anna, GET, /api/orders/order-1/private, 403"
    })
    void shouldApplyRolesAndDenyUnlistedRequests(String username, String method, String path, int status) {
        http.method(HttpMethod.valueOf(method))
                .uri(path)
                .headers(headers -> headers.setBasicAuth(username, username))
                .exchange()
                .expectStatus().isEqualTo(status);
    }

    @Test
    void shouldRejectInvalidPassword() {
        http.get()
                .uri("/api/orders/order-1")
                .headers(headers -> headers.setBasicAuth("ivan", "wrong-password"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldNotKeepAuthenticationBetweenRequests() {
        http.get()
                .uri("/api/orders/order-1")
                .headers(headers -> headers.setBasicAuth("ivan", "ivan"))
                .exchange()
                .expectStatus().isOk()
                .expectCookie().doesNotExist("SESSION");

        http.get()
                .uri("/api/orders/order-1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldAllowCorsPreflightWithoutAuthentication() {
        http.options()
                .uri("http://localhost/api/orders")
                .header(HttpHeaders.ORIGIN, "http://localhost:8443")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8443")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BackendConfiguration {

        @Bean
        RouteLocator testBackendRoute(RouteLocatorBuilder builder) {
            return builder.routes()
                    .route("test-backend", route -> route.order(-100)
                            .path("/**")
                            .uri("forward:/test-backend"))
                    .build();
        }

        @Bean
        RouterFunction<ServerResponse> testBackend() {
            return RouterFunctions.route()
                    .path("/test-backend", builder -> builder.route(request -> true,
                            request -> ServerResponse.ok().build()))
                    .build();
        }
    }
}
