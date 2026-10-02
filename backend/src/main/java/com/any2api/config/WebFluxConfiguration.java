package com.any2api.config;

import java.util.concurrent.ExecutorService;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.web.reactive.config.BlockingExecutionConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class WebFluxConfiguration implements WebFluxConfigurer {
    private final TaskExecutorAdapter blockingExecutor;

    public WebFluxConfiguration(ExecutorService databaseExecutor) {
        blockingExecutor = new TaskExecutorAdapter(databaseExecutor);
    }

    @Override
    public void configureBlockingExecution(BlockingExecutionConfigurer configurer) {
        configurer.setExecutor(blockingExecutor);
    }
}
