package org.elyonar.fincore.core.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Core deployable: customer, product and orchestration in one process.
 *
 * <p>Component scanning starts at {@code org.elyonar.fincore.core} so every module is found, and
 * at {@code org.elyonar.fincore.auth} so the shared authorization library's filter is wired in.
 * Nothing else belongs here — this module assembles, it does not decide.
 */
@SpringBootApplication(scanBasePackages = {"org.elyonar.fincore.core", "org.elyonar.fincore.auth"})
public class CoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}
