package com.ses.privacy.production.retention.trust;

import com.ses.privacy.production.PrivacyFeatureProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.bytes;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.document;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.ed25519;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.policyAnchor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF07 の trust（D-03 = AWS KMS）の起動時の検査（{@link PrivacyTrustStartupValidator}）。NF07 の既存の起動検査と同じく、flag が
 * OFF なら何も検査せず、ON のときは設定・文書の構造的な不足で起動を止め、時間要因（鮮度の上限・有効期間）だけなら起動を
 * 続ける（2026-11-14 の決定）。鍵は test-only。
 */
class PrivacyTrustStartupValidatorTest {

    private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");

    @TempDir
    Path directory;

    private Path file;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final KeyPair key = ed25519();

    @BeforeEach
    void setUp() {
        file = directory.resolve("nf07-trust.json").toAbsolutePath();
    }

    @Test
    void flagがOFFなら壊れた文書でも起動を止めず未設定のtrustはONでも違反にしない() throws IOException {
        Files.writeString(file, "{", StandardCharsets.UTF_8);
        assertThatCode(() -> validator(false, kms("1d")).validate()).doesNotThrowAnyException();
        assertThatCode(() -> validator(true, new UnconfiguredPrivacyTrustSource()).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void flagがONなら文書と設定の構造的な不足で固定のcodeだけを出して起動を止める() throws IOException {
        Files.writeString(file, "{", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> validator(true, kms("1d")).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trust.kms.document.notJson");

        Files.delete(file);
        assertThatThrownBy(() -> validator(true, kms("1d")).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trust.kms.document.unreadable");

        KeyPair other = ed25519();
        byte[] ph = bytes(document(NOW, List.of(policyAnchor("policy-issuer", "k1", other, "ACTIVE",
                NOW.minusSeconds(60), NOW.plusSeconds(86_400), "default")
                .put("signingAlgorithm", "ED25519_PH_SHA_512")), List.of(), Map.of()));
        Files.write(file, ph);
        assertThatThrownBy(() -> validator(true, kms("1d")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trust.kms.anchor.signingAlgorithm at $.policyAnchors[0].signingAlgorithm")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KmsTrustTestDocuments.spki(other)));

        writeValid(NOW);
        assertThatThrownBy(() -> validator(true, kms(null)).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trust.kms.maxDocumentAge.unset");
        assertThatThrownBy(() -> validator(true, kms("40d")).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trust.kms.maxDocumentAge.outOfRange");
        KmsTrustProperties relative = properties("1d");
        relative.setAnchorFile("nf07-trust.json");
        assertThatThrownBy(() -> validator(true, new KmsPrivacyTrustSource(relative, clock)).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trust.kms.anchorFile.notAbsolute");
        KmsTrustProperties slowReload = properties("1d");
        slowReload.setReloadInterval("10m");
        assertThatThrownBy(() -> validator(true, new KmsPrivacyTrustSource(slowReload, clock)).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trust.kms.reloadInterval.outOfRange");

        // 解釈できない時間の値は bind を失敗させず、flag が ON の起動検査で止める（review P3-6）
        assertThatThrownBy(() -> validator(true, kms("1 day")).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trust.kms.maxDocumentAge.invalid");
        KmsTrustProperties badReload = properties("1d");
        badReload.setReloadInterval("soon");
        assertThatThrownBy(() -> validator(true, new KmsPrivacyTrustSource(badReload, clock)).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trust.kms.reloadInterval.invalid");
        assertThatCode(() -> validator(false, kms("1 day")).validate()).as("flag が OFF なら検査しない")
                .doesNotThrowAnyException();
    }

    @Test
    void flagがONでも鮮度の上限を過ぎた文書と有効なanchorの無い文書は時間要因として起動を続ける() throws IOException {
        writeValid(NOW.minus(Duration.ofDays(2)));
        assertThatCode(() -> validator(true, kms("1d")).validate()).doesNotThrowAnyException();
        KmsPrivacyTrustSource.StartupReport stale = kms("1d").inspect();
        assertThat(stale.structural()).isEmpty();
        assertThat(stale.fresh()).isFalse();

        Files.write(file, bytes(document(NOW, List.of(policyAnchor("policy-issuer", "k1", key, "ACTIVE",
                NOW.minus(Duration.ofDays(30)), NOW.minusSeconds(1), "default")), List.of(), Map.of())));
        KmsPrivacyTrustSource.StartupReport expired = kms("1d").inspect();
        assertThat(expired.structural()).isEmpty();
        assertThat(expired.fresh()).isTrue();
        assertThat(expired.usablePolicyAnchors()).isZero();
        assertThatCode(() -> validator(true, kms("1d")).validate()).doesNotThrowAnyException();

        writeValid(NOW);
        KmsPrivacyTrustSource.StartupReport ready = kms("1d").inspect();
        assertThat(ready.fresh()).isTrue();
        assertThat(ready.usablePolicyAnchors()).isEqualTo(1);
        assertThatCode(() -> validator(true, kms("1d")).validate()).doesNotThrowAnyException();
    }

    private void writeValid(Instant generatedAt) throws IOException {
        Files.write(file, bytes(document(generatedAt, List.of(policyAnchor("policy-issuer", "k1", key, "ACTIVE",
                NOW.minus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(30)), "default")), List.of(), Map.of())));
    }

    private KmsPrivacyTrustSource kms(String maxDocumentAge) {
        return new KmsPrivacyTrustSource(properties(maxDocumentAge), clock);
    }

    private KmsTrustProperties properties(String maxDocumentAge) {
        KmsTrustProperties properties = new KmsTrustProperties();
        properties.setAnchorFile(file.toString());
        properties.setMaxDocumentAge(maxDocumentAge);
        return properties;
    }

    private static PrivacyTrustStartupValidator validator(boolean retention, PrivacyTrustSource source) {
        PrivacyFeatureProperties features = new PrivacyFeatureProperties();
        features.getRetention().setEnabled(retention);
        return new PrivacyTrustStartupValidator(features, source);
    }
}
