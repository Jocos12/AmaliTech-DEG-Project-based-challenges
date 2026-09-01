package com.finsafe.idempotencygateway.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Persisted outcome of a processed payment, keyed by the client-supplied idempotency key.
 * {@code expiresAt} supports TTL/expiry so the table does not grow without bound.
 */
@Entity
@Table(
        name = "idempotency_records",
        indexes = {
                @Index(name = "uk_idempotency_key", columnList = "idempotency_key", unique = true)
        }
)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "request_body_hash", nullable = false, length = 64)
    private String requestBodyHash;

    @Column(name = "response_status", nullable = false)
    private Integer responseStatus;

    @Lob
    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public IdempotencyRecord() {
    }

    public IdempotencyRecord(
            String idempotencyKey,
            String requestBodyHash,
            Integer responseStatus,
            String responseBody,
            LocalDateTime createdAt,
            LocalDateTime expiresAt
    ) {
        this.idempotencyKey = idempotencyKey;
        this.requestBodyHash = requestBodyHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestBodyHash() {
        return requestBodyHash;
    }

    public void setRequestBodyHash(String requestBodyHash) {
        this.requestBodyHash = requestBodyHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public static final class Builder {
        private String idempotencyKey;
        private String requestBodyHash;
        private Integer responseStatus;
        private String responseBody;
        private LocalDateTime createdAt;
        private LocalDateTime expiresAt;

        private Builder() {
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder requestBodyHash(String requestBodyHash) {
            this.requestBodyHash = requestBodyHash;
            return this;
        }

        public Builder responseStatus(Integer responseStatus) {
            this.responseStatus = responseStatus;
            return this;
        }

        public Builder responseBody(String responseBody) {
            this.responseBody = responseBody;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder expiresAt(LocalDateTime expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public IdempotencyRecord build() {
            return new IdempotencyRecord(
                    idempotencyKey,
                    requestBodyHash,
                    responseStatus,
                    responseBody,
                    createdAt,
                    expiresAt
            );
        }
    }
}
