package com.linda.gateway.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.linda.gateway.filter.TimedFilter;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/internal/metrics")
@RequiredArgsConstructor
@Slf4j
public class TimedFilterController {
    
    private final TimedFilter timedFilter;
    private final MeterRegistry meterRegistry;
    
    @GetMapping("/performance")
    public Mono<Map<String, Object>> getPerformanceStats() {
        return Mono.defer(() -> {
            try {
                // Create defensive copy if getPerformanceStats returns Map
                Map<String, Object> stats = new HashMap<>();
                Map<String, Object> originalStats = timedFilter.getPerformanceStats();
                if (originalStats != null) {
                    stats.putAll(originalStats);
                }
                
                // Fix 1: Changed Collection<Timer> to List<Timer>
                List<Timer> timers = new ArrayList<>(meterRegistry.find("gateway.filter.detailed").timers());
                
                Map<String, Map<String, Object>> timerStats = timers.stream()
                    .filter(timer -> 
                        timer != null && 
                        timer.getId() != null &&
                        timer.getId().getTag("route") != null && 
                        timer.getId().getTag("path") != null
                    )
                    .collect(Collectors.toMap(
                        timer -> {
                            String route = timer.getId().getTag("route");
                            String path = timer.getId().getTag("path");
                            return (route != null && path != null) ? route + "." + path : "unknown";
                        },
                        timer -> {
                            // Fix 2: Use timer.totalTime() instead of Timer.Snapshot
                            Map<String, Object> timerData = new HashMap<>();
                            timerData.put("count", timer.count());
                            
                            // Calculate mean from total time
                            double totalTime = timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS);
                            double count = timer.count();
                            double mean = count > 0 ? (totalTime / count) / 1_000_000.0 : 0.0; // Convert to milliseconds
                            timerData.put("mean", mean);
                            
                            // For percentiles, you need a different approach since we don't have snapshot
                            // Option 1: Use histogram if configured
                            // Option 2: Store these values in TimedFilter
                            timerData.put("p95", 0.0); // Placeholder - implement actual percentile calculation
                            timerData.put("p99", 0.0); // Placeholder
                            timerData.put("max", timer.max(java.util.concurrent.TimeUnit.NANOSECONDS) / 1_000_000.0); // Convert to ms
                            
                            return timerData;
                        },
                        (existing, replacement) -> {
                            // Merge duplicate timers
                            return replacement;
                        }
                    ));
                
                stats.put("detailedTimers", timerStats);
                stats.put("collectionTime", Instant.now().toString());
                
                return Mono.just(stats);
            } catch (Exception e) {
                log.error("Failed to collect metrics", e);
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "Metrics collection failed: " + e.getMessage());
                errorResponse.put("timestamp", Instant.now().toString());
                return Mono.just(errorResponse);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    @GetMapping("/health")
    public Mono<Map<String, Object>> healthCheck() {
        return Mono.defer(() -> {
            try {
                long totalRequests = getCounterValue("gateway.requests.total");
                long totalErrors = getCounterValue("gateway.errors.total");
                
                Map<String, Object> health = new HashMap<>();
                health.put("status", "UP");
                health.put("totalRequests", totalRequests);
                health.put("totalErrors", totalErrors);
                health.put("errorRate", totalRequests > 0 ? 
                    (double) totalErrors / totalRequests * 100 : 0.0);
                health.put("timestamp", Instant.now().toString());
                health.put("service", "TimedFilter");
                health.put("version", "1.0.0");
                
                return Mono.just(health);
            } catch (Exception e) {
                log.error("Health check failed", e);
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("status", "DOWN");
                errorResponse.put("error", e.getMessage());
                errorResponse.put("timestamp", Instant.now().toString());
                return Mono.just(errorResponse);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    @GetMapping("/reset")
    public Mono<Map<String, Object>> resetStats() {
        return Mono.defer(() -> {
            try {
                log.info("Resetting TimedFilter statistics");
                // Add actual reset logic here
                
                Map<String, Object> response = new HashMap<>();
                response.put("status", "reset_initiated");
                response.put("timestamp", Instant.now().toString());
                return Mono.just(response);
            } catch (Exception e) {
                log.error("Failed to reset stats", e);
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("status", "reset_failed");
                errorResponse.put("error", e.getMessage());
                errorResponse.put("timestamp", Instant.now().toString());
                return Mono.just(errorResponse);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    private long getCounterValue(String metricName) {
        try {
            // Fix 3: Correct way to get counter value
            Counter counter = meterRegistry.find(metricName).counter();
            if (counter != null) {
                return (long) counter.count();
            }
            return 0L;
        } catch (Exception e) {
            log.error("Failed to get counter value for {}", metricName, e);
            return 0L;
        }
    }
}