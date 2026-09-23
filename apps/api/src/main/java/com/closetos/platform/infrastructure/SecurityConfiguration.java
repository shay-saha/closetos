package com.closetos.platform.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, @Value("${closetos.auth.provider:cognito}") String provider)
            throws Exception {
        var authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(new AdministratorAuthorities(provider));
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers("/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers("/api/v1/admin", "/api/v1/admin/**")
                                        .hasAuthority("ROLE_CLOSETOS_ADMIN")
                                        .requestMatchers("/api/v1/**")
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .oauth2ResourceServer(
                        resource ->
                                resource.jwt(jwt -> jwt.jwtAuthenticationConverter(authentication))
                                        .authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        SecurityProblem.write(
                                                                request,
                                                                response,
                                                                401,
                                                                "AUTHENTICATION",
                                                                "Sign in to continue."))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        SecurityProblem.write(
                                                                request,
                                                                response,
                                                                403,
                                                                "AUTHORISATION",
                                                                "This action is not permitted.")))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${closetos.auth.client-id}") String clientId,
            @Value("${closetos.auth.provider:cognito}") String provider) {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        clientValidator(clientId, provider)));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> clientValidator(String clientId, String provider) {
        return jwt -> {
            boolean valid =
                    "cognito".equals(provider)
                            ? "access".equals(jwt.getClaimAsString("token_use"))
                                    && clientId.equals(jwt.getClaimAsString("client_id"))
                            : jwt.getAudience().contains(clientId);
            return valid && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                            new OAuth2Error(
                                    "invalid_token",
                                    "Token is not an access token for this application",
                                    null));
        };
    }
}
