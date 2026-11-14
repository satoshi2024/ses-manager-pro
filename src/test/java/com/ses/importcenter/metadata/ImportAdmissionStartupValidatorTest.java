package com.ses.importcenter.metadata;

import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Q-5: NF-06がOFFなら起動を止めず、ONなら承認設定の構造的不備で起動を止める（時間要因だけなら止めない）。 */
class ImportAdmissionStartupValidatorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void nf06OffNeverBlocksStartupEvenWithPartialApproval() {
        ImportAdmissionApprovalProperties partial = new ImportAdmissionApprovalProperties();
        partial.setDecisionId("DO-NF06-2026-001");

        assertDoesNotThrow(() -> validator(partial, false).validate());
        assertDoesNotThrow(() -> validator(new ImportAdmissionApprovalProperties(), false).validate());
    }

    @Test
    void nf06OnWithMissingOrTamperedApprovalFailsStartupWithoutPrintingValues() {
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> validator(new ImportAdmissionApprovalProperties(), true).validate());
        assertTrue(missing.getMessage().contains("decisionId.missing"));

        ImportAdmissionApprovalProperties tampered = approved();
        tampered.setSourceCodes(List.of("legacy-crm", "secret-extra-source"));
        IllegalStateException mismatch = assertThrows(IllegalStateException.class,
                () -> validator(tampered, true).validate());
        assertTrue(mismatch.getMessage().contains(ImportAdmissionApproval.VIOLATION_DIGEST_MISMATCH));
        assertFalse(mismatch.getMessage().contains("secret-extra-source"), "設定値をmessageへ出さない");
    }

    @Test
    void nf06OnWithCompleteApprovalStartsAndExpiredOnlyApprovalStartsWithAdmissionStopped() {
        assertDoesNotThrow(() -> validator(approved(), true).validate());

        ImportAdmissionApprovalProperties expired = new ImportAdmissionApprovalProperties();
        expired.setDecisionId("DO-NF06-2025-009");
        expired.setTenantId("tenant-a");
        expired.setSignedOn("2025-11-01");
        expired.setExpiresOn("2026-11-01");
        expired.setSourceCodes(List.of("legacy-crm"));
        expired.setApprovalDigest(ImportAdmissionApproval.computeDigest(expired));
        assertDoesNotThrow(() -> validator(expired, true).validate());
    }

    private static ImportAdmissionStartupValidator validator(ImportAdmissionApprovalProperties properties,
                                                             boolean nf06Enabled) {
        FeatureSwitchProperties switches = new FeatureSwitchProperties();
        switches.getNf06().setEnabled(nf06Enabled);
        @SuppressWarnings("unchecked")
        ObjectProvider<FeatureSwitches> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new FeatureSwitches(switches));
        return new ImportAdmissionStartupValidator(properties, provider, CLOCK);
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
