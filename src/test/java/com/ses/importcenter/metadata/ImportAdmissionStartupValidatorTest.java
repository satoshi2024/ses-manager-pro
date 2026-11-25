package com.ses.importcenter.metadata;

import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Q-5・FE-B02（feature-enablement-console design §8.3、requirements §7）: prod の起動時の admission 承認の確認は、NF-06 の ON・OFF と
 * 承認の値の不足（構造的な不足・時間要因）に関係なく起動を止めない（NF-06 を閉じるのは {@code ImportCenterEnablementConditions}）。
 * 承認の値は源（{@link ImportAdmissionApprovalSource}）から読む。
 */
class ImportAdmissionStartupValidatorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void prod_profileでだけ登録される() {
        assertArrayEquals(new String[]{"prod"}, ImportAdmissionStartupValidator.class.getAnnotation(Profile.class).value());
    }

    @Test
    void NF06のONとOFFのどちらでも承認の値の不足で起動を止めない() {
        ImportAdmissionApprovalProperties partial = new ImportAdmissionApprovalProperties();
        partial.setDecisionId("DO-NF06-2026-001");
        ImportAdmissionApprovalProperties tampered = approved();
        tampered.setSourceCodes(List.of("legacy-crm", "secret-extra-source"));
        ImportAdmissionApprovalProperties expired = new ImportAdmissionApprovalProperties();
        expired.setDecisionId("DO-NF06-2025-009");
        expired.setTenantId("tenant-a");
        expired.setSignedOn("2025-11-01");
        expired.setExpiresOn("2026-11-01");
        expired.setSourceCodes(List.of("legacy-crm"));
        expired.setApprovalDigest(ImportAdmissionApproval.computeDigest(expired));
        for (boolean enabled : new boolean[]{true, false}) {
            for (ImportAdmissionApprovalProperties properties : List.of(new ImportAdmissionApprovalProperties(), partial,
                    tampered, expired, approved())) {
                assertDoesNotThrow(() -> validator(properties, enabled).validate(), "nf06=" + enabled);
            }
        }
    }

    @Test
    void 承認の値は源から毎回読む() {
        AtomicInteger reads = new AtomicInteger();
        ImportAdmissionApprovalSource counting = new ImportAdmissionApprovalSource() {
            @Override
            public ImportAdmissionApprovalProperties current() {
                reads.incrementAndGet();
                return approved();
            }

            @Override
            public ImportAdmissionApprovalProperties fromSnapshot(FeatureEnablementSnapshot snapshot) {
                return approved();
            }
        };
        ImportAdmissionStartupValidator validator = new ImportAdmissionStartupValidator(counting, provider(true), CLOCK);
        assertDoesNotThrow(validator::validate);
        assertEquals(1, reads.get());
    }

    private static ImportAdmissionStartupValidator validator(ImportAdmissionApprovalProperties properties,
                                                             boolean nf06Enabled) {
        return new ImportAdmissionStartupValidator(properties, provider(nf06Enabled), CLOCK);
    }

    private static ObjectProvider<FeatureSwitches> provider(boolean nf06Enabled) {
        FeatureSwitchProperties switches = new FeatureSwitchProperties();
        switches.getNf06().setEnabled(nf06Enabled);
        @SuppressWarnings("unchecked")
        ObjectProvider<FeatureSwitches> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new FeatureSwitches(switches));
        return provider;
    }

    private static ImportAdmissionApprovalProperties approved() {
        ImportAdmissionApprovalProperties properties = new ImportAdmissionApprovalProperties();
        properties.setDecisionId("DO-NF06-2026-001");
        properties.setTenantId("tenant-a");
        properties.setSignedOn("2026-11-01");
        properties.setExpiresOn("2026-12-31");
        properties.setSourceCodes(List.of("legacy-crm"));
        properties.setApprovalDigest(ImportAdmissionApproval.computeDigest(properties));
        return properties;
    }
}
