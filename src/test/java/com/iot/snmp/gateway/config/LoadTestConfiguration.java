package com.iot.snmp.gateway.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;

@TestConfiguration
public class LoadTestConfiguration {
    
    @Bean
    @Primary
    public ReactiveRedisConnectionFactory testRedisConnectionFactory() {
        // Use embedded Redis for tests
        return new LettuceConnectionFactory("localhost", 6379);
    }
    
    @Bean
    @Primary
    public ReactiveRedisTemplate<String, String> testReactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveRedisTemplate<>(
            connectionFactory,
            RedisSerializationContext.string()
        );
    }
}