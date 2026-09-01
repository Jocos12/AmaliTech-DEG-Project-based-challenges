package com.finsafe.idempotencygateway.scheduler;

import com.finsafe.idempotencygateway.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyExpirySchedulerTest {

    @Mock
    private IdempotencyRecordRepository repository;

    @InjectMocks
    private IdempotencyExpiryScheduler scheduler;

    @Test
    void purgeExpiredKeysDelegatesToRepository() {
        when(repository.deleteByExpiresAtBefore(any(LocalDateTime.class))).thenReturn(3);

        scheduler.purgeExpiredKeys();

        verify(repository).deleteByExpiresAtBefore(any(LocalDateTime.class));
    }
}
