package com.atmospath.platform.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.atmospath.platform.account.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdempotencyService {
    private static final int MAX_KEY_LENGTH = 128;
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._:-]+$");

    private final IdempotencyRepository repository;

    @Autowired
    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
    }

    public Optional<UUID> findExistingResource(TenantContext context, String operation, String idempotencyKey) {
        var normalizedKey = normalize(idempotencyKey);
        if (normalizedKey.isEmpty()) {
            return Optional.empty();
        }
        return repository.findResourceId(context.tenantId(), operation, hash(normalizedKey.get()));
    }

    public void recordResource(TenantContext context, String operation, String idempotencyKey, UUID resourceId) {
        var normalizedKey = normalize(idempotencyKey);
        if (normalizedKey.isEmpty()) {
            return;
        }
        repository.saveResourceId(context.tenantId(), operation, hash(normalizedKey.get()), resourceId);
    }

    private static Optional<String> normalize(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        var normalized = idempotencyKey.trim();
        if (normalized.length() > MAX_KEY_LENGTH || !SAFE_KEY.matcher(normalized).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1-128 characters using letters, numbers, '.', '_', ':', or '-'.");
        }
        return Optional.of(normalized);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available.", exception);
        }
    }
}
