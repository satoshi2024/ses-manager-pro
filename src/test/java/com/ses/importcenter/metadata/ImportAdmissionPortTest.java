package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Q-5: 本番admission portは既定deny、署名済み決定IDとsource承認一覧が揃った場合だけ許可し、判定を監査へ残す。 */
class ImportAdmissionPortTest {
    /** JST 2026-11-14 12:00。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC);
    private static final ImportAuthorizedContext CONTEXT = new ImportAuthorizedContext("tenant-a", 1, 2);
    private static final ImportUploadSource SOURCE = new ImportUploadSource(3, "legacy-crm", 1);
    private static final String SHA = "a".repeat(64);

    private AuditLogService audit;

    @BeforeEach
    void prepareAudit() {
        audit = mock(AuditLogService.class);
    }

    @Test
    void unconfiguredApprovalDeniesBeforeBodyAndRecordsTheDenial() {
        ImportAdmissionPort admission = new ApprovedSourceImportAdmissionPort(
                new ImportAdmissionApprovalProperties(), audit, CLOCK);

        BusinessException failure = assertThrows(BusinessException.class, () -> admission.admitBeforeBody(
                CONTEXT, SOURCE, ImportEntity.CUSTOMER, "ses-import-v1", ImportEncoding.UTF8));

        assertEquals(403, failure.getCode());
        assertEquals("error.import.sourceNotApproved", failure.getMessageKey());
        ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
        verify(audit).record(any(), eq("ADMISSION"), uri.capture(), eq(403), eq("IMPORT_ADMISSION_DENIED"), eq(false));
        assertTrue(uri.getValue().contains("result=DENIED"));
        assertTrue(uri.getValue().contains("reason=approval.decisionId.missing"));
        verify(audit, never()).recordRequired(any(), anyString(), anyString(), anyInt(), anyString(), anyBoolean());
        assertThrows(BusinessException.class, () -> admission.admitContent(null, SHA));
    }

    @Test
    void completeSignedApprovalAdmitsApprovedSourceAndRecordsRequiredAuditForBothPhases() {
        ImportAdmissionPort admission = new ApprovedSourceImportAdmissionPort(approved(), audit, CLOCK);

        ImportAdmissionToken token = admission.admitBeforeBody(CONTEXT, new ImportUploadSource(3, "LEGACY-CRM", 1),
                ImportEntity.CUSTOMER, "ses-import-v1", ImportEncoding.SHIFT_JIS);
        ImportAdmissionManifest manifest = admission.admitContent(token, SHA);

        assertEquals("tenant-a", token.tenantId());
        assertEquals(3, token.sourceSystemId());
        assertTrue(token.fixtureKey().startsWith("nf06-approval:DO-NF06-2026-001:"));
        assertEquals("A1A_CONTENT", manifest.phase());
        assertEquals(SHA, manifest.sourceSha256());
        assertEquals(ImportEncoding.SHIFT_JIS, manifest.encoding());
        assertNull(manifest.documentId());
        ArgumentCaptor<String> uris = ArgumentCaptor.forClass(String.class);
        verify(audit, org.mockito.Mockito.times(2)).recordRequired(any(), eq("ADMISSION"), uris.capture(), eq(200),
                eq("IMPORT_ADMISSION_ALLOWED"), eq(true));
        List<String> recorded = uris.getAllValues();
        assertTrue(recorded.get(0).contains("phase=BEFORE_BODY") && recorded.get(0).contains("decision=DO-NF06-2026-001")
                && recorded.get(0).contains("source=legacy-crm"));
        assertTrue(recorded.get(1).contains("phase=CONTENT") && recorded.get(1).contains("sha=" + SHA.substring(0, 12)));
        assertFalse(recorded.get(1).contains(SHA), "hash全体は監査へ書かない");
    }

    @Test
    void sourceOutsideTheApprovedListTenantMismatchAndUnsupportedEntityAreDenied() {
        ImportAdmissionPort admission = new ApprovedSourceImportAdmissionPort(approved(), audit, CLOCK);

        assertDenied(() -> admission.admitBeforeBody(CONTEXT, new ImportUploadSource(4, "other-source", 1),
                ImportEntity.CUSTOMER, "ses-import-v1", ImportEncoding.UTF8));
        assertDenied(() -> admission.admitBeforeBody(new ImportAuthorizedContext("tenant-b", 1, 2), SOURCE,
                ImportEntity.CUSTOMER, "ses-import-v1", ImportEncoding.UTF8));
        assertDenied(() -> admission.admitBeforeBody(CONTEXT, SOURCE, ImportEntity.ENGINEER, "ses-import-v1",
                ImportEncoding.UTF8));
        verify(audit, never()).recordRequired(any(), anyString(), anyString(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    void incompleteOrTamperedApprovalIsDenied() {
        ImportAdmissionApprovalProperties partial = new ImportAdmissionApprovalProperties();
        partial.setDecisionId("DO-NF06-2026-001");
        partial.setSourceCodes(List.of("legacy-crm"));
        assertDenied(() -> new ApprovedSourceImportAdmissionPort(partial, audit, CLOCK).admitBeforeBody(
                CONTEXT, SOURCE, ImportEntity.CUSTOMER, "ses-import-v1", ImportEncoding.UTF8));

        ImportAdmissionApprovalProperties widened = approved();
        widened.setSourceCodes(List.of("legacy-crm", "added-later"));
        assertDenied(() -> new ApprovedSourceImportAdmissionPort(widened, audit, CLOCK).admitBeforeBody(
                CONTEXT, SOURCE, ImportEntity.CUSTOMER, "ses-import-v1", ImportEncoding.UTF8));

        ImportAdmissionApprovalProperties placeholder = approved();
        placeholder.setDecisionId("DO-TBD-0001");
        placeholder.setApprovalDigest(ImportAdmissionApproval.computeDigest(placeholder));
        assertNull(placeholder.getApprovalDigest(), "placeholderの決定IDではdigestを算出しない");
        assertDenied(() -> new ApprovedSourceImportAdmissionPort(placeholder, audit, CLOCK).admitBeforeBody(
                CONTEXT, SOURCE, ImportEntity.CUSTOMER, "ses-import-v1", ImportEncoding.UTF8));
    }

    @Test
    void expiredApprovalIsDeniedAndContentPhaseReevaluatesTheDecision() {
        ImportAdmissionApprovalProperties properties = approved();
        ApprovedSourceImportAdmissionPort today = new ApprovedSourceImportAdmissionPort(properties, audit, CLOCK);
        ImportAdmissionToken token = today.admitBeforeBody(CONTEXT, SOURCE, ImportEntity.CUSTOMER,
                "ses-import-v1", ImportEncoding.UTF8);

        Clock afterExpiry = Clock.fixed(Instant.parse("2027-01-01T03:00:00Z"), ZoneOffset.UTC);
        ApprovedSourceImportAdmissionPort later = new ApprovedSourceImportAdmissionPort(properties, audit, afterExpiry);
        assertDenied(() -> later.admitContent(token, SHA));
        assertDenied(() -> later.admitBeforeBody(CONTEXT, SOURCE, ImportEntity.CUSTOMER, "ses-import-v1",
                ImportEncoding.UTF8));

        ImportAdmissionToken forged = new ImportAdmissionToken(token.tenantId(), token.actorUserId(),
                token.legalEntityId(), token.sourceSystemId(), token.sourceVersion(), token.entity(),
                token.schemaVersion(), token.encoding(), "nf06-approval:DO-OTHER-0001:0000000000000000:x");
        assertDenied(() -> today.admitContent(forged, SHA));
    }

    @Test
    void allowIsRefusedWhenTheRequiredAuditCannotBeWritten() {
        doThrow(new IllegalStateException("audit down")).when(audit)
                .recordRequired(any(), anyString(), anyString(), anyInt(), anyString(), anyBoolean());
        ImportAdmissionPort admission = new ApprovedSourceImportAdmissionPort(approved(), audit, CLOCK);

        BusinessException failure = assertThrows(BusinessException.class, () -> admission.admitBeforeBody(
                CONTEXT, SOURCE, ImportEntity.CUSTOMER, "ses-import-v1", ImportEncoding.UTF8));
        assertEquals(503, failure.getCode());
        assertEquals("error.import.auditUnavailable", failure.getMessageKey());

        BusinessException noAudit = assertThrows(BusinessException.class, () -> new ApprovedSourceImportAdmissionPort(
                approved(), null, CLOCK).admitBeforeBody(CONTEXT, SOURCE, ImportEntity.CUSTOMER, "ses-import-v1",
                ImportEncoding.UTF8));
        assertEquals(503, noAudit.getCode());
    }

    @Test
    void evaluationClassifiesTemporalAndStructuralViolations() {
        LocalDate today = LocalDate.of(2026, 11, 14);
        assertFalse(ImportAdmissionApproval.configured(new ImportAdmissionApprovalProperties()));
        ImportAdmissionApproval.Evaluation complete = ImportAdmissionApproval.evaluate(approved(), today);
        assertTrue(complete.complete());
        assertNotNull(complete.snapshot());
        assertTrue(complete.snapshot().approvesSourceCode("Legacy-CRM"));

        ImportAdmissionApproval.Evaluation future = ImportAdmissionApproval.evaluate(approved(), LocalDate.of(2026, 10, 1));
        assertEquals(List.of("signedOn.future"), future.violations());
        assertEquals(List.of(), ImportAdmissionApproval.structuralViolations(future));

        ImportAdmissionApprovalProperties tooLong = approved();
        tooLong.setExpiresOn("2028-01-01");
        tooLong.setApprovalDigest("0".repeat(64));
        assertTrue(ImportAdmissionApproval.structuralViolations(ImportAdmissionApproval.evaluate(tooLong, today))
                .contains("expiresOn.tooLong"));

        ImportAdmissionApprovalProperties badSource = approved();
        badSource.setSourceCodes(List.of("9starts-with-digit"));
        assertTrue(ImportAdmissionApproval.evaluate(badSource, today).violations().contains("sourceCodes.format"));
    }

    /**
     * b-14（W3-05 review P3-7）: 二人による再計算（feature-switches.md 4.8.2 #1、a1a §10）の golden vector。
     * source code は小文字化して ASCII（C locale）の昇順に並べる。{@code -}（0x2D）＜数字＜{@code _}（0x5F）＜英小文字で、
     * locale 依存の {@code sort} では {@code crm,crm_2,crm2,crm-2} になり digest が一致しない。文書の printf 例と同じ値を固定する。
     */
    @Test
    void admission承認digestの正規形はgolden_vectorと一致しsource_codeをASCII順に並べる() {
        ImportAdmissionApprovalProperties properties = new ImportAdmissionApprovalProperties();
        properties.setDecisionId(" DO-NF06-2026-001 ");
        properties.setTenantId("default");
        properties.setSignedOn("2026-12-01");
        properties.setExpiresOn("2027-11-30");
        properties.setSourceCodes(List.of("CRM_2", "crm-2", "Crm", "crm2", "crm"));

        assertEquals("nf06-import-admission-approval-v1\n"
                        + "decision-id=DO-NF06-2026-001\ntenant-id=default\nsigned-on=2026-12-01\n"
                        + "expires-on=2027-11-30\nentity=CUSTOMER\nschema-version=ses-import-v1\n"
                        + "source-codes=crm,crm-2,crm2,crm_2\n",
                ImportAdmissionApproval.canonicalContent(new ImportAdmissionApproval.Content(true,
                        "DO-NF06-2026-001", "default", LocalDate.of(2026, 12, 1), LocalDate.of(2027, 11, 30),
                        java.util.Set.of("crm", "crm-2", "crm2", "crm_2"))));
        assertEquals("c5dc5f03efdb4fd22c23cdf86c7d248f22af96d187880a87f07664a97b173550",
                ImportAdmissionApproval.computeDigest(properties));
        properties.setApprovalDigest("C5DC5F03EFDB4FD22C23CDF86C7D248F22AF96D187880A87F07664A97B173550");
        assertTrue(ImportAdmissionApproval.evaluate(properties, LocalDate.of(2026, 12, 1)).complete(),
                "決定書の大文字の転記も照合できる");
    }

    @Test
    void canonicalContentIsVersionedAndOrderIndependent() {
        ImportAdmissionApprovalProperties first = approved();
        first.setSourceCodes(List.of("b-source", "A-source"));
        ImportAdmissionApprovalProperties second = approved();
        second.setSourceCodes(List.of("a-source", "b-source"));

        assertEquals(ImportAdmissionApproval.computeDigest(first), ImportAdmissionApproval.computeDigest(second));
        assertEquals("nf06-import-admission-approval-v1\n"
                        + "decision-id=DO-NF06-2026-001\ntenant-id=tenant-a\nsigned-on=2026-11-01\n"
                        + "expires-on=2026-12-31\nentity=CUSTOMER\nschema-version=ses-import-v1\n"
                        + "source-codes=a-source,b-source\n",
                ImportAdmissionApproval.canonicalContent(new ImportAdmissionApproval.Content(true,
                        "DO-NF06-2026-001", "tenant-a", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31),
                        java.util.Set.of("a-source", "b-source"))));
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

    private static void assertDenied(org.junit.jupiter.api.function.Executable call) {
        BusinessException failure = assertThrows(BusinessException.class, call);
        assertEquals(403, failure.getCode());
        assertEquals("error.import.sourceNotApproved", failure.getMessageKey());
    }
}
