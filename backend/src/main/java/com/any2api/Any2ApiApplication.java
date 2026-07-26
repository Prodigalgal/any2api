package com.any2api;

import com.any2api.config.Any2ApiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(Any2ApiProperties.class)
public class Any2ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(Any2ApiApplication.class, args);
    }
}

