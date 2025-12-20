package com.linda.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/auth")
    public Mono<ResponseEntity<Map<String, Object>>> authFallback(ServerWebExchange exchange) {
        return createFallbackResponse("Authentication service is temporarily unavailable");
    }

    @GetMapping("/device")
    public Mono<ResponseEntity<Map<String, Object>>> deviceFallback(ServerWebExchange exchange) {
        return createFallbackResponse("Device service is temporarily unavailable");
    }

    @GetMapping("/polling")
    public Mono<ResponseEntity<Map<String, Object>>> pollingFallback(ServerWebExchange exchange) {
        return createFallbackResponse("Polling service is temporarily unavailable");
    }

    @GetMapping("/metrics")
    public Mono<ResponseEntity<Map<String, Object>>> metricsFallback(ServerWebExchange exchange) {
        return createFallbackResponse("Metrics service is temporarily unavailable");
    }

    @GetMapping("/billing")
    public Mono<ResponseEntity<Map<String, Object>>> billingFallback(ServerWebExchange exchange) {
        return createFallbackResponse("Billing service is temporarily unavailable");
    }

    @GetMapping("/ui")
    public Mono<ResponseEntity<Map<String, Object>>> uiFallback(ServerWebExchange exchange) {
        return createFallbackResponse("UI backend service is temporarily unavailable");
    }

    @GetMapping("/admin")
    public Mono<ResponseEntity<Map<String, Object>>> adminFallback(ServerWebExchange exchange) {
        return createFallbackResponse("Admin service is temporarily unavailable");
    }

    private Mono<ResponseEntity<Map<String, Object>>> createFallbackResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        response.put("error", "Service Unavailable");
        response.put("message", message);
        response.put("timestamp", System.currentTimeMillis());
        
        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(response));
    }
}