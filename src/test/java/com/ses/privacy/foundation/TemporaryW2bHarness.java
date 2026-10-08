package com.ses.privacy.foundation;

import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.MySQLContainer;

/** 3つの独立MySQLを同一harnessが所有するW2b test capability。 */
final class TemporaryW2bHarness {
    private final ApplicationContext context;
    private final W2bTestConnectionCapability journal;
    private final W2bTestConnectionCapability application;
    private final W2bTestConnectionCapability witness;

    private TemporaryW2bHarness(ApplicationContext context, MySQLContainer<?> journalContainer,
                                MySQLContainer<?> applicationContainer, MySQLContainer<?> witnessContainer) {
        W2bTestEnvironment.requireTest(context);
        this.context = context;
        this.journal = W2bTestConnectionCapability.fromOwnedContainer(journalContainer, context,
                W2bTestConnectionCapability.Store.JOURNAL);
        this.application = W2bTestConnectionCapability.fromOwnedContainer(applicationContainer, context,
                W2bTestConnectionCapability.Store.APPLICATION);
        this.witness = W2bTestConnectionCapability.fromOwnedContainer(witnessContainer, context,
                W2bTestConnectionCapability.Store.WITNESS);
        if (journal.containerId().equals(application.containerId()) || journal.containerId().equals(witness.containerId())
                || application.containerId().equals(witness.containerId())) {
            throw new SecurityException("journal/app/witnessは異なるcontainerでなければなりません");
        }
    }

    static TemporaryW2bHarness fromOwnedContainers(ApplicationContext context, MySQLContainer<?> journal,
                                                    MySQLContainer<?> application, MySQLContainer<?> witness) {
        return new TemporaryW2bHarness(context, journal, application, witness);
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

    SyntheticAttestorKeyFixture createAttestorKeyFixture() {
        requireTest();
        return SyntheticAttestorKeyFixture.generateForHarness(this);
    }

    ApplicationContext contextForKeyFixture() {
        requireTest();
        return context;
    }

    private void requireTest() {
        journal.requireAvailable();
    }
}
