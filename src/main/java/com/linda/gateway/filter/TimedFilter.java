package com.linda.gateway.filter;

import io.micrometer.core.instrument.*;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance timed filter that records detailed metrics for each request.
 * Tracks execution time, success/error rates, and provides performance insights.
 */
@Component
@Slf4j
public class TimedFilter extends AbstractGatewayFilterFactory<TimedFilter.Config> {

    private final MeterRegistry meterRegistry;
    private final Map<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final DistributionSummary requestSizeSummary;
    private final DistributionSummary responseSizeSummary;
    
    // Performance counters
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong totalFilterTime = new AtomicLong();
    
    // Histogram buckets for response times (in milliseconds)
    private static final double[] RESPONSE_TIME_BUCKETS = {
        1, 5, 10, 25, 50, 75, 100, 250, 500, 750, 
        1000, 1500, 2000, 3000, 5000, 10000
    };

    public TimedFilter(MeterRegistry meterRegistry) {
        super(Config.class);
        this.meterRegistry = meterRegistry;
        
        // Initialize distribution summaries
        this.requestSizeSummary = DistributionSummary.builder("gateway.request.size")
            .description("Size of incoming requests in bytes")
            .baseUnit("bytes")
            .publishPercentiles(0.5, 0.95, 0.99)
            .scale(1024) // Scale to kilobytes
            .register(meterRegistry);
        
        this.responseSizeSummary = DistributionSummary.builder("gateway.response.size")
            .description("Size of outgoing responses in bytes")
            .baseUnit("bytes")
            .publishPercentiles(0.5, 0.95, 0.99)
            .scale(1024) // Scale to kilobytes
            .register(meterRegistry);
        
        // Register custom gauges
        registerCustomGauges();
        
        log.info("TimedFilter initialized with detailed metrics collection");
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
            if (!config.isEnabled()) {
                return chain.filter(exchange);
            }
            
            Instant startTime = Instant.now();
            String requestId = generateRequestId();
            
            // Increment total requests counter
            totalRequests.incrementAndGet();
            
            // Record request size if available
            recordRequestSize(exchange);
            
            // Extract request metadata
            RequestMetadata metadata = extractRequestMetadata(exchange);
            
            // Create timer sample
            Timer.Sample sample = Timer.start(meterRegistry);
            
            // Set request start time attribute
            exchange.getAttributes().put("request.startTime", startTime);
            exchange.getAttributes().put("request.id", requestId);
            exchange.getAttributes().put("request.metadata", metadata);
            
            // Execute the filter chain with detailed timing
            return chain.filter(exchange)
                .doOnSuccess(v -> handleSuccess(exchange, startTime, sample, metadata))
                .doOnError(e -> handleError(exchange, startTime, sample, metadata, e))
                .doFinally(signalType -> {
                    // Always record completion metrics
                    recordCompletionMetrics(exchange, startTime, metadata, signalType);
                });
        }
        
        @Override
        public int getOrder() {
            return config.getOrder();
        }
        
        private void handleSuccess(ServerWebExchange exchange, Instant startTime, 
                                  Timer.Sample sample, RequestMetadata metadata) {
            long duration = Duration.between(startTime, Instant.now()).toMillis();
            totalFilterTime.addAndGet(duration);
            
            // Get response status
            HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
            String code = statusCode != null ? String.valueOf(statusCode.value()) : "unknown";
            
            // Record success metrics
            recordTimer(sample, metadata, code, "success", duration);
            recordCounter("success", metadata, code);
            recordHistogram(duration, metadata, code, "success");
            
            // Record response size
            recordResponseSize(exchange);
            
            // Add performance headers
            addPerformanceHeaders(exchange, startTime, duration, "success");
            
            // Log slow requests
            if (duration > config.getSlowRequestThreshold()) {
                log.warn("Slow request detected: {} {} took {}ms", 
                    metadata.getMethod(), metadata.getPath(), duration);
            }
        }
        
        private void handleError(ServerWebExchange exchange, Instant startTime,
                                Timer.Sample sample, RequestMetadata metadata, Throwable error) {
            long duration = Duration.between(startTime, Instant.now()).toMillis();
            totalFilterTime.addAndGet(duration);
            totalErrors.incrementAndGet();
            
            // Record error metrics
            String errorType = error.getClass().getSimpleName();
            recordTimer(sample, metadata, "error", errorType, duration);
            recordCounter("error", metadata, errorType);
            recordHistogram(duration, metadata, "error", errorType);
            
            // Add performance headers even on error
            addPerformanceHeaders(exchange, startTime, duration, "error");
            
            // Log error details
            log.error("Request failed: {} {} - {}ms - {}", 
                metadata.getMethod(), metadata.getPath(), duration, error.getMessage());
        }
        
        private void recordCompletionMetrics(ServerWebExchange exchange, Instant startTime,
                                           RequestMetadata metadata, reactor.core.publisher.SignalType signalType) {
            long duration = Duration.between(startTime, Instant.now()).toMillis();
            
            // Record completion metrics
            meterRegistry.counter("gateway.request.completed",
                "route", metadata.getRouteId(),
                "path", metadata.getPath(),
                "method", metadata.getMethod(),
                "signal", signalType.name()
            ).increment();
            
            // Update sliding window metrics (last 1000 requests)
            updateSlidingWindowMetrics(duration, metadata.isSuccessful());
        }
        
        private void recordTimer(Timer.Sample sample, RequestMetadata metadata, 
                                String status, String outcome, long duration) {
            String timerKey = String.format("%s:%s:%s:%s:%s", 
                metadata.getRouteId(), metadata.getPath(), 
                metadata.getMethod(), status, outcome);
            
            Timer timer = timerCache.computeIfAbsent(timerKey, k -> 
                Timer.builder("gateway.filter.detailed")
                    .description("Detailed execution time per route/path/method")
                    .tags(
                        "route", metadata.getRouteId(),
                        "path", metadata.getPath(),
                        "method", metadata.getMethod(),
                        "status", status,
                        "outcome", outcome,
                        "filter", "TimedFilter"
                    )
                    .publishPercentiles(0.5, 0.75, 0.95, 0.99, 0.999)
                    .publishPercentileHistogram(true)
                    .minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(Duration.ofSeconds(10))
                    .register(meterRegistry)
            );
            
            sample.stop(timer);
            
            // Also record to generic timer
            Timer genericTimer = Timer.builder("gateway.request.duration")
                .tags(
                    "route", metadata.getRouteId(),
                    "status", status,
                    "outcome", outcome
                )
                .register(meterRegistry);
            
            genericTimer.record(duration, TimeUnit.MILLISECONDS);
        }
        
        private void recordCounter(String type, RequestMetadata metadata, String detail) {
            meterRegistry.counter("gateway.request." + type,
                "route", metadata.getRouteId(),
                "path", metadata.getPath(),
                "method", metadata.getMethod(),
                "detail", detail
            ).increment();
        }
        
        private void recordHistogram(long duration, RequestMetadata metadata, 
                                    String status, String outcome) {
            meterRegistry.timer("gateway.request.histogram",
                "route", metadata.getRouteId(),
                "status", status,
                "outcome", outcome
            ).record(duration, TimeUnit.MILLISECONDS);
        }
        
        private void recordRequestSize(ServerWebExchange exchange) {
            Long contentLength = exchange.getRequest().getHeaders().getContentLength();
            if (contentLength > 0) {
                requestSizeSummary.record(contentLength);
                
                // Also record per route
                meterRegistry.summary("gateway.request.size.detailed",
                    "route", getRouteId(exchange),
                    "method", exchange.getRequest().getMethod().name()
                ).record(contentLength);
            }
        }
        
        private void recordResponseSize(ServerWebExchange exchange) {
            // Note: Response size might not be available immediately
            // This would need to be implemented with response body interception
            exchange.getResponse().beforeCommit(() -> {
                Long contentLength = exchange.getResponse().getHeaders().getContentLength();
                if (contentLength > 0) {
                    responseSizeSummary.record(contentLength);
                }
                return Mono.empty();
            });
        }
        
        private void addPerformanceHeaders(ServerWebExchange exchange, Instant startTime,
                                          long duration, String outcome) {
            exchange.getResponse().getHeaders().add("X-Request-ID", 
                exchange.getAttribute("request.id"));
            exchange.getResponse().getHeaders().add("X-Response-Time", 
                String.valueOf(duration));
            exchange.getResponse().getHeaders().add("X-Request-Outcome", outcome);
            exchange.getResponse().getHeaders().add("X-Request-Start", 
                startTime.toString());
        }
        
        private void updateSlidingWindowMetrics(long duration, boolean success) {
            // In-memory sliding window for real-time monitoring
            // Implementation would use a circular buffer
        }
        
        private RequestMetadata extractRequestMetadata(ServerWebExchange exchange) {
            String routeId = getRouteId(exchange);
            String path = exchange.getRequest().getPath().value();
            HttpMethod method = exchange.getRequest().getMethod();
            String clientIp = getClientIp(exchange);
            String userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");
            
            return RequestMetadata.builder()
                .routeId(routeId != null ? routeId : "unknown")
                .path(path)
                .method(method != null ? method.name() : "UNKNOWN")
                .clientIp(clientIp)
                .userAgent(userAgent != null ? userAgent : "unknown")
                .timestamp(Instant.now())
                .build();
        }
        
        private String getRouteId(ServerWebExchange exchange) {
            return exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        }
        
        private String getClientIp(ServerWebExchange exchange) {
            String xForwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                return xForwardedFor.split(",")[0].trim();
            }
            return exchange.getRequest().getRemoteAddress() != null ?
                exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() :
                "unknown";
        }
        
        private String generateRequestId() {
            return String.format("req_%d_%d", 
                System.currentTimeMillis(), 
                System.nanoTime() % 10000);
        }
    }
    
    private void registerCustomGauges() {
        // Register gauge for total requests
        Gauge.builder("gateway.requests.total", totalRequests, AtomicLong::get)
            .description("Total number of requests processed")
            .register(meterRegistry);
        
        // Register gauge for total errors
        Gauge.builder("gateway.errors.total", totalErrors, AtomicLong::get)
            .description("Total number of errors")
            .register(meterRegistry);
        
        // Register gauge for average filter time
        Gauge.builder("gateway.filter.avg.time", 
                () -> totalRequests.get() > 0 ? 
                    (double) totalFilterTime.get() / totalRequests.get() : 0)
            .description("Average filter execution time in milliseconds")
            .baseUnit("ms")
            .register(meterRegistry);
        
        // Register gauge for error rate
        Gauge.builder("gateway.error.rate",
                () -> totalRequests.get() > 0 ? 
                    (double) totalErrors.get() / totalRequests.get() * 100 : 0)
            .description("Error rate percentage")
            .baseUnit("percent")
            .register(meterRegistry);
        
        // Register gauge for current active requests
        Gauge.builder("gateway.requests.active", 
                () -> getActiveRequestsCount())
            .description("Number of currently active requests")
            .register(meterRegistry);
    }
    
    private long getActiveRequestsCount() {
        // This would need a proper implementation tracking in-flight requests
        // For now, return a simple counter
        return totalRequests.get() - getCompletedRequests();
    }
    
    private long getCompletedRequests() {
        // Implementation would track completed requests
        return totalRequests.get() - totalErrors.get();
    }
    
    /**
     * Get performance statistics for monitoring
     */
    public Map<String, Object> getPerformanceStats() {
        long requests = totalRequests.get();
        long errors = totalErrors.get();
        long totalTime = totalFilterTime.get();
        
        return Map.of(
            "totalRequests", requests,
            "totalErrors", errors,
            "totalFilterTimeMs", totalTime,
            "avgRequestTimeMs", requests > 0 ? (double) totalTime / requests : 0,
            "errorRate", requests > 0 ? (double) errors / requests * 100 : 0,
            "requestsPerSecond", calculateRequestsPerSecond(),
            "timerCacheSize", timerCache.size(),
            "timestamp", Instant.now()
        );
    }
    
    private double calculateRequestsPerSecond() {
        // Implementation would track requests in time windows
        return 0.0; // Placeholder
    }
    
    /**
     * Configuration class for TimedFilter
     */
    public static class Config {
        private boolean enabled = true;
        private int order = Ordered.HIGHEST_PRECEDENCE + 100;
        private long slowRequestThreshold = 1000; // ms
        private boolean recordRequestSize = true;
        private boolean recordResponseSize = true;
        private boolean addPerformanceHeaders = true;
        private List<String> excludedPaths = Arrays.asList("/health", "/actuator/**");
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public int getOrder() { return order; }
        public void setOrder(int order) { this.order = order; }
        
        public long getSlowRequestThreshold() { return slowRequestThreshold; }
        public void setSlowRequestThreshold(long slowRequestThreshold) { 
            this.slowRequestThreshold = slowRequestThreshold; 
        }
        
        public boolean isRecordRequestSize() { return recordRequestSize; }
        public void setRecordRequestSize(boolean recordRequestSize) { 
            this.recordRequestSize = recordRequestSize; 
        }
        
        public boolean isRecordResponseSize() { return recordResponseSize; }
        public void setRecordResponseSize(boolean recordResponseSize) { 
            this.recordResponseSize = recordResponseSize; 
        }
        
        public boolean isAddPerformanceHeaders() { return addPerformanceHeaders; }
        public void setAddPerformanceHeaders(boolean addPerformanceHeaders) { 
            this.addPerformanceHeaders = addPerformanceHeaders; 
        }
        
        public List<String> getExcludedPaths() { return excludedPaths; }
        public void setExcludedPaths(List<String> excludedPaths) { 
            this.excludedPaths = excludedPaths; 
        }
    }
    
    /**
     * Request metadata DTO
     */
    @Data
    @Builder
    private static class RequestMetadata {
        private String routeId;
        private String path;
        private String method;
        private String clientIp;
        private String userAgent;
        private Instant timestamp;
        private boolean successful;
        
        public boolean isSuccessful() {
            return successful;
        }

		public String getRouteId() {
			// TODO Auto-generated method stub
			return null;
		}
    }
}