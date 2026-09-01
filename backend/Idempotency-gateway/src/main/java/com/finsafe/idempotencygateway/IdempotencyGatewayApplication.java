package com.finsafe.idempotencygateway;

import com.finsafe.idempotencygateway.config.IdempotencyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdempotencyGatewayApplication.class, args);
    }
}
