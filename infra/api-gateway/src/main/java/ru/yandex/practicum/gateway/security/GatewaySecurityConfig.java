package ru.yandex.practicum.gateway.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class GatewaySecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    MapReactiveUserDetailsService userDetailsService(GatewaySecurityProperties properties,
                                                    PasswordEncoder passwordEncoder) {
        var users = properties.users().stream()
                .map(user -> User.withUsername(user.username())
                        .password(passwordEncoder.encode(user.password()))
                        .roles(user.roles().toArray(String[]::new))
                        .build())
                .toList();
        return new MapReactiveUserDetailsService(users);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GlobalCorsProperties properties) {
        var source = new UrlBasedCorsConfigurationSource();
        source.setCorsConfigurations(properties.getCorsConfigurations());
        return source;
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                 CorsConfigurationSource corsConfigurationSource) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .httpBasic(Customizer.withDefaults())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/products/**", "/api/categories/**", "/api/inventory/**")
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/orders").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/orders/by-email", "/api/orders/{id}").hasRole("USER")
                        .pathMatchers(HttpMethod.POST, "/api/orders/**").hasRole("USER")
                        .pathMatchers(HttpMethod.POST, "/api/products/**", "/api/categories/**", "/api/inventory/**")
                        .hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PUT, "/api/products/**", "/api/categories/**", "/api/inventory/**")
                        .hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PATCH, "/api/products/**", "/api/categories/**", "/api/inventory/**")
                        .hasRole("ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api/products/**", "/api/categories/**", "/api/inventory/**")
                        .hasRole("ADMIN")
                        .anyExchange().denyAll())
                .build();
    }
}
