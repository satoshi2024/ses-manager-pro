package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.entity.BpAvailabilityIngestion;
import com.ses.entity.Document;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
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

    private FileScopeValidationService service;

    @BeforeEach
    void setUp() {
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
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
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
        lenient().when(engineerMapper.selectOne(any())).thenReturn(null);
        lenient().when(proposalMapper.selectOne(any())).thenReturn(null);
    }

    @Test
    void 案件メール取込の原本はproject_ingestionメニューを持たないロールに403() {
        noMatchOnEarlierTables();
        when(projectIngestionMapper.selectOne(any())).thenReturn(new ProjectIngestion());
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
        when(projectIngestionMapper.selectOne(any())).thenReturn(new ProjectIngestion());
        loginAs("営業");
        when(menuCacheServiceProvider.getIfAvailable()).thenReturn(menuCacheService);
        when(menuCacheService.getMenuKeysByRole("営業")).thenReturn(List.of("project-ingestion"));

        assertDoesNotThrow(() -> service.assertDownloadAllowed("abc.eml"));
    }

    @Test
    void 要員空き状況取込の原本はbp_availability_ingestionメニューで判定する() {
        noMatchOnEarlierTables();
        when(projectIngestionMapper.selectOne(any())).thenReturn(null);
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
        when(projectIngestionMapper.selectOne(any())).thenReturn(new ProjectIngestion());
        loginAs("管理者");

        assertDoesNotThrow(() -> service.assertDownloadAllowed("abc.eml"));
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
        version.setScanStatus("CLEAN");
        Document document = new Document();
        document.setDocumentType("MANAGEMENT_REPORT");
        when(documentVersionMapperProvider.getIfAvailable()).thenReturn(documentVersionMapper);
        when(documentVersionMapper.selectOne(any())).thenReturn(version);
        when(documentMapperProvider.getIfAvailable()).thenReturn(documentMapper);
        when(documentMapper.selectById(9001L)).thenReturn(document);
        loginAs("管理者");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("report-storage-key"));
        assertEquals("error.managementReport.deliveryRequired", ex.getMessage());
    }

    private DocumentVersion certificationEvidenceVersion(long versionId, String storageKey, String hash) {
        DocumentVersion version = new DocumentVersion();
        version.setId(versionId);
        version.setDocumentId(9100L);
        version.setStorageKey(storageKey);
        version.setScanStatus("CLEAN");
        version.setSha256(hash);
        Document document = new Document();
        document.setDocumentType("CERTIFICATION_EVIDENCE");
        lenient().when(documentVersionMapperProvider.getIfAvailable()).thenReturn(documentVersionMapper);
        lenient().when(documentVersionMapper.selectOne(any())).thenReturn(version);
        lenient().when(documentMapperProvider.getIfAvailable()).thenReturn(documentMapper);
        lenient().when(documentMapper.selectById(9100L)).thenReturn(document);
        lenient().when(documentLinkMapperProvider.getIfAvailable()).thenReturn(documentLinkMapper);
        lenient().when(engineerCertificationMapperProvider.getIfAvailable()).thenReturn(engineerCertificationMapper);
        return version;
    }

    @Test
    void 資格証憑はtyped_CERTIFICATION_RECORD_linkがなければ403() {
        noMatchOnEarlierTables();
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        when(documentLinkMapper.selectList(any())).thenReturn(List.of());
        loginAs("HR");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("cert-evidence.pdf"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 資格証憑はENGINEER_linkだけでは403() {
        noMatchOnEarlierTables();
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink engineerLink = new DocumentLink();
        engineerLink.setTargetType("ENGINEER");
        engineerLink.setTargetId(50L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(engineerLink));
        loginAs("管理者");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("cert-evidence.pdf"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 資格証憑は管理者でもtyped_linkとDataScopeが必要() {
        noMatchOnEarlierTables();
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink recordLink = new DocumentLink();
        recordLink.setTargetType("CERTIFICATION_RECORD");
        recordLink.setTargetId(200L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(recordLink));
        EngineerCertification record = new EngineerCertification();
        record.setId(200L);
        record.setEngineerId(50L);
        when(engineerCertificationMapper.selectById(200L)).thenReturn(record);
        org.mockito.Mockito.doThrow(BusinessException.of(403, "error.forbidden"))
                .when(dataScopeService).assertAllowedEngineer(50L);
        loginAs("管理者");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("cert-evidence.pdf"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 資格証憑はmixed_linkでもCERTIFICATION_RECORDで許可() {
        noMatchOnEarlierTables();
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
        when(engineerCertificationMapper.selectById(200L)).thenReturn(record);
        loginAs("HR");

        assertDoesNotThrow(() -> service.assertDownloadAllowed("cert-evidence.pdf"));
    }

    @Test
    void 資格証憑はversion不一致を拒否() {
        noMatchOnEarlierTables();
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink recordLink = new DocumentLink();
        recordLink.setTargetType("CERTIFICATION_RECORD");
        recordLink.setTargetId(200L);
        lenient().when(documentLinkMapper.selectList(any())).thenReturn(List.of(recordLink));
        EngineerCertification record = new EngineerCertification();
        record.setId(200L);
        record.setEngineerId(50L);
        lenient().when(engineerCertificationMapper.selectById(200L)).thenReturn(record);
        loginAs("HR");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("cert-evidence.pdf", 999L, null));
        assertEquals("error.file.versionMismatch", ex.getMessage());
    }

    @Test
    void 資格証憑はhash不一致を拒否() {
        noMatchOnEarlierTables();
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink recordLink = new DocumentLink();
        recordLink.setTargetType("CERTIFICATION_RECORD");
        recordLink.setTargetId(200L);
        lenient().when(documentLinkMapper.selectList(any())).thenReturn(List.of(recordLink));
        EngineerCertification record = new EngineerCertification();
        record.setId(200L);
        record.setEngineerId(50L);
        lenient().when(engineerCertificationMapper.selectById(200L)).thenReturn(record);
        loginAs("HR");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("cert-evidence.pdf", 100L, "deadbeef"));
        assertEquals("error.file.hashMismatch", ex.getMessage());
    }

    @Test
    void 資格証憑はCLEANかつversion_hash一致なら許可() {
        noMatchOnEarlierTables();
        certificationEvidenceVersion(100L, "cert-evidence.pdf", "abc123");
        DocumentLink recordLink = new DocumentLink();
        recordLink.setTargetType("CERTIFICATION_RECORD");
        recordLink.setTargetId(200L);
        when(documentLinkMapper.selectList(any())).thenReturn(List.of(recordLink));
        EngineerCertification record = new EngineerCertification();
        record.setId(200L);
        record.setEngineerId(50L);
        when(engineerCertificationMapper.selectById(200L)).thenReturn(record);
        loginAs("HR");

        assertDoesNotThrow(() -> service.assertDownloadAllowed("cert-evidence.pdf", 100L, "abc123"));
    }
}
