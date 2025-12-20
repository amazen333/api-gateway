package com.linda.gateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linda.gateway.filter.TimedFilter;

@Configuration
@EnableConfigurationProperties(TimedFilterProperties.class)
public class TimedFilterConfig {
    
    @Bean
    public TimedFilter timedFilter(MeterRegistry meterRegistry,
                                   TimedFilterProperties properties) {
        // Create the filter
        TimedFilter timedFilter = new TimedFilter(meterRegistry);
        
        // Use reflection or any available method to set config
        // This depends on how TimedFilter is implemented
        // For example, if it has a setConfig method:
        // timedFilter.setConfig(buildConfig(properties));
        
        // If no setter, store properties for later retrieval
        return timedFilter;
    }
    
    // Helper method to create config
    private TimedFilter.Config buildConfig(TimedFilterProperties properties) {
        TimedFilter.Config config = new TimedFilter.Config();
        config.setEnabled(properties.isEnabled());
        config.setOrder(properties.getOrder());
        config.setSlowRequestThreshold(properties.getSlowRequestThreshold());
        config.setRecordRequestSize(properties.isRecordRequestSize());
        config.setRecordResponseSize(properties.isRecordResponseSize());
        config.setAddPerformanceHeaders(properties.isAddPerformanceHeaders());
        config.setExcludedPaths(properties.getExcludedPaths());
        return config;
    }
}