package com.linda.gateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.Data;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.client.reactive.ReactorResourceFactory;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(GatewayPerformanceProperties.class)
public class GatewayPerformanceConfig {

    @Value("${app.gateway.performance.worker-threads:4}")
    private int workerThreads;
    
    @Value("${app.gateway.performance.boss-threads:2}")
    private int bossThreads;
    
    @Bean
    public ReactorResourceFactory reactorResourceFactory() {
        LoopResources loopResources = LoopResources.create(
            "gateway-event-loop",
            bossThreads,
            workerThreads,
            true
        );
        
        ReactorResourceFactory factory = new ReactorResourceFactory();
        factory.setUseGlobalResources(false);
        factory.setLoopResources(loopResources);
        factory.setConnectionProvider(connectionProvider());
        
        return factory;
    }
    
    @Bean
    public ConnectionProvider connectionProvider() {
        return ConnectionProvider.builder("gateway-connection-pool")
            .maxConnections(10000)
            .maxIdleTime(Duration.ofSeconds(30))
            .maxLifeTime(Duration.ofSeconds(60))
            .pendingAcquireTimeout(Duration.ofSeconds(10))
            .pendingAcquireMaxCount(-1)
            .evictInBackground(Duration.ofSeconds(30))
            .metrics(true)
            .build();
    }
    
    @Bean
    public HttpClient gatewayHttpClient() {
        return HttpClient.create(connectionProvider())
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_REUSEADDR, true)
            .responseTimeout(Duration.ofSeconds(5))
            .doOnConnected(conn -> 
                conn.addHandlerLast(new ReadTimeoutHandler(10, TimeUnit.SECONDS))
                    .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS))
            );
    }
    
    @Bean
    public ReactorClientHttpConnector reactorClientHttpConnector() {
        return new ReactorClientHttpConnector(gatewayHttpClient());
    }
    
    @Bean
    public RouteLocator highPerformanceRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
            // Health check route (fast path)
            .route("health-check", r -> r
                .path("/health", "/ready", "/live")
                .filters(f -> f.setResponseHeader("X-Response-Type", "health"))
                .uri("no://op")
            )
            
            // Static assets (cached)
            .route("static-assets", r -> r
                .path("/static/**", "/assets/**", "/*.ico", "/*.png", "/*.jpg")
                .filters(f -> f
                    .setResponseHeader("Cache-Control", "public, max-age=31536000")
                    .setResponseHeader("X-Content-Type-Options", "nosniff")
                )
                .uri("no://op")
            )
            
            // API routes will be configured via application.yml
            .build();
    }
}

// Properties class
@ConfigurationProperties(prefix = "app.gateway.performance")
@Data
class GatewayPerformanceProperties {
    private boolean enabled = true;
    private int workerThreads = 4;
    private int bossThreads = 2;
    private int maxInitialLineLength = 4096;
    private int maxHeaderSize = 8192;
    private int maxChunkSize = 8192;
    private boolean validateHeaders = false;
    private int compressionLevel = 6;
    private boolean directBuffers = true;
    private int maxConnections = 10000;
    private Duration connectionTimeout = Duration.ofSeconds(2);
    private Duration idleTimeout = Duration.ofSeconds(30);
}