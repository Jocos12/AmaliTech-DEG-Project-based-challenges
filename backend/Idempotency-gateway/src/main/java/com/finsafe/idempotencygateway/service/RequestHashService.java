package com.finsafe.idempotencygateway.service;

import com.finsafe.idempotencygateway.dto.PaymentRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class RequestHashService {

    public String hash(PaymentRequest request) {
        String canonical = canonicalize(request);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String canonicalize(PaymentRequest request) {
        BigDecimal normalizedAmount = request.amount().stripTrailingZeros();
        String currency = request.currency().trim().toUpperCase(Locale.ROOT);
        return normalizedAmount.toPlainString() + "|" + currency;
    }
}
