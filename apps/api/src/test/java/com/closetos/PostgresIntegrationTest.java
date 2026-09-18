package com.closetos;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
            "closetos.auth.client-id=closetos-test"
        })
@Import(PostgresIntegrationTest.DatabaseConfiguration.class)
public abstract class PostgresIntegrationTest {
    @MockitoBean protected JwtDecoder jwtDecoder;

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg18")
                            .asCompatibleSubstituteFor("postgres"));
        }
    }
}
