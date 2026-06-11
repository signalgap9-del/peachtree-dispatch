package com.atmospath.platform;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@ConditionalOnProperty(name = "atmospath.auth.enabled", havingValue = "true")
public class MeController {
    @GetMapping
    Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        var result = new LinkedHashMap<String, Object>();
        result.put("subject", jwt.getSubject());
        result.put("email", jwt.getClaimAsString("email"));
        result.put("username", jwt.getClaimAsString("cognito:username"));
        return result;
    }
}
