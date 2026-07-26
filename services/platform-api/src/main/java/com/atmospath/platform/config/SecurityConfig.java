package com.atmospath.platform.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<JwtDecoder> jwtDecoderProvider,
            @Value("${atmospath.auth.enabled:false}") boolean authEnabled,
            @Value("${atmospath.auth.issuer-uri:}") String issuerUri) throws Exception {
        http.csrf(csrf -> csrf.disable());
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny()));
        if (authEnabled) {
            // Prefer a JwtDecoder bean when one exists (tests); otherwise
            // discover it from the issuer, as before.
            JwtDecoder jwtDecoder = jwtDecoderProvider.getIfAvailable(
                    () -> JwtDecoders.fromIssuerLocation(issuerUri));
            http.authorizeHttpRequests(authorize -> authorize
                            // GDPR self-service: data portability and right to erasure.
                            .requestMatchers("/api/v1/me/data-export").authenticated()
                            .requestMatchers("/api/v1/me/account").authenticated()
                            .requestMatchers("/api/v1/me", "/api/v1/me/**").authenticated()
                            .requestMatchers("/api/v1/llm/**").authenticated()
                            .requestMatchers("/api/v1/billing/checkout",
                                    "/api/v1/billing/subscription",
                                    "/api/v1/billing/portal").authenticated()
                            .requestMatchers("/api/v1/admin/**").denyAll()
                            .anyRequest().permitAll())
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                            jwt.decoder(jwtDecoder)));
        } else {
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        }
        return http.build();
    }
}
