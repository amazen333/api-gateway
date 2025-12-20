package com.linda.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.gateway.filter.timed")
public class TimedFilterProperties {
    private boolean enabled = true;
    private int order = 0;
    private long slowRequestThreshold = 1000; // ms
    private boolean recordRequestSize = true;
    private boolean recordResponseSize = true;
    private boolean addPerformanceHeaders = true;
    private List<String> excludedPaths = List.of("/health", "/actuator/**");
    private boolean enableDetailedMetrics = true;
    private boolean enablePercentiles = true;
    private List<Double> percentiles = List.of(0.5, 0.75, 0.95, 0.99, 0.999);
}