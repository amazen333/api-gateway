package com.linda.gateway.config;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.linda.gateway.filter.JwtCachedAuthFilter;

import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Configuration
public class RateLimiterConfig {

    @Autowired(required = false)
    private JwtCachedAuthFilter jwtAuthFilter;
    
    private final ConcurrentMap<String, String> keyCache = new ConcurrentHashMap<>();
    
    @Bean
    @Primary
    public KeyResolver apiKeyResolver() {
        return exchange -> {
            // Use API key from header
            String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
            if (apiKey != null && !apiKey.isBlank()) {
                return Mono.just(computeKeyHash(apiKey));
            }
            
            // Fallback to IP with rate limiting
            String ipAddress = exchange.getRequest().getRemoteAddress() != null ?
                exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() :
                "unknown";
            
            return Mono.just("ip:" + ipAddress);
        };
    }
    
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            // Extract user ID from JWT
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            
            // Fallback to API key
            String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
            if (apiKey != null && !apiKey.isBlank()) {
                return Mono.just("api:" + computeKeyHash(apiKey));
            }
            
            return Mono.just("anonymous");
        };
    }
    
    @Bean
    public KeyResolver deviceKeyResolver() {
        return exchange -> {
            // Device-specific rate limiting
            String deviceId = exchange.getRequest().getHeaders().getFirst("X-Device-Id");
            if (deviceId != null && !deviceId.isBlank()) {
                return Mono.just("device:" + deviceId);
            }
            
            // Fallback to IP
            String ipAddress = exchange.getRequest().getRemoteAddress() != null ?
                exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() :
                "unknown";
            
            return Mono.just("ip:" + ipAddress);
        };
    }
    
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String ipAddress = exchange.getRequest().getRemoteAddress() != null ?
                exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() :
                "unknown";
            
            // Cache IP resolution
            return Mono.just("ip:" + ipAddress);
        };
    }
    
    @Bean
    public RedisRateLimiter defaultRateLimiter() {
        // High capacity for general API
        return new RedisRateLimiter(
            1000,  // replenishRate
            2000,  // burstCapacity
            1      // requestedTokens
        );
    }
    
    @Bean
    public RedisRateLimiter strictRateLimiter() {
        // Strict for sensitive endpoints
        return new RedisRateLimiter(
            100,   // replenishRate
            200,   // burstCapacity
            1      // requestedTokens
        );
    }
    
    @Bean
    public RedisRateLimiter highVolumeRateLimiter() {
        // High volume for telemetry/device data
        return new RedisRateLimiter(
            5000,  // replenishRate
            10000, // burstCapacity
            1      // requestedTokens
        );
    }
    
    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        return RateLimiterRegistry.ofDefaults();
    }
    
    private String computeKeyHash(String apiKey) {
        return keyCache.computeIfAbsent(apiKey, key -> {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(key.getBytes());
                return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            } catch (NoSuchAlgorithmException e) {
                return Integer.toHexString(key.hashCode());
            }
        });
    }
}