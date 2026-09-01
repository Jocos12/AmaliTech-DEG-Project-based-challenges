package com.finsafe.idempotencygateway.controller;

import com.finsafe.idempotencygateway.IdempotencyGatewayApplication;
import com.finsafe.idempotencygateway.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = IdempotencyGatewayApplication.class)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PaymentControllerIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdempotencyRecordRepository repository;

    @Test
    void processPaymentHappyPathDuplicateConflictAndMissingHeader() throws Exception {
        String key = "it-" + UUID.randomUUID();
        String body = "{\"amount\":100,\"currency\":\"GHS\"}";

        mockMvc.perform(post("/process-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Idempotency-Key header is required."));

        mockMvc.perform(post("/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed JSON request body."));

        mockMvc.perform(post("/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("Charged 100 GHS"))
                .andExpect(header().string("X-Cache-Hit", nullValue()));

        mockMvc.perform(post("/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("Charged 100 GHS"))
                .andExpect(header().string("X-Cache-Hit", "true"));

        mockMvc.perform(post("/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500,\"currency\":\"GHS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("Idempotency key already used for a different request body."));

        org.assertj.core.api.Assertions.assertThat(repository.findByIdempotencyKey(key)).isPresent();
    }

    @Test
    void concurrentInFlightRequestsShareSingleProcessingResult() throws Exception {
        String key = "inflight-" + UUID.randomUUID();
        String body = "{\"amount\":250,\"currency\":\"GHS\"}";

        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(post("/process-payment")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10);

        CompletableFuture<MvcResult> second = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(post("/process-payment")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        MvcResult a = first.get(5, TimeUnit.SECONDS);
        MvcResult b = second.get(5, TimeUnit.SECONDS);

        org.assertj.core.api.Assertions.assertThat(a.getResponse().getStatus()).isEqualTo(201);
        org.assertj.core.api.Assertions.assertThat(b.getResponse().getStatus()).isEqualTo(201);
        org.assertj.core.api.Assertions.assertThat(a.getResponse().getContentAsString())
                .isEqualTo(b.getResponse().getContentAsString())
                .contains("Charged 250 GHS");
        org.assertj.core.api.Assertions.assertThat(repository.findByIdempotencyKey(key)).isPresent();
    }
}
