package com.any2api;

import com.any2api.config.Any2ApiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = ReactiveUserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(Any2ApiProperties.class)
@ConfigurationPropertiesScan("com.any2api.provider")
public class Any2ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(Any2ApiApplication.class, args);
    }
}
