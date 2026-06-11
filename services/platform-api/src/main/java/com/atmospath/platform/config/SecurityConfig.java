package com.atmospath.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${atmospath.auth.enabled:false}") boolean authEnabled,
            @Value("${atmospath.auth.issuer-uri:}") String issuerUri) throws Exception {
        http.csrf(csrf -> csrf.disable());
        if (authEnabled) {
            http.authorizeHttpRequests(authorize -> authorize
                            .requestMatchers("/api/v1/me", "/api/v1/me/**").authenticated()
                            .anyRequest().permitAll())
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                            jwt.decoder(JwtDecoders.fromIssuerLocation(issuerUri))));
        } else {
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        }
        return http.build();
    }
}
