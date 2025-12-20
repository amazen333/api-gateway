package com.linda.gateway.filter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class PerformanceMetricsFilter extends AbstractGatewayFilterFactory<PerformanceMetricsFilter.Config> {

    private final MeterRegistry meterRegistry;
    private final Map<String, Timer> timerCache = new ConcurrentHashMap<>();
    
    public PerformanceMetricsFilter(MeterRegistry meterRegistry) {
        super(Config.class);
        this.meterRegistry = meterRegistry;
    }
    
    @Override
    public GatewayFilter apply(Config config) {
        return new InnerFilter(config);
    }
    
    private class InnerFilter implements GatewayFilter, Ordered {
        private final Config config;
        
        InnerFilter(Config config) {
            this.config = config;
        }
        
        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            Instant startTime = Instant.now();
            ServerHttpRequest request = exchange.getRequest();
            
            String routeId = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String path = request.getPath().value();
            String method = request.getMethod().name();
            
            return chain.filter(exchange)
                .doOnSuccess(v -> recordSuccess(startTime, routeId, path, method, exchange))
                .doOnError(e -> recordError(startTime, routeId, path, method, e));
        }
        
        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
        
        private void recordSuccess(Instant startTime, String routeId, String path, 
                                  String method, ServerWebExchange exchange) {
            long duration = Duration.between(startTime, Instant.now()).toMillis();
            int status = exchange.getResponse().getStatusCode() != null ?
                exchange.getResponse().getStatusCode().value() : 200;
            
            // Record metrics
            Timer timer = getOrCreateTimer("http.request.duration", routeId, path, method, String.valueOf(status));
            timer.record(duration, TimeUnit.MILLISECONDS);
            
            // Increment counter
            meterRegistry.counter("http.requests.total",
                "route", routeId != null ? routeId : "unknown",
                "path", path,
                "method", method,
                "status", String.valueOf(status)
            ).increment();
            
            // Record histogram
            meterRegistry.timer("http.request.latency",
                "route", routeId != null ? routeId : "unknown",
                "path", path
            ).record(duration, TimeUnit.MILLISECONDS);
            
            // Set response header
            exchange.getResponse().getHeaders().add("X-Response-Time", String.valueOf(duration));
        }
        
        private void recordError(Instant startTime, String routeId, String path, 
                                String method, Throwable error) {
            long duration = Duration.between(startTime, Instant.now()).toMillis();
            
            meterRegistry.counter("http.requests.errors",
                "route", routeId != null ? routeId : "unknown",
                "path", path,
                "method", method,
                "error", error.getClass().getSimpleName()
            ).increment();
            
            log.debug("Request failed: {} {} - {}ms - {}", method, path, duration, error.getMessage());
        }
        
        private Timer getOrCreateTimer(String name, String route, String path, String method, String status) {
            String key = String.format("%s:%s:%s:%s", route, path, method, status);
            return timerCache.computeIfAbsent(key, k -> 
                Timer.builder(name)
                    .tag("route", route != null ? route : "unknown")
                    .tag("path", path)
                    .tag("method", method)
                    .tag("status", status)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
            );
        }
    }
    
    public static class Config {
        private boolean enabled = true;
        private boolean recordPercentiles = true;
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public boolean isRecordPercentiles() { return recordPercentiles; }
        public void setRecordPercentiles(boolean recordPercentiles) { this.recordPercentiles = recordPercentiles; }
    }
}