package com.ses.privacy.foundation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** 生成データをtarget配下の一時領域だけで扱う試験harness。外部設定を読み込まない。 */
final class TemporarySyntheticHarness implements AutoCloseable {
    private final Path allowedRoot;
    private final Path root;
    private final Path storageRoot;
    private final Path journalRoot;
    private final String temporaryDatabaseId;
    private final SyntheticPrivacyProvider provider;

    private TemporarySyntheticHarness(Path allowedRoot, Path root, Path storageRoot, Path journalRoot,
                                      String temporaryDatabaseId, SyntheticPrivacyProvider provider) {
        this.allowedRoot = allowedRoot;
        this.root = root;
        this.storageRoot = storageRoot;
        this.journalRoot = journalRoot;
        this.temporaryDatabaseId = temporaryDatabaseId;
        this.provider = provider;
    }

    static TemporarySyntheticHarness open() throws IOException {
        Path projectRoot = Path.of("").toRealPath();
        Path target = projectRoot.resolve("target").normalize();
        Path allowed = target.resolve("nf07-synthetic");
        if (Files.isSymbolicLink(target) || Files.isSymbolicLink(allowed)) {
            throw new SecurityException("temporary harness rootにsymlinkは使えません");
        }
        Files.createDirectories(allowed);
        Path realAllowed = allowed.toRealPath();
        if (!realAllowed.startsWith(projectRoot) || !realAllowed.equals(allowed.toAbsolutePath().normalize())) {
            throw new SecurityException("temporary harness rootがworktree外です");
        }
        Path root = realAllowed.resolve(UUID.randomUUID().toString());
        Path storage = root.resolve("object-store");
        Path journal = root.resolve("journal");
        Files.createDirectories(storage);
        Files.createDirectories(journal);
        String generated = "fixture=synthetic-contact-v1\nvalue=redacted-generated-value\n";
        String manifestHash = HexFormat.of().formatHex(sha256(generated.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(root.resolve("generated-fixture.manifest"), generated + "sha256=" + manifestHash + "\n");
        SyntheticPrivacyProvider provider = new SyntheticPrivacyProvider(Map.of(
                "subject-ref-synthetic", "generated-subject-value"));
        return new TemporarySyntheticHarness(realAllowed, root, storage, journal,
                "nf07_synthetic_" + UUID.randomUUID().toString().replace("-", ""), provider);
    }

    Path root() { return root; }
    Path storageRoot() { return storageRoot; }
    Path journalRoot() { return journalRoot; }
    String temporaryDatabaseId() { return temporaryDatabaseId; }
    SyntheticPrivacyProvider provider() { return provider; }
    int externalProviderCalls() { return 0; }

    @Override
    public void close() throws IOException {
        Path realRoot = root.toRealPath();
        if (!realRoot.startsWith(allowedRoot) || Files.isSymbolicLink(root)) {
            throw new SecurityException("temporary harness cleanup対象が許可root外です");
        }
        try (var paths = Files.walk(realRoot)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        } catch (java.io.UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDKにSHA-256がありません", impossible);
        }
    }
}
