package com.linda.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class JwtCachedAuthFilter implements GatewayFilter {

    @Value("${app.gateway.security.jwt.secret}")
    private String jwtSecret;

    @Value("${app.gateway.security.jwt.clock-skew-seconds:30}")
    private long clockSkewSeconds;

    // Optional: fallback TTL if token has no exp (discouraged in prod)
    @Value("${app.gateway.security.jwt.cache.fallback-ttl-minutes:5}")
    private long cacheFallbackTtlMinutes;

    private SecretKey secretKey;
    private io.jsonwebtoken.JwtParser jwtParser;
    private Cache<String, Claims> jwtCache;

    // Public endpoints bypassing auth
    private final List<String> publicEndpoints = List.of(
        "/api/v1/public/",
        "/health",
        "/internal/status",
        "/internal/metrics",
        "/favicon.ico",
        "/static/"
    );

    // Admin endpoints require ROLE_ADMIN
    private final List<String> adminEndpoints = List.of(
        "/api/v1/admin/",
        "/internal/"
    );

    @PostConstruct
    public void init() {
        // Build secret key (expect >= 256-bit for HS256)
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));

        // JJWT 0.12.x parser
        this.jwtParser = Jwts.parser()
            .verifyWith(secretKey)
            .clockSkewSeconds(clockSkewSeconds)
            .build();

        // Cache that aligns TTL with token exp
        this.jwtCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfter(new Expiry<String, Claims>() {
                @Override
                public long expireAfterCreate(String key, Claims claims, long currentTime) {
                    Date exp = claims.getExpiration();
                    if (exp != null) {
                        long millis = exp.getTime() - System.currentTimeMillis();
                        return millis > 0 ? millis : 0;
                    }
                    return TimeUnit.MINUTES.toMillis(cacheFallbackTtlMinutes);
                }

                @Override
                public long expireAfterUpdate(String key, Claims claims, long currentTime, long currentDuration) {
                    return currentDuration;
                }

                @Override
                public long expireAfterRead(String key, Claims claims, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .recordStats()
            .build();

        log.info("JWT Cached Auth Filter initialized (maxSize=10_000, clockSkew={}s)", clockSkewSeconds);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        final ServerHttpRequest request = exchange.getRequest();
        final String path = request.getPath().value();

        if (isPublicEndpoint(path)) {
            return chain.filter(exchange);
        }

        final String token = extractToken(request);
        if (token == null || token.isBlank()) {
            return unauthorized(exchange, "Missing authorization token");
        }

        // Cache hit
        Claims claims = jwtCache.getIfPresent(token);
        if (claims != null) {
            return processClaims(exchange, chain, claims, path, token);
        }

        // Validate and cache
        return Mono.defer(() -> {
            try {
                Claims parsed = jwtParser.parseSignedClaims(token).getPayload();
                // Put into cache
                jwtCache.put(token, parsed);
                return processClaims(exchange, chain, parsed, path, token);
            } catch (ExpiredJwtException e) {
                log.debug("Token expired: {}", e.getMessage());
                return unauthorized(exchange, "Token expired");
            } catch (JwtException e) {
                log.debug("Invalid token: {}", e.getMessage());
                return unauthorized(exchange, "Invalid token");
            }
        });
    }

    private Mono<Void> processClaims(ServerWebExchange exchange, GatewayFilterChain chain,
                                     Claims claims, String path, String token) {
        if (isTokenExpired(claims)) {
            jwtCache.invalidate(token);
            return unauthorized(exchange, "Token expired");
        }

        if (isAdminEndpoint(path) && !hasAdminRole(claims)) {
            return forbidden(exchange, "Admin access required");
        }

        ServerHttpRequest modifiedRequest = enhanceRequest(exchange.getRequest(), claims);
        return chain.filter(exchange.mutate().request(modifiedRequest).build());
    }

    private boolean isPublicEndpoint(String path) {
        return publicEndpoints.stream().anyMatch(path::startsWith);
    }

    private boolean isAdminEndpoint(String path) {
        return adminEndpoints.stream().anyMatch(path::startsWith);
    }

    @SuppressWarnings("unchecked")
    private boolean hasAdminRole(Claims claims) {
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?> roles) {
            for (Object r : roles) {
                if (r instanceof String && "ROLE_ADMIN".equals(r)) return true;
            }
        }
        return false;
    }

    private boolean isTokenExpired(Claims claims) {
        Date expiration = claims.getExpiration();
        return expiration != null && expiration.before(new Date());
    }

    private String extractToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        return request.getHeaders().getFirst("X-Auth-Token");
    }

    private ServerHttpRequest enhanceRequest(ServerHttpRequest request, Claims claims) {
        String subject = safeString(claims.getSubject());
        String username = safeString(claims.get("username", String.class));
        String clientId = safeString(claims.get("client_id", String.class));
        String roles = joinRoles(claims.get("roles"));

        String issuedAt = claims.getIssuedAt() != null
            ? String.valueOf(claims.getIssuedAt().getTime())
            : "";

        return request.mutate()
            .header("X-User-Id", subject)
            .header("X-Username", username)
            .header("X-Roles", roles)
            .header("X-Client-Id", clientId)
            .header("X-Token-Issued-At", issuedAt)
            .build();
    }

    @SuppressWarnings("unchecked")
    private String joinRoles(Object rolesObj) {
        if (rolesObj instanceof List<?> roles) {
            StringBuilder sb = new StringBuilder();
            for (Object r : roles) {
                if (r instanceof String s && !s.isBlank()) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(s);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private String safeString(String v) {
        return v == null ? "" : v;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("X-Auth-Error", message);
        return exchange.getResponse().setComplete();
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().add("X-Auth-Error", message);
        return exchange.getResponse().setComplete();
    }

    // Lightweight stats for Actuator or custom endpoint
    public Map<String, Object> getCacheStats() {
        return Map.of(
            "size", jwtCache.estimatedSize(),
            "hitRate", jwtCache.stats().hitRate(),
            "missRate", jwtCache.stats().missRate(),
            "loadSuccessCount", jwtCache.stats().loadSuccessCount(),
            "loadFailureCount", jwtCache.stats().loadFailureCount(),
            "totalLoadTime", jwtCache.stats().totalLoadTime()
        );
    }
}
