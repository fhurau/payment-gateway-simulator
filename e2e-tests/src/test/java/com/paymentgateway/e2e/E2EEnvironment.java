package com.paymentgateway.e2e;

import java.io.File;
import java.time.Duration;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The whole docker-compose stack (§1's "single command brings everything up"), reused as-is
 * rather than re-declaring individual Postgres/Kafka/Redis/app containers - it's already the
 * source of truth for how these services wire together. Started once per test JVM (the
 * Testcontainers "singleton container" pattern): every test class in this module shares it,
 * and it's left running for Ryuk to reap when the JVM exits, rather than each test class paying
 * its own multi-minute startup cost.
 */
public final class E2EEnvironment {

    public static final ComposeContainer ENVIRONMENT;

    static {
        File composeFile = new File(System.getProperty("compose.file"));
        ENVIRONMENT = new ComposeContainer(composeFile)
                .withLocalCompose(true)
                .withExposedService("api-gateway", 8080,
                        Wait.forHttp("/actuator/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(5)))
                .withExposedService("payment-processor", 8081,
                        Wait.forHttp("/actuator/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(5)))
                .withExposedService("notification-service", 8082,
                        Wait.forHttp("/actuator/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(5)))
                .withExposedService("postgres", 5432,
                        Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)));
        ENVIRONMENT.start();
    }

    private E2EEnvironment() {
    }

    public static String apiGatewayBaseUrl() {
        return "http://" + ENVIRONMENT.getServiceHost("api-gateway", 8080) + ":"
                + ENVIRONMENT.getServicePort("api-gateway", 8080);
    }

    public static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + ENVIRONMENT.getServiceHost("postgres", 5432) + ":"
                + ENVIRONMENT.getServicePort("postgres", 5432) + "/" + database;
    }
}
