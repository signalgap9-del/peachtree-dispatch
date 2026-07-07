package com.atmospath.platform.account;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantAuthorizationService {
    public void requireAuthenticated(TenantContext context) {
        if (!context.authenticated() || context.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
    }

    public void requireSavedAssetAccess(TenantContext context) {
        requireAuthenticated(context);
        if (!context.role().canManageSavedAssets()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant role cannot manage saved assets.");
        }
    }
}
