package com.ses.privacy.foundation;

import org.springframework.core.env.Environment;
import org.testcontainers.containers.MySQLContainer;

/** 3つの独立MySQLを同一harnessが所有するW2b test capability。 */
final class TemporaryW2bHarness {
    private final W2bTestConnectionCapability journal;
    private final W2bTestConnectionCapability application;
    private final W2bTestConnectionCapability witness;

    private TemporaryW2bHarness(Environment environment, MySQLContainer<?> journalContainer,
                                MySQLContainer<?> applicationContainer, MySQLContainer<?> witnessContainer) {
        W2bTestEnvironment.requireTest(environment);
        this.journal = W2bTestConnectionCapability.fromOwnedContainer(journalContainer, environment,
                W2bTestConnectionCapability.Store.JOURNAL);
        this.application = W2bTestConnectionCapability.fromOwnedContainer(applicationContainer, environment,
                W2bTestConnectionCapability.Store.APPLICATION);
        this.witness = W2bTestConnectionCapability.fromOwnedContainer(witnessContainer, environment,
                W2bTestConnectionCapability.Store.WITNESS);
        if (journal.containerId().equals(application.containerId()) || journal.containerId().equals(witness.containerId())
                || application.containerId().equals(witness.containerId())) {
            throw new SecurityException("journal/app/witnessは異なるcontainerでなければなりません");
        }
    }

    static TemporaryW2bHarness fromOwnedContainers(Environment environment, MySQLContainer<?> journal,
                                                    MySQLContainer<?> application, MySQLContainer<?> witness) {
        return new TemporaryW2bHarness(environment, journal, application, witness);
    }

    W2bTestConnectionCapability journal() { requireTest(); return journal; }
    W2bTestConnectionCapability application() { requireTest(); return application; }
    W2bTestConnectionCapability witness() { requireTest(); return witness; }

    void initializeSchemas() {
        requireTest();
        journal.initializeSchema();
        application.initializeSchema();
        witness.initializeSchema();
    }

    private void requireTest() {
        journal.requireAvailable();
    }
}
