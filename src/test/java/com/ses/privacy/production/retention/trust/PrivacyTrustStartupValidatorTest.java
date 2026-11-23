package com.ses.privacy.production.retention.trust;

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

/**
 * NF07 の trust（D-03 = AWS KMS）の起動時の検査（{@link PrivacyTrustStartupValidator}）。機能の開閉に関係なく常に検査し、設定・文書の
 * 構造的な不足は起動を止めずに {@link PrivacyTrustStructureState} へ置き（副入口 REL・REG だけが閉じる。FE-C04）、時間要因（鮮度の
 * 上限・有効期間）だけなら起動を続ける。鍵は test-only。
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
    void 未設定のtrustは違反にせず構造の不足も無いとして扱う() throws IOException {
        Files.writeString(file, "{", StandardCharsets.UTF_8);
        PrivacyTrustStructureState state = new PrivacyTrustStructureState();
        assertThatCode(() -> new PrivacyTrustStartupValidator(new UnconfiguredPrivacyTrustSource(), state).validate())
                .doesNotThrowAnyException();
        assertThat(state.sound()).isTrue();
        assertThat(state.inspected()).isTrue();
    }

    /** FE-C04: flag に関係なく常に検査し、構造の不足は起動を止めずに状態へ置く（副入口 REL・REG だけが閉じる）。 */
    @Test
    void 文書と設定の構造的な不足は起動を止めず固定のcodeだけを状態に置く() throws IOException {
        Files.writeString(file, "{", StandardCharsets.UTF_8);
        assertStructural(kms("1d"), "trust.kms.document.notJson");

        Files.delete(file);
        assertStructural(kms("1d"), "trust.kms.document.unreadable");

        KeyPair other = ed25519();
        byte[] ph = bytes(document(NOW, List.of(policyAnchor("policy-issuer", "k1", other, "ACTIVE",
                NOW.minusSeconds(60), NOW.plusSeconds(86_400), "default")
                .put("signingAlgorithm", "ED25519_PH_SHA_512")), List.of(), Map.of()));
        Files.write(file, ph);
        PrivacyTrustStructureState signing = assertStructural(kms("1d"),
                "trust.kms.anchor.signingAlgorithm at $.policyAnchors[0].signingAlgorithm");
        assertThat(String.join(",", signing.structuralViolations())).doesNotContain(KmsTrustTestDocuments.spki(other));

        writeValid(NOW);
        assertStructural(kms(null), "trust.kms.maxDocumentAge.unset");
        assertStructural(kms("40d"), "trust.kms.maxDocumentAge.outOfRange");
        KmsTrustProperties relative = properties("1d");
        relative.setAnchorFile("nf07-trust.json");
        assertStructural(new KmsPrivacyTrustSource(relative, clock), "trust.kms.anchorFile.notAbsolute");
        KmsTrustProperties slowReload = properties("1d");
        slowReload.setReloadInterval("10m");
        assertStructural(new KmsPrivacyTrustSource(slowReload, clock), "trust.kms.reloadInterval.outOfRange");

        // 解釈できない時間の値は bind を失敗させず、起動時の検査で構造の不足にする（review P3-6）
        assertStructural(kms("1 day"), "trust.kms.maxDocumentAge.invalid");
        KmsTrustProperties badReload = properties("1d");
        badReload.setReloadInterval("soon");
        assertStructural(new KmsPrivacyTrustSource(badReload, clock), "trust.kms.reloadInterval.invalid");

        // 直した文書と設定なら不足は無い
        PrivacyTrustStructureState sound = new PrivacyTrustStructureState();
        new PrivacyTrustStartupValidator(kms("1d"), sound).validate();
        assertThat(sound.sound()).isTrue();
    }
    @Test
    void 鮮度の上限を過ぎた文書と有効なanchorの無い文書は時間要因として起動を続ける() throws IOException {
        writeValid(NOW.minus(Duration.ofDays(2)));
        assertThatCode(() -> validator(kms("1d")).validate()).doesNotThrowAnyException();
        KmsPrivacyTrustSource.StartupReport stale = kms("1d").inspect();
        assertThat(stale.structural()).isEmpty();
        assertThat(stale.fresh()).isFalse();

        Files.write(file, bytes(document(NOW, List.of(policyAnchor("policy-issuer", "k1", key, "ACTIVE",
                NOW.minus(Duration.ofDays(30)), NOW.minusSeconds(1), "default")), List.of(), Map.of())));
        KmsPrivacyTrustSource.StartupReport expired = kms("1d").inspect();
        assertThat(expired.structural()).isEmpty();
        assertThat(expired.fresh()).isTrue();
        assertThat(expired.usablePolicyAnchors()).isZero();
        assertThatCode(() -> validator(kms("1d")).validate()).doesNotThrowAnyException();

        writeValid(NOW);
        KmsPrivacyTrustSource.StartupReport ready = kms("1d").inspect();
        assertThat(ready.fresh()).isTrue();
        assertThat(ready.usablePolicyAnchors()).isEqualTo(1);
        assertThatCode(() -> validator(kms("1d")).validate()).doesNotThrowAnyException();
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

    private static PrivacyTrustStartupValidator validator(PrivacyTrustSource source) {
        return new PrivacyTrustStartupValidator(source, new PrivacyTrustStructureState());
    }

    /** 起動を止めず、構造の不足を状態に置く。 */
    private static PrivacyTrustStructureState assertStructural(PrivacyTrustSource source, String fragment) {
        PrivacyTrustStructureState state = new PrivacyTrustStructureState();
        assertThatCode(() -> new PrivacyTrustStartupValidator(source, state).validate()).as(fragment)
                .doesNotThrowAnyException();
        assertThat(state.sound()).as(fragment).isFalse();
        assertThat(String.join(",", state.structuralViolations())).as(fragment).contains(fragment);
        return state;
    }
}
