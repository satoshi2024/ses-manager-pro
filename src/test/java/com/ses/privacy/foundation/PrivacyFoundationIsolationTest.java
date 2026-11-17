package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyFoundationIsolationTest {
    /** foundation に置いてはならない Spring の登録・注入アノテーション（W3-14 §3.1.1）。 */
    private static final java.util.List<String> FORBIDDEN_SPRING_ANNOTATIONS = java.util.List.of(
            "@Component", "@Service", "@Repository", "@Controller", "@RestController", "@Configuration",
            "@Bean", "@Autowired", "@ConfigurationProperties", "@Transactional", "@Scheduled", "@Profile",
            "@ConditionalOn", "@EventListener", "@Value(");

    @Test
    void foundationにはSpringの登録と注入のアノテーションが無い() throws Exception {
        Path foundation = Path.of("src/main/java/com/ses/privacy/foundation");
        java.util.List<String> violations = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(foundation)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                for (String annotation : FORBIDDEN_SPRING_ANNOTATIONS) {
                    if (source.lines().map(String::trim).anyMatch(line -> line.startsWith(annotation))) {
                        violations.add(file.getFileName() + " " + annotation);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "foundationにSpring登録のアノテーションがあります: " + violations);
    }

    /**
     * public な factory と解除の入口は、どの registry・失効 provider・proof を渡すかを呼出し側が決められるため、呼出し元を
     * Spring 登録（registry は空・失効は常に UNKNOWN）と解除の service に限る（NF07-REL review P3-3）。呼出し元を増やす変更は
     * ここに理由と一緒に足し、review を通す。policy 登録の verifier（NF07-REG）も同じ理由で Spring 登録だけに限る。
     */
    @Test
    void 解除のproofとJournalの本番部品の呼出し元はSpring登録と解除のserviceに限る() throws Exception {
        java.util.Map<String, java.util.Set<String>> allowed = java.util.Map.of(
                "policyRegistrationVerifier(", java.util.Set.of(
                        "com/ses/privacy/foundation/ProductionPrivacyComponents.java",
                        "com/ses/privacy/production/retention/policy/PrivacyPolicyRegistrationConfiguration.java"),
                "new ProductionPolicyRegistrationVerifier(", java.util.Set.of(
                        "com/ses/privacy/foundation/ProductionPrivacyComponents.java"),
                "new VerifiedPolicyRegistration(", java.util.Set.of(
                        "com/ses/privacy/foundation/ProductionPolicyRegistrationVerifier.java"),
                "holdReleaseVerifier(", java.util.Set.of("com/ses/privacy/foundation/ProductionPrivacyComponents.java",
                        "com/ses/privacy/production/retention/hold/PrivacyHoldConfiguration.java"),
                "appendHoldRelease(", java.util.Set.of("com/ses/privacy/foundation/HoldReleaseJournalPort.java",
                        "com/ses/privacy/foundation/ProductionPrivacyJournalAdapter.java",
                        "com/ses/privacy/production/retention/hold/PrivacyHoldReleaseService.java"),
                "productionPrivacyJournal(", java.util.Set.of("com/ses/privacy/foundation/ProductionPrivacyComponents.java",
                        "com/ses/privacy/production/retention/hold/PrivacyJournalConnections.java"),
                "new HoldReleaseProofVerifier(", java.util.Set.of(
                        "com/ses/privacy/foundation/ProductionPrivacyComponents.java"),
                "new VerifiedHoldRelease(", java.util.Set.of("com/ses/privacy/foundation/HoldReleaseProofVerifier.java"));
        // 方法参照（{@code ::appendHoldRelease} 等）は呼出しの "(" を含まないため、どこにも書かせない（delta review P3-6）
        java.util.List<String> forbiddenReferences = java.util.List.of("::holdReleaseVerifier", "::appendHoldRelease",
                "::productionPrivacyJournal", "::policyRegistrationVerifier");
        Path root = Path.of("src/main/java");
        java.util.List<String> violations = new java.util.ArrayList<>();
        java.util.Map<String, java.util.Set<String>> found = new java.util.HashMap<>();
        try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                String relative = root.relativize(file).toString().replace('\\', '/');
                for (String reference : forbiddenReferences) {
                    if (source.contains(reference)) {
                        violations.add(relative + " " + reference);
                    }
                }
                for (java.util.Map.Entry<String, java.util.Set<String>> entry : allowed.entrySet()) {
                    if (source.contains(entry.getKey())) {
                        found.computeIfAbsent(entry.getKey(), key -> new java.util.HashSet<>()).add(relative);
                        if (!entry.getValue().contains(relative)) {
                            violations.add(relative + " " + entry.getKey());
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "許可されていない呼出し元があります: " + violations);
        // 検査が空振りしていない（許可した場所に実際の呼出し・定義がある）
        assertEquals(allowed, found, "許可した呼出し元と実際の出現が一致しません");
    }

    @Test
    void production部品はProductionPrivacyComponents経由でしか生成できない() throws Exception {
        for (Class<?> type : java.util.List.of(ProductionPolicyReadonlyQueryService.class,
                ProductionApprovalEnvelopeVerifier.class, SecurityContextCallerContextProvider.class,
                ProductionPolicyEnvelopeDecoder.class, ProductionEnvironmentGuard.class,
                ProductionPrivacyJournalAdapter.class, HoldReleaseProofVerifier.class,
                ProductionPolicyRegistrationVerifier.class, PrivacyTrustReadiness.class)) {
            assertFalse(java.lang.reflect.Modifier.isPublic(type.getModifiers()), type.getSimpleName() + " はpackage-private");
            for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertFalse(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()),
                        type.getSimpleName() + " のconstructorはpublicにしない");
            }
        }
        // NF07-REG: 検証済みの envelope は verifier だけが作り、public constructor を持たない
        assertEquals(0, VerifiedPolicyRegistration.class.getConstructors().length);
        assertEquals(0, PolicyRegistrationRejectedException.class.getConstructors().length);
        assertTrue(java.lang.reflect.Modifier.isPublic(ProductionPrivacyComponents.class.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isFinal(ProductionPrivacyComponents.class.getModifiers()));
        for (java.lang.reflect.Constructor<?> constructor : ProductionPrivacyComponents.class.getDeclaredConstructors()) {
            assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        }
        // foundation 外の production 部品は foundation の package-private 型を参照できない（compileで保証される）。
        // 公開しているのは port・record・factory だけであることを固定する。
        for (Class<?> type : java.util.List.of(ProductionPolicyCatalogPort.class, CurrentPolicySource.class,
                ProductionPolicyTrustRegistry.class, PolicyLookupKey.class, ProductionCatalogBinding.class,
                CurrentPolicySnapshot.class)) {
            assertTrue(java.lang.reflect.Modifier.isPublic(type.getModifiers()), type.getSimpleName());
        }
    }

    @Test
    void 本番Journal_adapterはprod以外では接続前に生成を拒否しhold解除も接続前に拒否する() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        javax.sql.DataSource tripwire = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        attempts.incrementAndGet();
                        throw new AssertionError("接続前に拒否される必要があります");
                    }
                    return null;
                });
        for (String[] active : java.util.List.of(new String[]{"test"}, new String[]{}, new String[]{"dev"})) {
            org.springframework.mock.env.MockEnvironment environment = new org.springframework.mock.env.MockEnvironment();
            environment.setActiveProfiles(active);
            assertThrows(SecurityException.class, () -> ProductionPrivacyComponents.productionPrivacyJournal(
                    environment, tripwire, java.time.Clock.systemUTC()));
        }
        org.springframework.mock.env.MockEnvironment prod = new org.springframework.mock.env.MockEnvironment();
        prod.setActiveProfiles("prod");
        PrivacyJournalPort journal = ProductionPrivacyComponents.productionPrivacyJournal(
                prod, tripwire, java.time.Clock.systemUTC());
        assertNotNull(journal);
        PrivacyJournalPort.JournalPartition partition = new PrivacyJournalPort.JournalPartition("tenant-a", "subject-a");
        assertThrows(SecurityException.class, () -> journal.append(PrivacyJournalPort.JournalCommand.create(
                "release-1", partition, 1, PrivacyJournalPort.CommandType.HOLD_RELEASED,
                null, null, null, null, 0, null)));
        assertEquals(0, attempts.get(), "生成時とhold解除の拒否ではJournalへ接続しない");
        assertEquals(0, ProductionPrivacyJournalAdapter.class.getConstructors().length,
                "本番adapterにpublic constructorがない");
        // NF07-REL: 解除は検証済み proof を受け取る別入口だけ。proof は verifier だけが作り、public constructor を持たない
        assertTrue(journal instanceof HoldReleaseJournalPort, "本番adapterは解除の入口を持つ");
        assertEquals(0, VerifiedHoldRelease.class.getConstructors().length, "解除proofにpublic constructorがない");
        assertFalse(HoldReleaseJournalPort.class.isAssignableFrom(JdbcPrivacyJournalAdapter.class),
                "合成adapterは解除の入口を持たない（I-6）");
    }

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
