package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.entity.BpAvailabilityIngestion;
import com.ses.entity.Document;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCertification;
import com.ses.entity.ProjectIngestion;
import com.ses.mapper.BpAvailabilityIngestionMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectIngestionMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.ResumeIngestionMapper;
import com.ses.service.MenuCacheService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.impl.FileScopeValidationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取込機能の原本ファイル（氏名・連絡先を含むPII）が、対応するメニュー権限を持つロールにしか
 * ダウンロードできないことの検証。
 *
 * <p>{@link FileScopeValidationService} は「どのテーブルにも該当しなければ許可」というフォール
 * スルーを持つため、ファイルを保存する機能を追加した際に判定を足し忘れると、黙って認証済み
 * 全ユーザーへ公開されてしまう。FR-01(案件メール取込)・FR-08(要員空き状況取込)の原本が
 * まさにこの状態だったため、その回帰を防ぐ。
 */
@ExtendWith(MockitoExtension.class)
class FileScopeValidationServiceTest {

    @Mock private ResumeIngestionMapper resumeIngestionMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private ProposalMapper proposalMapper;
    @Mock private ProjectIngestionMapper projectIngestionMapper;
    @Mock private BpAvailabilityIngestionMapper bpAvailabilityIngestionMapper;
    @Mock private DataScopeService dataScopeService;
    @Mock private MenuCacheService menuCacheService;
    @Mock private ObjectProvider<MenuCacheService> menuCacheServiceProvider;
    @Mock private ObjectProvider<DocumentVersionMapper> documentVersionMapperProvider;
    @Mock private DocumentVersionMapper documentVersionMapper;
    @Mock private ObjectProvider<DocumentLinkMapper> documentLinkMapperProvider;
    @Mock private ObjectProvider<DocumentMapper> documentMapperProvider;
    @Mock private DocumentMapper documentMapper;
    @Mock private ObjectProvider<EngineerAccountLinkService> engineerAccountLinkServiceProvider;
    @Mock private ObjectProvider<com.ses.service.security.OrganizationScopeService> organizationScopeServiceProvider;
    @Mock private ObjectProvider<AuthorizationService> authorizationServiceProvider;
    @Mock private ObjectProvider<EngineerCertificationMapper> engineerCertificationMapperProvider;
    @Mock private EngineerCertificationMapper engineerCertificationMapper;
    @Mock private DocumentLinkMapper documentLinkMapper;
    @Mock private Clock clock;
    @Mock private com.ses.mapper.privacy.PrivacyExportPackageMapper privacyExportPackageMapper;
    @Mock private com.ses.mapper.FeatureSignOffMapper featureSignOffMapper;

    private FileScopeValidationService service;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        lenient().when(documentVersionMapperProvider.getIfAvailable()).thenReturn(documentVersionMapper);
        lenient().when(documentLinkMapperProvider.getIfAvailable()).thenReturn(documentLinkMapper);
        lenient().when(documentVersionMapper.selectList(any())).thenReturn(List.of());
        service = new FileScopeValidationService(
                resumeIngestionMapper,
                engineerMapper,
                proposalMapper,
                projectIngestionMapper,
                bpAvailabilityIngestionMapper,
                documentVersionMapperProvider,
                documentLinkMapperProvider,
                documentMapperProvider,
                engineerAccountLinkServiceProvider,
                organizationScopeServiceProvider,
                dataScopeService,
                menuCacheServiceProvider,
                authorizationServiceProvider,
                engineerCertificationMapperProvider,
                clock);
        // NF07-A2b: DSAR の開示 package の照合（無ければ全て拒否する fail-closed のため、package でない名前を返す mock を与える）
        lenient().when(privacyExportPackageMapper.countByStoredNames(any(), any(), any())).thenReturn(0L);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "privacyExportPackageMapper",
                privacyExportPackageMapper);
        // FE-A02: 機能の開放の署名の文書の照合（無ければ全て拒否する fail-closed。mock は署名でない＝0件を返す）
        lenient().when(featureSignOffMapper.countByStoredNames(any(), any())).thenReturn(0L);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "featureSignOffMapper", featureSignOffMapper);
    }

    // ---------------------------------------------------------------- NF07-A2b: 汎用 file 経路の遮断（§3.5.2 P2-5）

    @Test
    void DSARの開示packageは営業_要員_HR_管理者_privacy_adminの誰が汎用経路で求めても拒否し業務表を見ない() {
        when(privacyExportPackageMapper.countByStoredNames("default", "dsarpkg.json", "dsarpkg.json")).thenReturn(1L);
        // privacy-admin は管理者 role の group 所属で、role の上では管理者と区別されない（管理者の通過分岐より前で拒否する）
        for (String role : List.of("営業", "要員", "HR", "マネージャー", "管理者")) {
            loginAs(role);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.assertDownloadAllowed("dsarpkg.json"), role);
            assertEquals(403, ex.getCode(), role);
            assertEquals("error.forbidden", ex.getMessageKey(), role);
            BusinessException versioned = assertThrows(BusinessException.class,
                    () -> service.assertDownloadAllowed("dsarpkg.json", 1L, "a".repeat(64)), role);
            assertEquals(403, versioned.getCode(), role);
        }
        verify(resumeIngestionMapper, never()).selectByStoredFileNameForTenant(any(), any());
        verify(documentVersionMapper, never()).selectList(any());
        verify(engineerMapper, never()).selectOne(any());
    }

    @Test
    void 機能の開放の署名の文書は全roleで汎用の取得と再scanを拒否し業務表を見ない() {
        when(featureSignOffMapper.countByStoredNames("signoff.pdf", "signoff.pdf")).thenReturn(1L);
        when(featureSignOffMapper.countByStoredNames("SIGNOFF.PDF. ", "signoff.pdf")).thenReturn(1L);
        for (String role : List.of("営業", "要員", "HR", "マネージャー", "管理者")) {
            loginAs(role);
            BusinessException download = assertThrows(BusinessException.class,
                    () -> service.assertDownloadAllowed("signoff.pdf"), role);
            assertEquals(403, download.getCode(), role);
            assertEquals("error.forbidden", download.getMessageKey(), role);
            BusinessException rescan = assertThrows(BusinessException.class,
                    () -> service.assertRescanAllowed("signoff.pdf"), role);
            assertEquals(403, rescan.getCode(), role);
            // 大文字・末尾の . と空白の違いも正規化した名前で照合する
            assertEquals(403, assertThrows(BusinessException.class,
                    () -> service.assertDownloadAllowed("SIGNOFF.PDF. "), role).getCode(), role);
        }
        verify(resumeIngestionMapper, never()).selectByStoredFileNameForTenant(any(), any());
        verify(engineerMapper, never()).selectOne(any());

        // 照会に失敗した・mapper が無い場合も拒否する（fail-closed）
        when(featureSignOffMapper.countByStoredNames(any(), any())).thenThrow(new IllegalStateException("db"));
        assertEquals(403, assertThrows(BusinessException.class, () -> service.assertRescanAllowed("other.pdf")).getCode());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "featureSignOffMapper", null);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.assertRescanAllowed("other.pdf")).getCode());
    }

    @Test
    void DSARの開示packageは汎用の再scanも拒否する() {
        when(privacyExportPackageMapper.countByStoredNames("default", "dsarpkg.json", "dsarpkg.json")).thenReturn(1L);
        loginAs("管理者");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.assertRescanAllowed("dsarpkg.json"));
        assertEquals(403, ex.getCode());
        assertDoesNotThrow(() -> service.assertRescanAllowed("other.pdf"));
    }

    @Test
    void 大文字と末尾の点や空白で表記を変えた保存名も正規化した名前で照合して拒否する() {
        when(privacyExportPackageMapper.countByStoredNames("default", "DSARPKG.JSON. ", "dsarpkg.json")).thenReturn(1L);
        loginAs("管理者");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("DSARPKG.JSON. "));
        assertEquals(403, ex.getCode());
        verify(privacyExportPackageMapper).countByStoredNames("default", "DSARPKG.JSON. ", "dsarpkg.json");
    }

    /**
     * HYG-FINAL P3-D: PRIVACY_EXPORT の metadata は照会者の tenant だけで照合する（索引 {@code (tenant_id, stored_name)} を使う）。
     * tenant が決まらなければ照会せずに拒否する（fail-closed）。他 tenant の metadata だけを持つ名前を開けないことは
     * {@code FileStorageServiceImplTest} の保存層の照合が確かめる。
     */
    @Test
    void DSARの開示packageの照合には照会者のtenantを渡しtenantが無ければ照会せずに拒否する() {
        loginAs("管理者");
        service.assertRescanAllowed("tenant-a.json");
        verify(privacyExportPackageMapper).countByStoredNames("default", "tenant-a.json", "tenant-a.json");

        AccountingTenantContextHolder.setTenantId("tenant-b");
        service.assertRescanAllowed("tenant-b.json");
        verify(privacyExportPackageMapper).countByStoredNames("tenant-b", "tenant-b.json", "tenant-b.json");

        AccountingTenantContextHolder.clear();
        SecurityContextHolder.clearContext();
        BusinessException noTenant = assertThrows(BusinessException.class,
                () -> service.assertRescanAllowed("no-tenant.json"));
        assertEquals(403, noTenant.getCode());
        assertEquals("error.forbidden", noTenant.getMessageKey());
        verify(privacyExportPackageMapper, never()).countByStoredNames(any(), org.mockito.ArgumentMatchers.eq("no-tenant.json"),
                any());
    }

    @Test
    void package表を照合できなければ汎用経路の取得を全て拒否する() {
        loginAs("管理者");
        when(privacyExportPackageMapper.countByStoredNames(any(), any(), any())).thenThrow(new IllegalStateException("db"));
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("anything.pdf")).getCode());

        org.springframework.test.util.ReflectionTestUtils.setField(service, "privacyExportPackageMapper", null);
        assertEquals("error.forbidden", assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("anything.pdf")).getMessageKey());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.assertRescanAllowed("anything.pdf")).getCode());
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    private void loginAs(String role) {
        // SecurityUtils.currentRole() は principal が LoginUser のときだけロールを返す
        com.ses.entity.SysUser user = new com.ses.entity.SysUser();
        user.setId(1L);
        user.setUsername("tester");
        user.setRole(role);
        com.ses.config.LoginUser principal = new com.ses.config.LoginUser(
                user, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    /** 先行する3判定（レジュメ/写真/スキルシート）に該当させないための共通スタブ。 */
    private void noMatchOnEarlierTables() {
        lenient().when(resumeIngestionMapper.selectOne(any())).thenReturn(null);
        lenient().when(resumeIngestionMapper.selectByStoredFileNameForTenant(any(), any())).thenReturn(null);
        lenient().when(resumeIngestionMapper.countByStoredFileName(any())).thenReturn(0);
        lenient().when(engineerMapper.selectOne(any())).thenReturn(null);
        lenient().when(proposalMapper.selectOne(any())).thenReturn(null);
    }

    @Test
    void 案件メール取込の原本はproject_ingestionメニューを持たないロールに403() {
        noMatchOnEarlierTables();
        ProjectIngestion ingestion = new ProjectIngestion();
        ingestion.setTenantId("default");
        when(projectIngestionMapper.selectByStoredFileNameForTenant("default", "abc.eml")).thenReturn(ingestion);
        loginAs("HR");
        when(menuCacheServiceProvider.getIfAvailable()).thenReturn(menuCacheService);
        when(menuCacheService.getMenuKeysByRole("HR")).thenReturn(List.of("engineer"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("abc.eml"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 案件メール取込の原本はメニューを持つロールなら許可() {
        noMatchOnEarlierTables();
        ProjectIngestion ingestion = new ProjectIngestion();
        ingestion.setTenantId("default");
        when(projectIngestionMapper.selectByStoredFileNameForTenant("default", "abc.eml")).thenReturn(ingestion);
        loginAs("営業");
        when(menuCacheServiceProvider.getIfAvailable()).thenReturn(menuCacheService);
        when(menuCacheService.getMenuKeysByRole("営業")).thenReturn(List.of("project-ingestion"));

        assertDoesNotThrow(() -> service.assertDownloadAllowed("abc.eml"));
    }

    @Test
    void 要員空き状況取込の原本はbp_availability_ingestionメニューで判定する() {
        noMatchOnEarlierTables();
        when(projectIngestionMapper.selectByStoredFileNameForTenant("default", "abc.pdf")).thenReturn(null);
        when(bpAvailabilityIngestionMapper.selectOne(any())).thenReturn(new BpAvailabilityIngestion());
        loginAs("HR");
        when(menuCacheServiceProvider.getIfAvailable()).thenReturn(menuCacheService);
        when(menuCacheService.getMenuKeysByRole("HR")).thenReturn(List.of("engineer"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("abc.pdf"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 管理者はメニュー設定によらず取込原本を参照できる() {
        noMatchOnEarlierTables();
        ProjectIngestion ingestion = new ProjectIngestion();
        ingestion.setTenantId("default");
        when(projectIngestionMapper.selectByStoredFileNameForTenant("default", "abc.eml")).thenReturn(ingestion);
        loginAs("管理者");

        assertDoesNotThrow(() -> service.assertDownloadAllowed("abc.eml"));
    }

    @Test
    void 既存engineer写真参照は許可し後から付いたIMPORT_JOB_linkは汎用downloadを拒否() {
        noMatchOnEarlierTables();
        String storageKey = "shared-engineer-import.csv";
        Engineer engineer = new Engineer();
        engineer.setId(77L);
        when(engineerMapper.selectOne(any())).thenReturn(engineer);
        DocumentVersion version = importJobVersion(storageKey, 880L);
        when(documentVersionMapper.selectList(any())).thenReturn(List.of(version));
        DocumentLink link = new DocumentLink();
        link.setTenantId("default");
        link.setDocumentId(880L);
        link.setTargetType("IMPORT_JOB");
        link.setTargetId(990L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of());

        loginAs("HR");
        assertDoesNotThrow(() -> service.assertDownloadAllowed(storageKey, 41L, "ab".repeat(32)));

        when(documentLinkMapper.selectList(any())).thenReturn(List.of(link));
        org.mockito.Mockito.clearInvocations(engineerMapper);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed(storageKey, 41L, "ab".repeat(32)));

        assertEquals(403, ex.getCode());
        verify(engineerMapper, never()).selectOne(any());
    }

    @Test
    void IMPORT_JOB_linkがなければ既存engineer写真のdownload認可を維持() {
        noMatchOnEarlierTables();
        String storageKey = "ordinary-engineer-photo.png";
        Engineer engineer = new Engineer();
        engineer.setId(78L);
        when(engineerMapper.selectOne(any())).thenReturn(engineer);
        when(documentVersionMapper.selectList(any())).thenReturn(List.of(importJobVersion(storageKey, 881L)));
        when(documentLinkMapper.selectList(any())).thenReturn(List.of());
        loginAs("HR");

        assertDoesNotThrow(() -> service.assertDownloadAllowed(storageKey));
    }

    private DocumentVersion importJobVersion(String storageKey, long documentId) {
        DocumentVersion version = new DocumentVersion();
        version.setId(41L);
        version.setTenantId("default");
        version.setDocumentId(documentId);
        version.setStorageKey(storageKey);
        version.setSha256("ab".repeat(32));
        version.setScanStatus("CLEAN");
        return version;
    }

    @Test
    void 未登録のstoredNameはdefaultDeny() {
        noMatchOnEarlierTables();
        loginAs("営業");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("unknown.png"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 管理レポート文書は汎用Document経路からdownloadできない() {
        noMatchOnEarlierTables();
        DocumentVersion version = new DocumentVersion();
        version.setDocumentId(9001L);
        version.setTenantId("default");
        version.setScanStatus("CLEAN");
        Document document = new Document();
        document.setDocumentType("MANAGEMENT_REPORT");
        document.setTenantId("default");
        when(documentVersionMapperProvider.getIfAvailable()).thenReturn(documentVersionMapper);
        when(documentVersionMapper.selectOne(any())).thenReturn(version);
        when(documentMapperProvider.getIfAvailable()).thenReturn(documentMapper);
        when(documentMapper.selectOne(any())).thenReturn(document);
        loginAs("管理者");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("report-storage-key"));
        assertEquals("error.managementReport.deliveryRequired", ex.getMessage());
    }

    private DocumentVersion certificationEvidenceVersion(long versionId, String storageKey, String hash) {
        DocumentVersion version = new DocumentVersion();
        version.setId(versionId);
        version.setDocumentId(9100L);
        version.setTenantId("default");
        version.setStorageKey(storageKey);
        version.setScanStatus("CLEAN");
        version.setSha256(hash);
        Document document = new Document();
        document.setDocumentType("CERTIFICATION_EVIDENCE");
        document.setTenantId("default");
        lenient().when(documentVersionMapperProvider.getIfAvailable()).thenReturn(documentVersionMapper);
        lenient().when(documentVersionMapper.selectOne(any())).thenReturn(version);
        lenient().when(documentMapperProvider.getIfAvailable()).thenReturn(documentMapper);
        lenient().when(documentMapper.selectOne(any())).thenReturn(document);
        lenient().when(documentLinkMapperProvider.getIfAvailable()).thenReturn(documentLinkMapper);
        lenient().when(engineerCertificationMapperProvider.getIfAvailable()).thenReturn(engineerCertificationMapper);
        return version;
    }

    @Test
    void 資格証憑はtyped_CERTIFICATION_RECORD_linkがなければ403() {
        DocumentVersion version = certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        when(documentLinkMapper.selectList(any())).thenReturn(List.of());
        loginAs("HR");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertCertificationEvidenceDownloadAllowed(
                        "cert-evidence.pdf", 200L, 9100L, 100L, "abc123"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 資格証憑はENGINEER_linkだけでは403() {
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink engineerLink = new DocumentLink();
        engineerLink.setTargetType("ENGINEER");
        engineerLink.setTargetId(50L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(engineerLink));
        loginAs("管理者");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertCertificationEvidenceDownloadAllowed(
                        "cert-evidence.pdf", 200L, 9100L, 100L, "abc123"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 資格証憑は管理者でもtyped_linkとDataScopeが必要() {
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink recordLink = new DocumentLink();
        recordLink.setTargetType("CERTIFICATION_RECORD");
        recordLink.setTargetId(200L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(recordLink));
        EngineerCertification record = new EngineerCertification();
        record.setId(200L);
        record.setEngineerId(50L);
        record.setTenantId("default");
        when(engineerCertificationMapper.selectOne(any())).thenReturn(record);
        org.mockito.Mockito.doThrow(BusinessException.of(403, "error.forbidden"))
                .when(dataScopeService).assertAllowedEngineer(50L);
        loginAs("管理者");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertCertificationEvidenceDownloadAllowed(
                        "cert-evidence.pdf", 200L, 9100L, 100L, "abc123"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 資格証憑はmixed_linkでもCERTIFICATION_RECORDで許可() {
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink engineerLink = new DocumentLink();
        engineerLink.setTargetType("ENGINEER");
        engineerLink.setTargetId(99L);
        DocumentLink recordLink = new DocumentLink();
        recordLink.setTargetType("CERTIFICATION_RECORD");
        recordLink.setTargetId(200L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(engineerLink, recordLink));
        EngineerCertification record = new EngineerCertification();
        record.setId(200L);
        record.setEngineerId(50L);
        record.setTenantId("default");
        when(engineerCertificationMapper.selectOne(any())).thenReturn(record);
        loginAs("HR");

        assertDoesNotThrow(() -> service.assertCertificationEvidenceDownloadAllowed(
                "cert-evidence.pdf", 200L, 9100L, 100L, "abc123"));
    }

    @Test
    void 資格証憑はversion不一致を拒否() {
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink recordLink = new DocumentLink();
        recordLink.setTargetType("CERTIFICATION_RECORD");
        recordLink.setTargetId(200L);
        lenient().when(documentLinkMapper.selectList(any())).thenReturn(List.of(recordLink));
        EngineerCertification record = new EngineerCertification();
        record.setId(200L);
        record.setEngineerId(50L);
        record.setTenantId("default");
        lenient().when(engineerCertificationMapper.selectOne(any())).thenReturn(record);
        loginAs("HR");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertCertificationEvidenceDownloadAllowed(
                        "cert-evidence.pdf", 200L, 9100L, 999L, null));
        assertEquals("error.file.versionMismatch", ex.getMessage());
    }

    @Test
    void 資格証憑はhash不一致を拒否() {
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink recordLink = new DocumentLink();
        recordLink.setTargetType("CERTIFICATION_RECORD");
        recordLink.setTargetId(200L);
        lenient().when(documentLinkMapper.selectList(any())).thenReturn(List.of(recordLink));
        EngineerCertification record = new EngineerCertification();
        record.setId(200L);
        record.setEngineerId(50L);
        record.setTenantId("default");
        lenient().when(engineerCertificationMapper.selectOne(any())).thenReturn(record);
        loginAs("HR");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertCertificationEvidenceDownloadAllowed(
                        "cert-evidence.pdf", 200L, 9100L, 100L, "deadbeef"));
        assertEquals("error.file.hashMismatch", ex.getMessage());
    }

    @Test
    void 資格証憑はCLEANかつversion_hash一致なら許可() {
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink recordLink = new DocumentLink();
        recordLink.setTargetType("CERTIFICATION_RECORD");
        recordLink.setTargetId(200L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(recordLink));
        EngineerCertification record = new EngineerCertification();
        record.setId(200L);
        record.setEngineerId(50L);
        record.setTenantId("default");
        when(engineerCertificationMapper.selectOne(any())).thenReturn(record);
        loginAs("HR");

        assertDoesNotThrow(() -> service.assertCertificationEvidenceDownloadAllowed(
                "cert-evidence.pdf", 200L, 9100L, 100L, "abc123"));
    }

    @Test
    void 資格証憑はlegal_hold中のdownloadを拒否する() {
        certificationEvidenceVersion(100L, "cert-evidence-held.pdf", "abc123");
        Document held = new Document();
        held.setDocumentType("CERTIFICATION_EVIDENCE");
        held.setTenantId("default");
        held.setLegalHoldFlag(1);
        when(documentMapper.selectOne(any())).thenReturn(held);
        loginAs("HR");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertCertificationEvidenceDownloadAllowed(
                        "cert-evidence-held.pdf", 200L, 9100L, 100L, "abc123"));
        assertEquals("error.file.legalHoldActive", ex.getMessage());
    }

    @Test
    void 汎用document_downloadは資格証憑を常に拒否する() {
        noMatchOnEarlierTables();
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink recordLink = new DocumentLink();
        recordLink.setTargetType("CERTIFICATION_RECORD");
        recordLink.setTargetId(200L);
        lenient().when(documentLinkMapper.selectList(any())).thenReturn(List.of(recordLink));
        EngineerCertification record = new EngineerCertification();
        record.setId(200L);
        record.setEngineerId(50L);
        record.setTenantId("default");
        lenient().when(engineerCertificationMapper.selectOne(any())).thenReturn(record);
        loginAs("HR");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("cert-evidence.pdf", 100L, "abc123"));
        assertEquals(403, ex.getCode());
        assertEquals("error.forbidden", ex.getMessage());
    }

    @Test
    void 資格証憑専用境界は営業を未スコープ時に拒否する() {
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink recordLink = new DocumentLink();
        recordLink.setTargetType("CERTIFICATION_RECORD");
        recordLink.setTargetId(200L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(recordLink));
        EngineerCertification record = new EngineerCertification();
        record.setId(200L);
        record.setEngineerId(50L);
        record.setTenantId("default");
        when(engineerCertificationMapper.selectOne(any())).thenReturn(record);
        when(dataScopeService.isScoped()).thenReturn(false);
        loginAs("営業");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertCertificationEvidenceDownloadAllowed(
                        "cert-evidence.pdf", 200L, 9100L, 100L, "abc123"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 資格証憑専用境界はrecordと文書のtenantが一致しなければ拒否する() {
        DocumentVersion version = certificationEvidenceVersion(100L, "cert-evidence-tenant.pdf", "abc123");
        version.setTenantId("default");
        Document document = new Document();
        document.setDocumentType("CERTIFICATION_EVIDENCE");
        document.setTenantId("default");
        when(documentMapper.selectOne(any())).thenReturn(document);

        EngineerCertification record = new EngineerCertification();
        record.setId(200L);
        record.setTenantId("tenant-b");
        record.setEngineerId(50L);
        DocumentLink recordLink = new DocumentLink();
        recordLink.setDocumentId(9100L);
        recordLink.setTargetType("CERTIFICATION_RECORD");
        recordLink.setTargetId(200L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(recordLink));
        when(engineerCertificationMapper.selectOne(any())).thenReturn(record);
        loginAs("HR");

        assertThrows(BusinessException.class, () -> service.assertCertificationEvidenceDownloadAllowed(
                "cert-evidence-tenant.pdf", 200L, 9100L, 100L, "abc123"));
    }

    // ---------------------------------------------------------------- NF-02: portal添付downloadの公開範囲（W2 r2 P3-1）

    @Mock private com.ses.mapper.ServiceRequestMapper serviceRequestMapper;
    @Mock private com.ses.service.impl.ServiceRequestFileReferenceProvider serviceRequestFileReferenceProvider;

    /**
     * controller（{@code getPortalDetail}）を経ない呼出しでも、uploadと同じpolicyで公開範囲外の問い合わせを
     * 他社と同じ404にする。文書版（scan中の403等）を引く前に止め、範囲外の添付の有無・状態を示さない。
     */
    @Test
    void portal添付downloadは公開範囲外の問い合わせを文書版を引く前に404にする() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "serviceRequestMapper", serviceRequestMapper);
        com.ses.entity.ServiceRequest internal = serviceRequest("INTERNAL", null,
                java.time.LocalDateTime.of(2026, 3, 31, 23, 59));
        when(serviceRequestMapper.selectOne(any())).thenReturn(internal);

        // policy未注入はportal起票のみ（fail-closed。uploadと同じ既定）
        BusinessException withoutPolicy = assertThrows(BusinessException.class,
                () -> service.assertPortalServiceRequestDownloadAllowed("sr-key", 10L, 20L, 30L, portalLink()));
        assertEquals(404, withoutPolicy.getCode());
        assertEquals("error.notFound", withoutPolicy.getMessageKey());

        // 公開開始時刻より前に社内で登録した問い合わせも範囲外
        org.springframework.test.util.ReflectionTestUtils.setField(service, "portalServiceRequestVisibilityPolicy",
                new com.ses.service.servicedesk.PortalServiceRequestVisibilityPolicy(
                        java.time.OffsetDateTime.parse("2026-04-01T00:00:00+09:00")));
        BusinessException beforeSince = assertThrows(BusinessException.class,
                () -> service.assertPortalServiceRequestDownloadAllowed("sr-key", 10L, 20L, 30L, portalLink()));
        assertEquals(404, beforeSince.getCode());
        assertEquals("error.notFound", beforeSince.getMessageKey());

        verify(documentVersionMapper, never()).selectOne(any());
        verify(documentMapper, never()).selectOne(any());
    }

    /** 上の拒否がpolicyによるものであること（範囲内の問い合わせは同じ入力で最後まで通る）。 */
    @Test
    void portal添付downloadは公開範囲内の問い合わせなら残りの検証を通過する() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "serviceRequestMapper", serviceRequestMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "serviceRequestFileReferenceProvider",
                serviceRequestFileReferenceProvider);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "portalServiceRequestVisibilityPolicy",
                new com.ses.service.servicedesk.PortalServiceRequestVisibilityPolicy(
                        java.time.OffsetDateTime.parse("2026-04-01T00:00:00+09:00")));
        DocumentVersion version = new DocumentVersion();
        version.setDocumentId(30L);
        version.setTenantId("default");
        version.setStorageKey("sr-key");
        version.setScanStatus("CLEAN");
        when(documentVersionMapper.selectOne(any())).thenReturn(version);
        Document document = new Document();
        document.setId(30L);
        document.setTenantId("default");
        document.setDocumentType("SERVICE_REQUEST_ATTACHMENT");
        document.setLegalHoldFlag(0);
        when(documentMapperProvider.getIfAvailable()).thenReturn(documentMapper);
        when(documentMapper.selectOne(any())).thenReturn(document);
        DocumentLink typedLink = new DocumentLink();
        typedLink.setDocumentId(30L);
        typedLink.setTargetType("SERVICE_REQUEST");
        typedLink.setTargetId(10L);
        typedLink.setDeletedFlag(0);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(typedLink));
        when(serviceRequestFileReferenceProvider.referencedFileNames()).thenReturn(java.util.Set.of("sr-key"));

        // 公開開始時刻以降に社内で登録した問い合わせ、およびportal起票の問い合わせ（時刻に関係なく対象）
        for (com.ses.entity.ServiceRequest visible : List.of(
                serviceRequest("INTERNAL", null, java.time.LocalDateTime.of(2026, 4, 1, 0, 0)),
                serviceRequest("PORTAL", 200L, java.time.LocalDateTime.of(2026, 1, 5, 9, 0)))) {
            when(serviceRequestMapper.selectOne(any())).thenReturn(visible);
            assertDoesNotThrow(() -> service.assertPortalServiceRequestDownloadAllowed(
                    "sr-key", 10L, 20L, 30L, portalLink()), visible.getChannel());
        }
    }

    private com.ses.entity.ServiceRequest serviceRequest(String channel, Long portalUserId,
                                                         java.time.LocalDateTime createdAt) {
        com.ses.entity.ServiceRequest request = new com.ses.entity.ServiceRequest();
        request.setId(10L);
        request.setTenantId("default");
        request.setCustomerId(20L);
        request.setChannel(channel);
        request.setPortalUserId(portalUserId);
        request.setCreatedAt(createdAt);
        return request;
    }

    private com.ses.entity.ServiceAttachmentLink portalLink() {
        return com.ses.entity.ServiceAttachmentLink.builder()
                .serviceRequestId(10L).documentId(30L).visibility("PORTAL_VISIBLE").build();
    }
}
