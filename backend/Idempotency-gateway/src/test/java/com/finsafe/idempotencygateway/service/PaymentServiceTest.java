package com.finsafe.idempotencygateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsafe.idempotencygateway.config.IdempotencyProperties;
import com.finsafe.idempotencygateway.dto.PaymentRequest;
import com.finsafe.idempotencygateway.dto.PaymentResponse;
import com.finsafe.idempotencygateway.exception.IdempotencyConflictException;
import com.finsafe.idempotencygateway.model.IdempotencyRecord;
import com.finsafe.idempotencygateway.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String KEY = "pay-123";
    private static final PaymentRequest REQUEST = new PaymentRequest(new BigDecimal("100"), "GHS");
    private static final PaymentResponse CHARGED = new PaymentResponse("Charged 100 GHS");

    @Mock
    private IdempotencyRecordRepository repository;

    @Mock
    private SimulatedPaymentProcessor paymentProcessor;

    private RequestHashService requestHashService;
    private ObjectMapper objectMapper;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        requestHashService = new RequestHashService();
        objectMapper = new ObjectMapper();
        IdempotencyProperties properties = new IdempotencyProperties();
        properties.getKey().setTtlHours(24);
        paymentService = new PaymentService(
                repository,
                requestHashService,
                paymentProcessor,
                objectMapper,
                properties
        );
    }

    @Test
    void newKeyProcessesAndStoresResponse() {
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(paymentProcessor.charge(REQUEST)).thenReturn(CHARGED);
        when(repository.saveAndFlush(any(IdempotencyRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<PaymentResponse> response = paymentService.processPayment(KEY, REQUEST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(CHARGED);
        assertThat(response.getHeaders().getFirst(PaymentService.CACHE_HIT_HEADER)).isNull();
        verify(paymentProcessor, times(1)).charge(REQUEST);
        verify(repository).saveAndFlush(any(IdempotencyRecord.class));
    }

    @Test
    void duplicateKeySameBodyReturnsCachedResponseWithoutReprocessing() throws Exception {
        String hash = requestHashService.hash(REQUEST);
        IdempotencyRecord record = storedRecord(hash, CHARGED);
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(record));

        ResponseEntity<PaymentResponse> response = paymentService.processPayment(KEY, REQUEST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(CHARGED);
        assertThat(response.getHeaders().getFirst(PaymentService.CACHE_HIT_HEADER)).isEqualTo("true");
        verify(paymentProcessor, never()).charge(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateKeyDifferentBodyThrowsConflict() throws Exception {
        PaymentRequest other = new PaymentRequest(new BigDecimal("500"), "GHS");
        String originalHash = requestHashService.hash(REQUEST);
        IdempotencyRecord record = storedRecord(originalHash, CHARGED);
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> paymentService.processPayment(KEY, other))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessage(IdempotencyConflictException.MESSAGE);
        verify(paymentProcessor, never()).charge(any());
    }

    @Test
    void uniqueConstraintRaceReplaysExistingRecord() throws Exception {
        when(repository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(storedRecord(requestHashService.hash(REQUEST), CHARGED)));
        when(paymentProcessor.charge(REQUEST)).thenReturn(CHARGED);
        when(repository.saveAndFlush(any(IdempotencyRecord.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        ResponseEntity<PaymentResponse> response = paymentService.processPayment(KEY, REQUEST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(CHARGED);
        assertThat(response.getHeaders().getFirst(PaymentService.CACHE_HIT_HEADER)).isEqualTo("true");
    }

    @Test
    void concurrentRequestsWithSameKeyProcessOnceAndShareResult() throws Exception {
        AtomicInteger chargeCalls = new AtomicInteger();
        when(repository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(IdempotencyRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentProcessor.charge(any())).thenAnswer(invocation -> {
            chargeCalls.incrementAndGet();
            Thread.sleep(250);
            return CHARGED;
        });

        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier start = new CyclicBarrier(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<ResponseEntity<PaymentResponse>> results = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    results.add(paymentService.processPayment(KEY, REQUEST));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }));
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        for (Future<?> future : futures) {
            future.get(1, TimeUnit.SECONDS);
        }

        assertThat(chargeCalls.get()).isEqualTo(1);
        assertThat(results).hasSize(threadCount);
        assertThat(results).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED);
        assertThat(results).allMatch(r -> CHARGED.equals(r.getBody()));

        long cacheHits = results.stream()
                .filter(r -> "true".equals(r.getHeaders().getFirst(PaymentService.CACHE_HIT_HEADER)))
                .count();
        assertThat(cacheHits).isEqualTo(threadCount - 1);
        verify(repository, times(1)).saveAndFlush(any(IdempotencyRecord.class));
    }

    private IdempotencyRecord storedRecord(String hash, PaymentResponse body) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        return IdempotencyRecord.builder()
                .idempotencyKey(KEY)
                .requestBodyHash(hash)
                .responseStatus(HttpStatus.CREATED.value())
                .responseBody(objectMapper.writeValueAsString(body))
                .createdAt(now)
                .expiresAt(now.plusHours(24))
                .build();
    }
}
