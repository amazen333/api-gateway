package com.iot.snmp.gateway.benchmark;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import com.linda.gateway.filter.TimedFilter;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SimpleTimedFilterBenchmark {
    
    public static void main(String[] args) throws InterruptedException {
        benchmarkFilterPerformance();
    }
    
    private static void benchmarkFilterPerformance() throws InterruptedException {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TimedFilter timedFilter = new TimedFilter(meterRegistry);
        GatewayFilterChain filterChain = exchange -> Mono.empty();
        
        int threadCount = 8;
        int iterations = 10000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(iterations);
        
        Instant start = Instant.now();
        
        for (int i = 0; i < iterations; i++) {
            executor.submit(() -> {
                try {
                    ServerWebExchange exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/v1/benchmark").build()
                    );
                    
                    timedFilter.apply(new TimedFilter.Config())
                        .filter(exchange, filterChain)
                        .block();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        Instant end = Instant.now();
        
        executor.shutdown();
        
        Duration duration = Duration.between(start, end);
        double throughput = (double) iterations / duration.toMillis() * 1000;
        
        System.out.println("========================================");
        System.out.println("TimedFilter Benchmark Results:");
        System.out.println("========================================");
        System.out.println("Iterations: " + iterations);
        System.out.println("Threads: " + threadCount);
        System.out.println("Duration: " + duration.toMillis() + "ms");
        System.out.println("Throughput: " + String.format("%.2f", throughput) + " ops/sec");
        System.out.println("========================================");
    }
}