package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyFoundationIsolationTest {
    @Test
    void missingProviderFailsClosedAndNoSyntheticOrRealProviderIsCalledByDefault() {
        PrivacyProviderRegistry registry = new PrivacyProviderRegistry(java.util.Map.of(),
                new SyntheticEnvironmentGuard(new org.springframework.mock.env.MockEnvironment()));
        assertThrows(SecurityException.class, () -> registry.require("synthetic-store"));
        assertThrows(SecurityException.class, () -> registry.require("real-provider"));
    }

    @Test
    void syntheticProviderIsRejectedByActualProdProfiles() {
        SyntheticPrivacyProvider synthetic = new SyntheticPrivacyProvider(java.util.Map.of(
                "subject-ref-synthetic", "generated-value"));
        org.springframework.mock.env.MockEnvironment prod = new org.springframework.mock.env.MockEnvironment();
        prod.setActiveProfiles("prod", "test");
        PrivacyProviderRegistry registry = new PrivacyProviderRegistry(java.util.Map.of(
                synthetic.providerId(), synthetic), new SyntheticEnvironmentGuard(prod));
        assertThrows(SecurityException.class, () -> registry.require(synthetic.providerId()));
        assertEquals(0, synthetic.invocationCount());
    }

    @Test
    void syntheticProviderIsRejectedWhenProdIsTheDefaultProfile() {
        SyntheticPrivacyProvider synthetic = new SyntheticPrivacyProvider(java.util.Map.of(
                "subject-ref-synthetic", "generated-value"));
        org.springframework.mock.env.MockEnvironment prodDefault = new org.springframework.mock.env.MockEnvironment();
        prodDefault.setDefaultProfiles("prod");
        PrivacyProviderRegistry registry = new PrivacyProviderRegistry(java.util.Map.of(
                synthetic.providerId(), synthetic), new SyntheticEnvironmentGuard(prodDefault));
        assertThrows(SecurityException.class, () -> registry.require(synthetic.providerId()));
        assertEquals(0, synthetic.invocationCount());
    }

    @Test
    void generatedTemporaryHarnessIgnoresDbUrlAndPathOverridesAndCleansOnlyItsOwnSandbox() throws Exception {
        Path projectRoot = Path.of("").toRealPath();
        Path outside = Files.createTempDirectory("nf07-outside-sentinel-");
        Path sentinel = outside.resolve("keep.txt");
        Files.writeString(sentinel, "outside-sentinel");
        String oldDbUrl = System.getProperty("DB_URL");
        String oldPath = System.getProperty("privacy.synthetic.path");
        System.setProperty("DB_URL", "jdbc:mysql://127.0.0.1:3306/real_database");
        System.setProperty("privacy.synthetic.path", outside.toString());
        try {
            Path root;
            try (TemporarySyntheticHarness harness = TemporarySyntheticHarness.open()) {
                root = harness.root();
                assertTrue(root.startsWith(projectRoot.resolve("target/nf07-synthetic").toAbsolutePath().normalize()));
                assertTrue(Files.exists(harness.storageRoot()));
                assertTrue(Files.exists(harness.journalRoot()));
                assertTrue(harness.temporaryDatabaseId().startsWith("nf07_synthetic_"));
                assertEquals(0, harness.externalProviderCalls());
                assertEquals(0, harness.provider().invocationCount());
                assertTrue(Files.readString(root.resolve("generated-fixture.manifest")).contains("sha256="));
                assertEquals("outside-sentinel", Files.readString(sentinel));
            }
            assertFalse(Files.exists(root));
            assertEquals("outside-sentinel", Files.readString(sentinel));
        } finally {
            restoreProperty("DB_URL", oldDbUrl);
            restoreProperty("privacy.synthetic.path", oldPath);
            Files.deleteIfExists(sentinel);
            Files.deleteIfExists(outside);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }
}
