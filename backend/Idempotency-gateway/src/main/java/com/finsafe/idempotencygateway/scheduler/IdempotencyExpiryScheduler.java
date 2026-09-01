package com.finsafe.idempotencygateway.scheduler;

import com.finsafe.idempotencygateway.repository.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class IdempotencyExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyExpiryScheduler.class);

    private final IdempotencyRecordRepository repository;

    public IdempotencyExpiryScheduler(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${idempotency.purge-interval-ms:3600000}")
    public void purgeExpiredKeys() {
        int removed = repository.deleteByExpiresAtBefore(LocalDateTime.now());
        if (removed > 0) {
            log.info("Purged {} expired idempotency key(s)", removed);
        }
    }
}
