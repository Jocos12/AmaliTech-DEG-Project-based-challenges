package com.finsafe.idempotencygateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsafe.idempotencygateway.config.IdempotencyProperties;
import com.finsafe.idempotencygateway.dto.PaymentRequest;
import com.finsafe.idempotencygateway.dto.PaymentResponse;
import com.finsafe.idempotencygateway.exception.IdempotencyConflictException;
import com.finsafe.idempotencygateway.exception.MissingIdempotencyKeyException;
import com.finsafe.idempotencygateway.model.IdempotencyRecord;
import com.finsafe.idempotencygateway.repository.IdempotencyRecordRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PaymentService {

    public static final String CACHE_HIT_HEADER = "X-Cache-Hit";

    private final IdempotencyRecordRepository repository;
    private final RequestHashService requestHashService;
    private final SimulatedPaymentProcessor paymentProcessor;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties properties;
    private final ConcurrentHashMap<String, InFlightRequest> inFlight = new ConcurrentHashMap<>();

    public PaymentService(
            IdempotencyRecordRepository repository,
            RequestHashService requestHashService,
            SimulatedPaymentProcessor paymentProcessor,
            ObjectMapper objectMapper,
            IdempotencyProperties properties
    ) {
        this.repository = repository;
        this.requestHashService = requestHashService;
        this.paymentProcessor = paymentProcessor;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ResponseEntity<PaymentResponse> processPayment(String idempotencyKey, PaymentRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }

        String requestHash = requestHashService.hash(request);

        Optional<IdempotencyRecord> existing = findActive(idempotencyKey);
        if (existing.isPresent()) {
            return replayStored(existing.get(), requestHash);
        }

        InFlightRequest candidate = new InFlightRequest(requestHash, new CompletableFuture<>());
        InFlightRequest current = inFlight.putIfAbsent(idempotencyKey, candidate);

        if (current != null) {
            return awaitInFlight(current, requestHash);
        }

        try {
            Optional<IdempotencyRecord> raced = findActive(idempotencyKey);
            if (raced.isPresent()) {
                ResponseEntity<PaymentResponse> replayed = replayStored(raced.get(), requestHash);
                candidate.future().complete(stripCacheHit(replayed));
                return replayed;
            }

            PaymentResponse body = paymentProcessor.charge(request);
            LocalDateTime now = LocalDateTime.now();
            IdempotencyRecord record = IdempotencyRecord.builder()
                    .idempotencyKey(idempotencyKey)
                    .requestBodyHash(requestHash)
                    .responseStatus(HttpStatus.CREATED.value())
                    .responseBody(writeJson(body))
                    .createdAt(now)
                    .expiresAt(now.plusHours(properties.getKey().getTtlHours()))
                    .build();

            try {
                repository.saveAndFlush(record);
            } catch (DataIntegrityViolationException ex) {
                IdempotencyRecord winner = repository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> ex);
                ResponseEntity<PaymentResponse> replayed = replayStored(winner, requestHash);
                candidate.future().complete(stripCacheHit(replayed));
                return replayed;
            }

            ResponseEntity<PaymentResponse> response = ResponseEntity.status(HttpStatus.CREATED).body(body);
            candidate.future().complete(response);
            return response;
        } catch (RuntimeException ex) {
            candidate.future().completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(idempotencyKey, candidate);
        }
    }

    private Optional<IdempotencyRecord> findActive(String idempotencyKey) {
        Optional<IdempotencyRecord> found = repository.findByIdempotencyKey(idempotencyKey);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord record = found.get();
        if (record.isExpired(LocalDateTime.now())) {
            repository.delete(record);
            return Optional.empty();
        }
        return found;
    }

    private ResponseEntity<PaymentResponse> awaitInFlight(InFlightRequest inFlightRequest, String requestHash) {
        if (!inFlightRequest.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        try {
            ResponseEntity<PaymentResponse> original = inFlightRequest.future().join();
            return withCacheHit(original);
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw ex;
        }
    }

    private ResponseEntity<PaymentResponse> replayStored(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestBodyHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        return ResponseEntity.status(record.getResponseStatus())
                .header(CACHE_HIT_HEADER, "true")
                .body(readJson(record.getResponseBody()));
    }

    private ResponseEntity<PaymentResponse> withCacheHit(ResponseEntity<PaymentResponse> original) {
        return ResponseEntity.status(original.getStatusCode())
                .headers(original.getHeaders())
                .header(CACHE_HIT_HEADER, "true")
                .body(original.getBody());
    }

    private ResponseEntity<PaymentResponse> stripCacheHit(ResponseEntity<PaymentResponse> entity) {
        return ResponseEntity.status(entity.getStatusCode()).body(entity.getBody());
    }

    private String writeJson(PaymentResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize payment response", e);
        }
    }

    private PaymentResponse readJson(String json) {
        try {
            return objectMapper.readValue(json, PaymentResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to deserialize stored payment response", e);
        }
    }

    private record InFlightRequest(String requestHash, CompletableFuture<ResponseEntity<PaymentResponse>> future) {
    }
}
