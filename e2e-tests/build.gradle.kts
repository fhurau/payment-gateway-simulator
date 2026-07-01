// Not a runnable Spring Boot app - just a Testcontainers-based test suite against the whole
// docker-compose stack. Disable bootJar (which needs a main class) so the plain jar task runs
// instead when this module gets swept up in a root `./gradlew build`.
tasks.named("bootJar") {
    enabled = false
}
tasks.named<Jar>("jar") {
    enabled = true
}

dependencies {
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    testImplementation("org.postgresql:postgresql:42.7.4")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}

// The default `test` task only runs fast, non-e2e tests (there are none in this module - every
// class here is tagged "e2e"), so a bare `./gradlew build`/`./gradlew test` at the root stays
// fast. The real suite runs via the separate `e2eTest` task, invoked explicitly (and by CI).
tasks.test {
    useJUnitPlatform {
        excludeTags("e2e")
    }
}

tasks.register<Test>("e2eTest") {
    description = "Runs the Testcontainers E2E + contract test suite against the full docker-compose stack (§15)."
    group = "verification"
    useJUnitPlatform {
        includeTags("e2e")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("compose.file", rootProject.file("docker-compose.yml").absolutePath)
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // The stack takes a while to build+start; don't let JUnit split it across parallel JVM forks.
    maxParallelForks = 1
}
