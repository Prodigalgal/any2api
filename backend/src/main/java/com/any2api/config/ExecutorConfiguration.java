package com.any2api.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfiguration {

    @Bean(destroyMethod = "close")
    ExecutorService databaseExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

