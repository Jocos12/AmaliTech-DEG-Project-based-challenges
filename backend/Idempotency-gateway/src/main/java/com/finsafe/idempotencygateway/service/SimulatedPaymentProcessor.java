package com.finsafe.idempotencygateway.service;

import com.finsafe.idempotencygateway.config.IdempotencyProperties;
import com.finsafe.idempotencygateway.dto.PaymentRequest;
import com.finsafe.idempotencygateway.dto.PaymentResponse;
import com.finsafe.idempotencygateway.exception.PaymentProcessingException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class SimulatedPaymentProcessor {

    private final IdempotencyProperties properties;

    public SimulatedPaymentProcessor(IdempotencyProperties properties) {
        this.properties = properties;
    }

    public PaymentResponse charge(PaymentRequest request) {
        try {
            Thread.sleep(properties.getProcessingDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentProcessingException("Payment processing was interrupted", e);
        }
        return new PaymentResponse("Charged " + formatAmount(request.amount()) + " " + request.currency().trim());
    }

    private String formatAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
