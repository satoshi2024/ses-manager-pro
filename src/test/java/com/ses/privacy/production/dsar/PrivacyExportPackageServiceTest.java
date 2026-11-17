package com.ses.privacy.production.dsar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.UploadProperties;
import com.ses.entity.FileSecurityMetadata;
import com.ses.entity.privacy.PrivacyAction;
import com.ses.entity.privacy.PrivacyExportPackage;
import com.ses.entity.privacy.PrivacyRequest;
import com.ses.entity.privacy.PrivacyRequestSubjectLink;
import com.ses.mapper.FileSecurityMetadataMapper;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.PrivacyActionMapper;
import com.ses.mapper.privacy.PrivacyExportPackageMapper;
import com.ses.mapper.privacy.PrivacyRequestMapper;
import com.ses.mapper.privacy.PrivacyRequestSubjectLinkMapper;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.PrivacyFeatureProperties;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.DsarDeliveryClass;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.PrivacySubjectType;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import com.ses.privacy.production.provider.ProviderTable;
import com.ses.privacy.production.provider.SubjectLink;
import com.ses.privacy.production.redaction.ThirdPartyRedactor;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.service.FileStorageService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NF07-A2b: DSAR の草案〜交付記録（production-bridge-phased-plan.md §3.5.2 の対象限定テスト「第三者情報 0、規則未設定で自動出力 0、
 * 交付記録で PURGED、{@code purge_after} 超過で削除、承認されていない package は交付記録できない」と Demo (1)(2)）。
 *
 * <p>H2 上に専用 tenant の case を A2a の service で SCOPED まで進め、実 mapper と実 {@link FileStorageService}（quarantine → scan →
 * published、{@code t_file_security_metadata}）で package を作る（class の transaction で rollback し、作った実体は
 * {@link #removeStoredFiles} で消す）。service の各呼出しは NESTED（savepoint）の transaction で包む。privacy-admin A（作成者）と
 * B（承認者）は actor の切替えで表す。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PrivacyExportPackageServiceTest {

    private static final String TENANT = "nf07-a2b-package";
    /** JST 2026-11-16 10:00。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), ZoneId.of("Asia/Tokyo"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 10, 0);
    private static final long ADMIN_A = 1_516_301L;
    private static final long ADMIN_B = 1_516_302L;
    private static final String D06_ID = "D06-NF07-2026-0006";
    private static final String SUBJECT_NAME = "山田 太郎";
    private static final String SUBJECT_EMAIL = "taro.yamada.101@example.invalid";
    private static final String THIRD_PARTY = "株式会社第三者商事";
    private static final String SKILL_NOTE = "Java 10年";

    @Autowired
    private PrivacyRequestMapper requestMapper;
    @Autowired
    private PrivacyRequestSubjectLinkMapper linkMapper;
    @Autowired
    private PrivacyActionMapper actionMapper;
    @Autowired
    private PrivacyExportPackageMapper packageMapper;
    @Autowired
    private PiiDataElementMapper dataElementMapper;
    @Autowired
    private FileSecurityMetadataMapper metadataMapper;
    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private UploadProperties uploadProperties;
    @Autowired
    private com.ses.service.security.impl.FileScopeValidationService fileScopeValidationService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicReference<PrivacyRequestService.Actor> actor = new AtomicReference<>();
    private final AtomicReference<PrivacyHoldGate> holdGate = new AtomicReference<>();
    private final AtomicReference<ThirdPartyRedactor.Rules> rules = new AtomicReference<>();
    private PrivacyRequestServiceTest.CapturingAuditLogService auditLog;
    private PrivacyApprovalProperties approval;
    private PrivacyFeatureProperties features;
    private SubjectProvider provider;
    private ProductionPrivacyProviderRegistry registry;
    private PrivacyRequestService requestService;
    private PrivacyExportPackageService service;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        actor.set(adminA());
        auditLog = new PrivacyRequestServiceTest.CapturingAuditLogService();
        approval = new PrivacyApprovalProperties();
        approval.getD01().setDecisionId("D01-NF07-2026-0001");
        approval.getD01().setSignedOn("2026-11-01");
        approval.getD01().setExpiresOn("2027-10-31");
        approval.getD06().setDecisionId(D06_ID);
        approval.getD06().setSignedOn("2026-11-05");
        approval.getD06().setExpiresOn("2027-11-04");
        features = new PrivacyFeatureProperties();
        features.getDsar().setDueDays(30);
        features.getDsar().setPackageMaxAge(Duration.ofDays(30));
        provider = new SubjectProvider();
        registry = new ProductionPrivacyProviderRegistry(List.of(provider));
        requestService = requestService();
        service = service(CLOCK);
    }

    @AfterEach
    void removeStoredFiles() throws IOException {
        for (PrivacyExportPackage row : packageMapper.selectList(new LambdaQueryWrapper<PrivacyExportPackage>()
                .eq(PrivacyExportPackage::getTenantId, TENANT))) {
            Files.deleteIfExists(published(row.getStoredName()));
            Files.deleteIfExists(base().resolve("quarantine").resolve(row.getStoredName()));
        }
        AccountingTenantContextHolder.clear();
    }

    // ---------------------------------------------------------------- Demo (1): 検索と草案

    @Test
    void 規則未設定なら自動出力は0件で全ての記録と未登録providerを要手動確認に並べた草案を作る() throws Exception {
        catalog("PKG_FIXTURE_MAILBOX", null);
        long id = scopedRequest("T-PREVIEW");

        PrivacyDsarViews.Transition result = service.preview(id, new PrivacyDsarCommands.Preview(4L));

        assertThat(result.status()).isEqualTo("PREVIEWED");
        assertThat(result.rowVersion()).isEqualTo(5L);
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getPreparedBy()).isEqualTo(ADMIN_A);
        assertThat(row.getApprovedPackageId()).isNull();
        PrivacyExportPackage pkg = onlyPackage(id);
        assertThat(pkg.getStatus()).isEqualTo("DRAFT");
        assertThat(pkg.getCreatedBy()).isEqualTo(ADMIN_A);
        assertThat(pkg.getPurgeAfter()).isEqualTo(NOW.plusDays(30));
        // FileStorageService の quarantine → scan → published を通り、PUBLISHED + CLEAN の metadata を持つ
        FileSecurityMetadata metadata = metadataMapper.selectByStoredName(TENANT, pkg.getStoredName());
        assertThat(metadata.getFileKind()).isEqualTo("PRIVACY_EXPORT");
        assertThat(metadata.getStorageState()).isEqualTo("PUBLISHED");
        assertThat(metadata.getScanStatus()).isEqualTo("CLEAN");
        byte[] stored = Files.readAllBytes(published(pkg.getStoredName()));
        assertThat(PrivacyExportPackageContent.sha256(stored)).isEqualTo(pkg.getContentSha256());
        assertThat((long) stored.length).isEqualTo(pkg.getSizeBytes());

        JsonNode json = new ObjectMapper().readTree(stored);
        assertThat(json.get("format").asText()).isEqualTo("nf07-dsar-export-package-v1");
        assertThat(json.get("redaction").asText()).isEqualTo("REDACTION_RULES_UNSET");
        assertThat(json.get("autoReleased")).as("規則未設定で自動出力 0").isEmpty();
        String text = new String(stored, StandardCharsets.UTF_8);
        assertThat(text).doesNotContain(SUBJECT_NAME, SUBJECT_EMAIL, THIRD_PARTY, SKILL_NOTE);

        PrivacyDsarViews.DeliverySection section = service.section(id);
        assertThat(section.autoReleasedCount()).isZero();
        assertThat(section.redactionReason()).isEqualTo("REDACTION_RULES_UNSET");
        assertThat(section.providers()).containsExactly(new PrivacyDsarViews.ProviderCount(SubjectProvider.ID, 2));
        assertThat(section.manualReview()).contains(
                new PrivacyDsarViews.ManualReviewItem(SubjectProvider.ID, SubjectProvider.PROFILE, "REDACTION_RULES_UNSET", 1),
                new PrivacyDsarViews.ManualReviewItem(SubjectProvider.ID, SubjectProvider.SKILL, "REDACTION_RULES_UNSET", 2),
                new PrivacyDsarViews.ManualReviewItem(null, "PKG_FIXTURE_MAILBOX", "PROVIDER_UNREGISTERED", null));
        assertThat(section.currentPackage().contentSha256()).isEqualTo(pkg.getContentSha256());
        assertThat(section.preparedByMe()).isTrue();
        assertThat(section.actions()).as("作成者には承認の操作を出さない").isEmpty();

        List<PrivacyAction> search = actions(id, "SEARCH");
        assertThat(search).anySatisfy(action -> {
            assertThat(action.getStatus()).isEqualTo("BLOCKED");
            assertThat(action.getDataElementId()).isEqualTo("PKG_FIXTURE_MAILBOX");
            assertThat(action.getBlockedReason()).isEqualTo("PROVIDER_UNREGISTERED");
        });
        assertThat(actions(id, "EXPORT_DRAFT")).singleElement().satisfies(action -> {
            assertThat(action.getResultHash()).isEqualTo(pkg.getContentSha256());
            assertThat(action.getResultCount()).isZero();
        });
        assertThat(auditLog.codes).endsWith("NF07_DSAR_PREVIEWED");
    }

    @Test
    void D06の規則があっても第三者混在の行は1件も自動で入れず本人だけの行の許可fieldだけを入れる() throws Exception {
        // 規則は第三者の列（customer_name）まで自動交付を許すが、その行は第三者混在の data element に属する
        rules.set(new ThirdPartyRedactor.Rules(D06_ID, Set.of("t_engineer.name", "t_engineer.customer_name",
                "t_engineer_skill.skill_note")));
        long id = scopedRequest("T-RULES");

        service.preview(id, new PrivacyDsarCommands.Preview(4L));

        PrivacyExportPackage pkg = onlyPackage(id);
        byte[] stored = Files.readAllBytes(published(pkg.getStoredName()));
        String text = new String(stored, StandardCharsets.UTF_8);
        assertThat(text).as("第三者情報 0").doesNotContain(THIRD_PARTY);
        assertThat(text).as("第三者混在の行は本人の列も自動では出さない").doesNotContain(SUBJECT_NAME, SUBJECT_EMAIL);
        JsonNode released = new ObjectMapper().readTree(stored).get("autoReleased");
        assertThat(released).hasSize(1);
        assertThat(released.get(0).get("table").asText()).isEqualTo("t_engineer_skill");
        assertThat(released.get(0).get("fields").get("t_engineer_skill.skill_note").asText()).isEqualTo(SKILL_NOTE);
        assertThat(released.get(0).get("fields").has("t_engineer_skill.tenant_id"))
                .as("規則が許していない列は入れない").isFalse();

        PrivacyDsarViews.DeliverySection section = service.section(id);
        assertThat(section.redactionReason()).isEqualTo("REDACTION_RULES_APPLIED");
        assertThat(section.autoReleasedCount()).isEqualTo(1);
        assertThat(section.manualReview()).contains(
                new PrivacyDsarViews.ManualReviewItem(SubjectProvider.ID, SubjectProvider.PROFILE,
                        "REDACTION_MIXED_MANUAL_ONLY", 1),
                new PrivacyDsarViews.ManualReviewItem(SubjectProvider.ID, SubjectProvider.SKILL,
                        "REDACTION_MIXED_MANUAL_ONLY", 1));
    }

    @Test
    void package最長保存期間が未設定か範囲外なら草案を作らず実体も残さない() {
        long id = scopedRequest("T-NO-MAX-AGE");
        for (Duration invalid : new Duration[]{null, Duration.ofMinutes(30), Duration.ofDays(367)}) {
            features.getDsar().setPackageMaxAge(invalid);
            assertThatThrownBy(() -> service.preview(id, new PrivacyDsarCommands.Preview(4L)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarPackageRuleUnset");
                    });
        }
        assertThat(requestMapper.selectById(id).getStatus()).isEqualTo("SCOPED");
        assertThat(packages(id)).isEmpty();
        assertThat(service.section(id).packageRuleConfigured()).isFalse();
        assertThat(service.section(id).previewReady()).isFalse();
    }

    @Test
    void 開示請求以外と範囲確定の後にproviderの登録が変わったcaseは草案を作らない() {
        long deletion = scopedRequest("T-DELETION", "DELETION");
        assertThatThrownBy(() -> service.preview(deletion, new PrivacyDsarCommands.Preview(4L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarExportAccessOnly"));
        // 開示以外の請求は草案を作らず、手動対応の完了だけを出す（review P2-2）
        PrivacyDsarViews.DeliverySection deletionSection = service.section(deletion);
        assertThat(deletionSection.exportSupported()).isFalse();
        assertThat(deletionSection.actions()).containsExactly("RECORD_MANUAL_RESOLUTION");
        assertThat(deletionSection.manualResolution()).isNull();
        requestService.recordManualResolution(deletion, new PrivacyDsarCommands.ManualResolution(4L, "DELETED", "ANS-77"));
        PrivacyDsarViews.DeliverySection resolved = service.section(deletion);
        assertThat(resolved.status()).isEqualTo("CONFIRMED");
        assertThat(resolved.manualResolution().resolutionCode()).isEqualTo("DELETED");
        assertThat(resolved.manualResolution().evidenceRef()).isEqualTo("ANS-77");
        assertThat(resolved.actions()).isEmpty();
        assertThat(resolved.delivery()).as("交付の記録は開示請求だけ").isNull();

        long id = scopedRequest("T-SCOPE-CHANGED");
        registry = new ProductionPrivacyProviderRegistry(List.of(provider, new ExtraProvider()));
        PrivacyExportPackageService changed = service(CLOCK);
        assertThatThrownBy(() -> changed.preview(id, new PrivacyDsarCommands.Preview(4L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarScopeChanged");
                });
        assertThat(packages(id)).isEmpty();
        assertThat(provider.calls).as("範囲が変わった case では記録を読まない").isEmpty();
    }

    @Test
    void 本人確認の後にD06が変わったcaseは草案を作らず期限日の無いcaseは草案の時点で期限日を補う() {
        features.getDsar().setDueDays(null);
        long id = scopedRequest("T-DUE-LATE");
        assertThat(requestMapper.selectById(id).getDueDate()).isNull();

        approval.getD06().setDecisionId("D06-NF07-2026-0016");
        assertThatThrownBy(() -> service.preview(id, new PrivacyDsarCommands.Preview(4L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarDecisionChanged"));
        assertThat(service.section(id).decisionChanged()).isTrue();

        approval.getD06().setDecisionId(D06_ID);
        features.getDsar().setDueDays(30);
        service.preview(id, new PrivacyDsarCommands.Preview(4L));
        assertThat(requestMapper.selectById(id).getDueDate()).isEqualTo(LocalDate.of(2026, 12, 16));
    }

    // ---------------------------------------------------------------- Demo (2): 承認・専用ダウンロード・交付記録

    @Test
    void 作成者自身の承認は拒否し別のprivacy_adminが承認するとpackageのidとhashをrequestに書く() {
        long id = previewedRequest("T-APPROVE");
        PrivacyExportPackage pkg = onlyPackage(id);

        assertThatThrownBy(() -> service.approve(id, approval(5L, pkg)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(403);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarApproverIsPreparer");
                });

        actor.set(adminB());
        assertThat(service.section(id).actions()).containsExactly("REVIEW_DRAFT", "APPROVE");
        assertThatThrownBy(() -> service.approve(id, new PrivacyDsarCommands.Approval(5L, pkg.getId(), "0".repeat(64))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarPackageMismatch"));
        assertThatThrownBy(() -> service.approve(id, new PrivacyDsarCommands.Approval(5L, pkg.getId(), "ABC")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));

        PrivacyDsarViews.Transition approved = service.approve(id, approval(5L, pkg));

        assertThat(approved.status()).isEqualTo("APPROVED");
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getApprovedBy()).isEqualTo(ADMIN_B);
        assertThat(row.getApprovedAt()).isEqualTo(NOW);
        assertThat(row.getApprovedPackageId()).isEqualTo(pkg.getId());
        assertThat(row.getApprovedContentSha256()).isEqualTo(pkg.getContentSha256());
        PrivacyExportPackage approvedPackage = packageMapper.selectById(pkg.getId());
        assertThat(approvedPackage.getStatus()).isEqualTo("APPROVED");
        assertThat(approvedPackage.getApprovedBy()).isEqualTo(ADMIN_B);
        assertThat(auditLog.codes).endsWith("NF07_DSAR_APPROVED");
        assertConflict(() -> service.approve(id, approval(5L, pkg)));
        assertThat(service.section(id).approvedByMe()).isTrue();
    }

    @Test
    void 承認されていないpackageと承認後に作った別のpackageはダウンロードも交付記録もできない() {
        long id = previewedRequest("T-UNAPPROVED");
        PrivacyExportPackage pkg = onlyPackage(id);

        assertNotApproved(() -> service.download(id, pkg.getId()));
        assertConflict(() -> service.recordDelivery(id, delivery(5L, pkg)));

        actor.set(adminB());
        service.approve(id, approval(5L, pkg));
        PrivacyExportPackage other = new PrivacyExportPackage();
        other.setTenantId(TENANT);
        other.setRequestId(id);
        other.setStoredName("nf07a2b0000000000000000000000000.json");
        other.setContentSha256("f".repeat(64));
        other.setSizeBytes(2L);
        other.setStatus("DRAFT");
        other.setPurgeAfter(NOW.plusDays(30));
        other.setCreatedBy(ADMIN_A);
        other.setCreatedAt(NOW);
        packageMapper.insert(other);

        actor.set(adminA());
        assertNotApproved(() -> service.download(id, other.getId()));
        assertNotApproved(() -> service.recordDelivery(id, delivery(6L, other)));
        assertNotApproved(() -> service.recordDelivery(id, new PrivacyDsarCommands.Delivery(6L, pkg.getId(),
                other.getContentSha256(), "EMAIL", "SEND-1")));
        assertThat(requestMapper.selectById(id).getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void 承認者以外のprivacy_adminが専用ダウンロードで取得し交付を記録するとCONFIRMEDでpackageはPURGEDになり実体も消える() {
        long id = approvedRequest("T-DELIVER");
        PrivacyExportPackage pkg = onlyPackage(id);
        String storedName = pkg.getStoredName();
        PrivacyExportPackageFileReferenceProvider references = new PrivacyExportPackageFileReferenceProvider(packageMapper);
        assertThat(references.referencedFileNames()).contains(storedName);

        actor.set(adminA());
        PrivacyDsarViews.PackageDownload download = service.download(id, pkg.getId());
        assertThat(PrivacyExportPackageContent.sha256(download.content())).isEqualTo(pkg.getContentSha256());
        assertThat(download.filename()).isEqualTo(requestMapper.selectById(id).getRequestNo() + ".json");
        assertThat(service.section(id).actions()).containsExactly("DOWNLOAD", "RECORD_DELIVERY");

        assertThatThrownBy(() -> service.recordDelivery(id, new PrivacyDsarCommands.Delivery(6L, pkg.getId(),
                pkg.getContentSha256(), "FAX", "SEND-1")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.recordDelivery(id, new PrivacyDsarCommands.Delivery(6L, pkg.getId(),
                pkg.getContentSha256(), "EMAIL", "taro@example.invalid")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));

        PrivacyDsarViews.Transition confirmed = service.recordDelivery(id, delivery(6L, pkg));

        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getDeliveredBy()).isEqualTo(ADMIN_A);
        assertThat(row.getDeliveryMethod()).isEqualTo("EMAIL");
        assertThat(row.getDeliveryEvidenceRef()).isEqualTo("SEND-2026-0001");
        PrivacyExportPackage purged = packageMapper.selectById(pkg.getId());
        assertThat(purged.getStatus()).isEqualTo("PURGED");
        assertThat(purged.getPurgedAt()).isEqualTo(NOW);
        assertThat(Files.exists(published(storedName))).as("交付記録と同時に実体を消す").isFalse();
        assertThat(metadataMapper.selectByStoredName(TENANT, storedName)).isNull();
        assertThat(references.referencedFileNames()).as("PURGED の後は参照に数えない").doesNotContain(storedName);
        assertThat(auditLog.codes).contains("NF07_DSAR_PACKAGE_DOWNLOADED").endsWith("NF07_DSAR_DELIVERY_RECORDED");
        assertNotApproved(() -> service.download(id, pkg.getId()));
        PrivacyDsarViews.DeliverySection section = service.section(id);
        assertThat(section.delivery().deliveryMethod()).isEqualTo("EMAIL");
        assertThat(section.actions()).isEmpty();
    }

    /**
     * HYG-FINAL P3-B: 交付の記録は、承認した package を専用ダウンロードで取得した印（{@code DSAR-DOWNLOAD:}）を要する。取得の前は
     * 区画に交付の記録を出さず、API は 409 で拒否する。取得しないまま期限で PURGED になった package も、hash が合っていても拒否する
     * （交付記録の hash を、system から実際に取り出した内容に結び付けるため）。
     */
    @Test
    void 交付の記録は承認したpackageの取得の後だけで取得の前と取得せずPURGEDになった後は409にする() {
        long id = approvedRequest("T-DELIVER-BEFORE-DOWNLOAD");
        PrivacyExportPackage pkg = onlyPackage(id);
        actor.set(adminA());

        assertThat(service.section(id).actions()).as("取得の前は交付を記録させない").containsExactly("DOWNLOAD");
        assertNotDownloaded(() -> service.recordDelivery(id, delivery(6L, pkg)));
        assertThat(requestMapper.selectById(id).getStatus()).isEqualTo("APPROVED");
        assertThat(packageMapper.selectById(pkg.getId()).getStatus()).isEqualTo("APPROVED");
        assertThat(Files.exists(published(pkg.getStoredName()))).isTrue();

        service.download(id, pkg.getId());
        assertThat(service.section(id).actions()).containsExactly("DOWNLOAD", "RECORD_DELIVERY");
        assertThat(service.recordDelivery(id, delivery(6L, pkg)).status()).isEqualTo("CONFIRMED");

        long untouchedId = approvedRequest("T-DELIVER-PURGED-UNTOUCHED");
        PrivacyExportPackage untouched = onlyPackage(untouchedId);
        PrivacyExportPackageService afterExpiry = service(Clock.fixed(Instant.parse("2026-12-17T01:00:00Z"),
                ZoneId.of("Asia/Tokyo")));
        afterExpiry.purgeDue(null);
        assertThat(packageMapper.selectById(untouched.getId()).getStatus()).isEqualTo("PURGED");
        actor.set(adminA());
        assertNotDownloaded(() -> afterExpiry.recordDelivery(untouchedId, delivery(6L, untouched)));
        assertThat(requestMapper.selectById(untouchedId).getStatus()).isEqualTo("APPROVED");
        assertThat(afterExpiry.section(untouchedId).actions()).as("取得していなければ作り直す").containsExactly("PREVIEW");
    }

    @Test
    void 保存実体が承認時の内容と違えばダウンロードを拒否する() throws IOException {
        long id = approvedRequest("T-TAMPER");
        PrivacyExportPackage pkg = onlyPackage(id);
        Files.write(published(pkg.getStoredName()), "{\"tampered\":true}".getBytes(StandardCharsets.UTF_8));

        actor.set(adminA());
        assertThatThrownBy(() -> service.download(id, pkg.getId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarPackageIntegrity");
                });
        assertThat(auditLog.codes).doesNotContain("NF07_DSAR_PACKAGE_DOWNLOADED");
    }

    // ---------------------------------------------------------------- 期限超過・終了した case の削除

    @Test
    void purge_afterを過ぎたpackageは削除で実体ごとPURGEDになり期限切れの草案は作り直せる() {
        long id = previewedRequest("T-EXPIRE");
        PrivacyExportPackage pkg = onlyPackage(id);
        LocalDateTime later = NOW.plusDays(31);

        assertThat(service.purgeDue(NOW)).as("期限内は消さない").isZero();
        PrivacyExportPackageService afterExpiry = service(Clock.fixed(Instant.parse("2026-12-17T01:00:00Z"),
                ZoneId.of("Asia/Tokyo")));
        actor.set(adminB());
        assertThatThrownBy(() -> afterExpiry.approve(id, approval(5L, pkg)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarPackageExpired"));

        assertThat(afterExpiry.purgeDue(later)).isEqualTo(1);
        PrivacyExportPackage purged = packageMapper.selectById(pkg.getId());
        assertThat(purged.getStatus()).isEqualTo("PURGED");
        assertThat(Files.exists(published(pkg.getStoredName()))).isFalse();
        assertThat(auditLog.uris).anySatisfy(uri -> assertThat(uri).contains("PACKAGE_PURGED"));

        assertThat(afterExpiry.section(id).actions()).containsExactly("PREVIEW");
        PrivacyDsarViews.Transition redone = afterExpiry.preview(id, new PrivacyDsarCommands.Preview(5L));
        assertThat(redone.rowVersion()).isEqualTo(6L);
        assertThat(requestMapper.selectById(id).getPreparedBy()).as("作り直した者が作成者").isEqualTo(ADMIN_B);
        assertThat(packages(id)).extracting(PrivacyExportPackage::getStatus).containsExactly("PURGED", "DRAFT");
    }

    /**
     * D14（deploy-runbook §4.4、plan §3.4.1）: 開示 package は交付用の副本で hold の対象ではないため、本人に SUBJECT hold があっても
     * 期限超過の削除と交付記録の削除は gate を照会せずに消す（原本は D1〜D11 が守る）。
     */
    @Test
    void 本人がhold中でも開示packageの削除はgateを見ずに期限で消す() {
        // 本人（ENGINEER:101）に SUBJECT hold がある状態を gate に持たせる（stub の設定は照会に数えない doReturn で行う）
        PrivacyHoldGate gate = mock(PrivacyHoldGate.class);
        org.mockito.Mockito.doReturn(new PrivacyHoldGate.Decision(PrivacyHoldGate.State.VERIFIED, null,
                Set.of("ENGINEER:101"), Set.of(), Map.of())).when(gate).inspect(TENANT);
        holdGate.set(gate);
        long expiring = previewedRequest("T-HELD-EXPIRE");
        PrivacyExportPackage expiringPackage = onlyPackage(expiring);
        long redrafting = previewedRequest("T-HELD-REDRAFT");
        PrivacyExportPackage redraftedPackage = onlyPackage(redrafting);
        long delivering = approvedRequest("T-HELD-DELIVER");
        PrivacyExportPackage deliveringPackage = onlyPackage(delivering);

        // 交付記録の削除（期限内の承認済み package の実体を同じ tx で消す。交付の記録は取得の印を要する。HYG-FINAL P3-B）
        actor.set(adminA());
        service.download(delivering, deliveringPackage.getId());
        service.recordDelivery(delivering, delivery(6L, deliveringPackage));
        assertThat(packageMapper.selectById(deliveringPackage.getId()).getStatus()).isEqualTo("PURGED");
        assertThat(Files.exists(published(deliveringPackage.getStoredName()))).isFalse();

        // 期限切れの草案の作り直し（作り直しの前に旧 package の実体を消す）
        PrivacyExportPackageService afterExpiry = service(Clock.fixed(Instant.parse("2026-12-17T01:00:00Z"),
                ZoneId.of("Asia/Tokyo")));
        afterExpiry.preview(redrafting, new PrivacyDsarCommands.Preview(5L));
        assertThat(packageMapper.selectById(redraftedPackage.getId()).getStatus()).isEqualTo("PURGED");
        assertThat(Files.exists(published(redraftedPackage.getStoredName()))).isFalse();

        // 期限超過の削除（scheduler の経路）
        assertThat(service.purgeDue(NOW.plusDays(31))).isGreaterThanOrEqualTo(1);
        assertThat(packageMapper.selectById(expiringPackage.getId()).getStatus()).isEqualTo("PURGED");
        assertThat(Files.exists(published(expiringPackage.getStoredName()))).isFalse();
        org.mockito.Mockito.verifyNoInteractions(gate);

        // hold は表示の上では本人に掛かっている（削除の経路だけが gate を見ない）
        assertThat(service.section(expiring).hold().state()).isEqualTo("HELD");
    }

    // ---------------------------------------------------------------- review 対応（P2-1・P2-3・P3-2・P3-4・P3-5・P3-8）

    @Test
    void scanが使えず保存できない草案は実体を残さず汎用経路と再scanも種別で拒否する() throws IOException {
        long id = scopedRequest("T-SCAN-FAIL");
        PrivacyExportPackageService unscannable = service(CLOCK, unscannableStorage());

        assertThatThrownBy(() -> unscannable.preview(id, new PrivacyDsarCommands.Preview(4L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(503);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarPackageFileUnavailable");
                });
        assertThat(packages(id)).isEmpty();
        List<Map<String, Object>> metadata = jdbcTemplate.queryForList("SELECT stored_name, scan_status, deleted_flag "
                + "FROM t_file_security_metadata WHERE tenant_id = ? AND file_kind = 'PRIVACY_EXPORT'", TENANT);
        assertThat(metadata).singleElement().satisfies(row -> {
            assertThat(row.get("scan_status")).isEqualTo("UNAVAILABLE");
            assertThat(((Number) row.get("deleted_flag")).intValue()).as("拒否を記録して論理削除").isEqualTo(1);
        });
        String storedName = (String) metadata.get(0).get("stored_name");
        assertThat(Files.exists(base().resolve("quarantine").resolve(storedName))).as("quarantine に残さない").isFalse();
        assertThat(Files.exists(published(storedName))).isFalse();
        // package の行が無くても、PRIVACY_EXPORT の metadata で汎用の取得と再scanを拒否する（review P2-1 (b)）
        assertThat(catchThrowable(() -> fileScopeValidationService.assertDownloadAllowed(storedName)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        assertThat(catchThrowable(() -> fileScopeValidationService.assertRescanAllowed(storedName)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        assertThat(requestMapper.selectById(id).getStatus()).isEqualTo("SCOPED");
    }

    @Test
    void package行を持たないPRIVACY_EXPORTの実体は猶予の後に削除で消し生きているpackageの実体は残す() throws IOException {
        long id = previewedRequest("T-ORPHAN");
        PrivacyExportPackage live = onlyPackage(id);
        String orphan = "a2borphan000000000000000000000001.json";
        Files.createDirectories(base().resolve("published"));
        Files.writeString(published(orphan), "{\"orphan\":true}");
        jdbcTemplate.update("INSERT INTO t_file_security_metadata (tenant_id, stored_name, file_kind, storage_state, "
                + "scan_status, created_at, deleted_flag) VALUES (?, ?, 'PRIVACY_EXPORT', 'PUBLISHED', 'CLEAN', ?, 0)",
                TENANT, orphan, LocalDateTime.of(2000, 1, 1, 0, 0));
        // 生きている package の実体は、metadata が古くても package の行があるため孤児ではない
        jdbcTemplate.update("UPDATE t_file_security_metadata SET created_at = ? WHERE tenant_id = ? AND stored_name = ?",
                LocalDateTime.of(2000, 1, 1, 0, 0), TENANT, live.getStoredName());

        service.purgeDue(NOW);

        assertThat(Files.exists(published(orphan))).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_flag FROM t_file_security_metadata WHERE tenant_id = ? "
                + "AND stored_name = ?", Integer.class, TENANT, orphan)).isEqualTo(1);
        assertThat(Files.exists(published(live.getStoredName()))).isTrue();
        assertThat(packageMapper.selectById(live.getId()).getStatus()).isEqualTo("DRAFT");
        // 猶予（1時間）より新しい metadata は消さない（作成日時は created_at を書く時計で、service の時計の10分前。HYG-FINAL P3-A）
        String fresh = "a2borphan000000000000000000000002.json";
        Files.writeString(published(fresh), "{\"orphan\":true}");
        LocalDateTime written = com.ses.config.MyBatisPlusConfig.AutoFillMetaObjectHandler.rowTimestamp(CLOCK);
        jdbcTemplate.update("INSERT INTO t_file_security_metadata (tenant_id, stored_name, file_kind, storage_state, "
                + "scan_status, created_at, deleted_flag) VALUES (?, ?, 'PRIVACY_EXPORT', 'PUBLISHED', 'CLEAN', ?, 0)",
                TENANT, fresh, written.minusMinutes(10));
        assertThat(service.orphanCreatedBefore()).isEqualTo(written.minusHours(1));
        service.purgeDue(NOW);
        assertThat(Files.exists(published(fresh))).isTrue();
        Files.deleteIfExists(published(fresh));
    }

    /**
     * HYG-FINAL P3-A: 孤児の猶予は created_at を書いた時計（{@code AutoFillMetaObjectHandler}、JVM の既定時刻帯）で数え、DB の時計や
     * service の Clock の時刻帯（Asia/Tokyo）では数えない。JVM の時刻帯が UTC でも東京でも、保存した直後の回は消さず、猶予を過ぎた回に
     * 消す。修正前（DB の {@code LOCALTIMESTAMP} との比較）は JVM が UTC のとき猶予が負になり、保存した直後の回で消していた。
     */
    @Test
    void 孤児の猶予はcreated_atを書いた時計で数えJVMの時刻帯がUTCでも東京でも保存直後の実体を消さない() throws IOException {
        Files.createDirectories(base().resolve("published"));
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            int sequence = 0;
            for (String zone : List.of("UTC", "Asia/Tokyo")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
                String name = "a2bjvmzone00000000000000000000" + (++sequence) + "0.json";
                Files.writeString(published(name), "{\"orphan\":true}");
                // created_at は MyBatis-Plus の MetaObjectHandler が書く（SQL の既定値にも業務時刻帯の Clock にも頼らない）
                FileSecurityMetadata row = new FileSecurityMetadata();
                row.setTenantId(TENANT);
                row.setStoredName(name);
                row.setFileKind("PRIVACY_EXPORT");
                row.setStorageState("PUBLISHED");
                row.setScanStatus("CLEAN");
                metadataMapper.insert(row);
                Instant afterWrite = Instant.now();
                ZoneId businessZone = ZoneId.of("Asia/Tokyo");

                service(Clock.fixed(afterWrite, businessZone)).purgeDue(NOW);
                assertThat(Files.exists(published(name))).as("JVM=%s: 猶予の内は消さない", zone).isTrue();
                assertThat(metadataMapper.selectByStoredName(TENANT, name)).as("JVM=%s", zone).isNotNull();

                service(Clock.fixed(afterWrite.plus(Duration.ofMinutes(61)), businessZone)).purgeDue(NOW);
                assertThat(Files.exists(published(name))).as("JVM=%s: 猶予を過ぎたら消す", zone).isFalse();
                assertThat(metadataMapper.selectByStoredName(TENANT, name)).as("JVM=%s", zone).isNull();
            }
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    void 汎用のloadは開示packageを開かず専用の経路だけが読める() {
        long id = previewedRequest("T-GENERIC-LOAD");
        PrivacyExportPackage pkg = onlyPackage(id);

        assertThat(catchThrowable(() -> fileStorageService.load(pkg.getStoredName())))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(403);
                    assertThat(e.getMessageKey()).isEqualTo("error.forbidden");
                });
        assertThat(fileStorageService.loadServerGenerated(pkg.getStoredName(),
                com.ses.common.enums.FileKind.PRIVACY_EXPORT)).isNotNull();
    }

    @Test
    void 承認者は承認前に草案の内容を確認でき作成者とhash違いと承認後は確認できない() throws Exception {
        rules.set(new ThirdPartyRedactor.Rules(D06_ID, Set.of("t_engineer_skill.skill_note")));
        long id = previewedRequest("T-REVIEW");
        PrivacyExportPackage pkg = onlyPackage(id);

        assertThatThrownBy(() -> service.reviewDraft(id, pkg.getId(), pkg.getContentSha256()))
                .as("作成者 A は承認前の内容を見られない").isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(403);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarDraftReviewByPreparer");
                });

        actor.set(adminB());
        assertThat(service.section(id).actions()).containsExactly("REVIEW_DRAFT", "APPROVE");
        assertThatThrownBy(() -> service.reviewDraft(id, pkg.getId(), "0".repeat(64)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarPackageMismatch"));
        PrivacyDsarViews.PackageDownload draft = service.reviewDraft(id, pkg.getId(), pkg.getContentSha256());

        assertThat(PrivacyExportPackageContent.sha256(draft.content())).isEqualTo(pkg.getContentSha256());
        assertThat(new ObjectMapper().readTree(draft.content()).get("autoReleased").get(0).get("fields")
                .get("t_engineer_skill.skill_note").asText()).as("承認者は自動で入る値を見て承認できる").isEqualTo(SKILL_NOTE);
        assertThat(auditLog.codes).endsWith("NF07_DSAR_DRAFT_REVIEWED");
        assertThat(requestMapper.selectById(id).getStatus()).as("確認は状態を変えない").isEqualTo("PREVIEWED");

        service.approve(id, approval(5L, pkg));
        assertConflict(() -> service.reviewDraft(id, pkg.getId(), pkg.getContentSha256()));
    }

    @Test
    void 交付記録で実体を消せなければ交付記録ごと巻き戻しpackageと実体を残す() {
        long id = approvedRequest("T-DELETE-FAIL");
        PrivacyExportPackage pkg = onlyPackage(id);
        actor.set(adminA());
        service.download(id, pkg.getId());
        PrivacyExportPackageService failing = service(CLOCK, new FailingDeleteStorage(fileStorageService));

        assertThatThrownBy(() -> failing.recordDelivery(id, delivery(6L, pkg)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(503);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarPackageFileUnavailable");
                });

        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("APPROVED");
        assertThat(row.getRowVersion()).isEqualTo(6L);
        assertThat(row.getDeliveredAt()).isNull();
        assertThat(packageMapper.selectById(pkg.getId()).getStatus()).isEqualTo("APPROVED");
        assertThat(Files.exists(published(pkg.getStoredName()))).isTrue();
        // 再試行は自己回復する（実体の削除は冪等）
        assertThat(service.recordDelivery(id, delivery(6L, pkg)).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void 取得済みの承認packageは期限切れでも作り直さず交付を記録させ取得していなければ外れた承認を行に残して作り直す() {
        long downloadedId = approvedRequest("T-DOWNLOADED-EXPIRE");
        PrivacyExportPackage downloadedPackage = onlyPackage(downloadedId);
        actor.set(adminA());
        service.download(downloadedId, downloadedPackage.getId());
        service.download(downloadedId, downloadedPackage.getId());
        long untouchedId = approvedRequest("T-UNTOUCHED-EXPIRE");
        PrivacyExportPackage untouchedPackage = onlyPackage(untouchedId);
        PrivacyExportPackageService afterExpiry = service(Clock.fixed(Instant.parse("2026-12-17T01:00:00Z"),
                ZoneId.of("Asia/Tokyo")));

        actor.set(adminA());
        PrivacyDsarViews.DeliverySection downloaded = afterExpiry.section(downloadedId);
        assertThat(downloaded.approvedPackageDownloaded()).isTrue();
        assertThat(downloaded.actions()).containsExactly("RECORD_DELIVERY");
        assertThatThrownBy(() -> afterExpiry.preview(downloadedId, new PrivacyDsarCommands.Preview(6L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarRedraftAfterDownload"));
        assertThat(actions(downloadedId, "MANUAL_ACTION")).filteredOn(action -> action.getIdempotencyKey()
                .startsWith("DSAR-DOWNLOAD:")).as("取得の印は package ごとに1行").hasSize(1);

        assertThat(afterExpiry.section(untouchedId).actions()).containsExactly("PREVIEW");
        afterExpiry.preview(untouchedId, new PrivacyDsarCommands.Preview(6L));
        PrivacyRequest redone = requestMapper.selectById(untouchedId);
        assertThat(redone.getStatus()).isEqualTo("PREVIEWED");
        assertThat(redone.getApprovedPackageId()).isNull();
        assertThat(actions(untouchedId, "MANUAL_ACTION")).filteredOn(action -> action.getIdempotencyKey()
                .startsWith("DSAR-SUPERSEDED:")).singleElement().satisfies(action -> {
                    assertThat(action.getBlockedReason()).isEqualTo("APPROVAL_EXPIRED");
                    assertThat(action.getEvidenceRef()).isEqualTo("PKG:" + untouchedPackage.getId());
                    assertThat(action.getResultHash()).isEqualTo(untouchedPackage.getContentSha256());
                });
    }

    @Test
    void 列を持たない記録も要手動確認に数え一覧から落とさない() {
        provider.emptyRecord = true;
        long id = previewedRequest("T-NO-FIELDS");

        assertThat(service.section(id).manualReview()).contains(new PrivacyDsarViews.ManualReviewItem(SubjectProvider.ID,
                SubjectProvider.SKILL, "RECORD_NO_FIELDS", 1));
    }

    @Test
    void 終了した本人請求に残ったpackageは期限前でも削除する() {
        long id = previewedRequest("T-CLOSED");
        PrivacyExportPackage pkg = onlyPackage(id);

        requestService.block(id, new PrivacyDsarCommands.Closure(5L, "REDACTION_NOT_POSSIBLE", null));
        assertThat(service.purgeDue(NOW)).isEqualTo(1);

        assertThat(packageMapper.selectById(pkg.getId()).getStatus()).isEqualTo("PURGED");
        assertThat(Files.exists(published(pkg.getStoredName()))).isFalse();
    }

    // ---------------------------------------------------------------- 監査・hold の表示

    @Test
    void 監査の本文には受付番号と状態遷移だけを入れ保存名とhashとsubject参照を入れない() {
        long id = approvedRequest("T-AUDIT");
        PrivacyExportPackage pkg = onlyPackage(id);
        actor.set(adminA());
        service.download(id, pkg.getId());
        service.recordDelivery(id, delivery(6L, pkg));

        assertThat(auditLog.uris).isNotEmpty().allSatisfy(uri -> assertThat(uri)
                .doesNotContain(pkg.getStoredName(), pkg.getContentSha256(), "ENGINEER", "SEND-2026-0001",
                        SUBJECT_NAME));
        assertThat(auditLog.uris).anySatisfy(uri -> assertThat(uri).contains("PREVIEWED").contains("from=SCOPED"));
    }

    @Test
    void 本人のholdの状態は表示のためだけに読み判定できなければUNKNOWNにする() {
        long id = scopedRequest("T-HOLD");
        assertThat(service.section(id).hold().state()).as("gate が無ければ確認できない").isEqualTo("UNKNOWN");

        PrivacyHoldGate gate = mock(PrivacyHoldGate.class);
        holdGate.set(gate);
        when(gate.inspect(TENANT)).thenReturn(new PrivacyHoldGate.Decision(PrivacyHoldGate.State.VERIFIED, null,
                Set.of("ENGINEER:101"), Set.of(), Map.of()));
        assertThat(service.section(id).hold().state()).isEqualTo("HELD");
        when(gate.inspect(TENANT)).thenReturn(new PrivacyHoldGate.Decision(PrivacyHoldGate.State.VERIFIED, null,
                Set.of("ENGINEER:102"), Set.of(), Map.of()));
        assertThat(service.section(id).hold().state()).isEqualTo("NOT_HELD");
        when(gate.inspect(TENANT)).thenThrow(new IllegalStateException("journal unavailable"));
        assertThat(service.section(id).hold().state()).isEqualTo("UNKNOWN");

        long unscoped = requestService.intake(new PrivacyDsarCommands.Intake("ACCESS", "EMAIL", null, "T-HOLD-2", null))
                .requestId();
        assertThat(service.section(unscoped).hold().state()).isEqualTo("NOT_SCOPED");
    }

    /**
     * HYG-FINAL P3-F: 開示以外の請求で本人が HELD のとき、削除・利用停止の結果は記録を止めずに区画（API）で警告する。記録の前は
     * 警告になる結果コードを返し、記録の後は記録した結果に印を付ける。HELD でなければ警告しない。
     */
    @Test
    void 本人がHELDのとき削除と利用停止の手動対応の結果は記録を止めずに警告を返す() {
        PrivacyHoldGate gate = mock(PrivacyHoldGate.class);
        holdGate.set(gate);
        when(gate.inspect(TENANT)).thenReturn(new PrivacyHoldGate.Decision(PrivacyHoldGate.State.VERIFIED, null,
                Set.of("ENGINEER:101"), Set.of(), Map.of()));
        long deletion = scopedRequest("T-HOLD-DELETION", "DELETION");
        long restriction = scopedRequest("T-HOLD-RESTRICTION", "RESTRICTION");
        long correction = scopedRequest("T-HOLD-CORRECTION", "CORRECTION");

        assertThat(service.section(deletion).holdConflictResolutions()).containsExactly("DELETED");
        assertThat(service.section(restriction).holdConflictResolutions()).containsExactly("RESTRICTED");
        assertThat(service.section(correction).holdConflictResolutions()).isEmpty();

        requestService.recordManualResolution(deletion,
                new PrivacyDsarCommands.ManualResolution(4L, "DELETED", "DEL-2026-0001"));
        requestService.recordManualResolution(correction,
                new PrivacyDsarCommands.ManualResolution(4L, "CORRECTED", "COR-2026-0001"));
        PrivacyDsarViews.DeliverySection recorded = service.section(deletion);
        assertThat(recorded.status()).as("警告は記録を止めない").isEqualTo("CONFIRMED");
        assertThat(recorded.manualResolution().resolutionCode()).isEqualTo("DELETED");
        assertThat(recorded.manualResolution().holdConflict()).isTrue();
        assertThat(service.section(correction).manualResolution().holdConflict()).isFalse();

        when(gate.inspect(TENANT)).thenReturn(new PrivacyHoldGate.Decision(PrivacyHoldGate.State.VERIFIED, null,
                Set.of("ENGINEER:102"), Set.of(), Map.of()));
        assertThat(service.section(deletion).manualResolution().holdConflict()).as("HELD でなければ警告しない").isFalse();
        assertThat(service.section(restriction).holdConflictResolutions()).isEmpty();
    }

    /**
     * HYG-FINAL P3-F の残り（RB-NF07-HOLD）: {@code POST /{id}/resolution} の応答そのものが、記録した直後の hold の状態と警告の印を
     * 返す（区画の {@code manualResolution.holdConflict} と同じ判定）。記録は止めない。hold の状態を読めなくても記録済みの応答を
     * 失敗にせず、{@code UNKNOWN}・印なしにする（再送させて 409 にしない）。
     */
    @Test
    void 手動対応の記録の応答は本人がHELDのとき削除と利用停止に警告の印を付け状態を読めなくても失敗にしない() {
        PrivacyHoldGate gate = mock(PrivacyHoldGate.class);
        holdGate.set(gate);
        when(gate.inspect(TENANT)).thenReturn(new PrivacyHoldGate.Decision(PrivacyHoldGate.State.VERIFIED, null,
                Set.of("ENGINEER:101"), Set.of(), Map.of()));
        long deletion = scopedRequest("T-RESP-DELETION", "DELETION");
        long restriction = scopedRequest("T-RESP-RESTRICTION", "RESTRICTION");
        long refused = scopedRequest("T-RESP-REFUSED", "DELETION");

        PrivacyDsarViews.Transition deleted = requestService.recordManualResolution(deletion,
                new PrivacyDsarCommands.ManualResolution(4L, "DELETED", "DEL-2026-0002"));
        PrivacyDsarViews.ResolutionRecorded response = service.resolutionRecorded(deleted);
        assertThat(response.status()).as("警告は記録を止めない").isEqualTo("CONFIRMED");
        assertThat(response.requestId()).isEqualTo(deletion);
        assertThat(response.rowVersion()).isEqualTo(deleted.rowVersion());
        assertThat(response.holdState()).isEqualTo("HELD");
        assertThat(response.holdConflict()).isTrue();

        PrivacyDsarViews.ResolutionRecorded notConflicting = service.resolutionRecorded(requestService
                .recordManualResolution(refused, new PrivacyDsarCommands.ManualResolution(4L,
                        "DELETION_REFUSED_RETENTION", "REF-2026-0001")));
        assertThat(notConflicting.holdState()).isEqualTo("HELD");
        assertThat(notConflicting.holdConflict()).as("削除しなかった結果は警告しない").isFalse();

        // hold の状態を読めない（gate の例外）: 記録は commit 済みなので応答は成功のまま UNKNOWN・印なし
        when(gate.inspect(TENANT)).thenThrow(new IllegalStateException("journal unreachable"));
        PrivacyDsarViews.ResolutionRecorded unknown = service.resolutionRecorded(requestService
                .recordManualResolution(restriction, new PrivacyDsarCommands.ManualResolution(4L, "RESTRICTED",
                        "RES-2026-0001")));
        assertThat(unknown.status()).isEqualTo("CONFIRMED");
        assertThat(unknown.holdState()).isEqualTo("UNKNOWN");
        assertThat(unknown.holdConflict()).isFalse();
        assertThat(requestMapper.selectById(restriction).getStatus()).isEqualTo("CONFIRMED");
    }

    // ---------------------------------------------------------------- helpers

    private PrivacyRequestService requestService() {
        return new PrivacyRequestService(mock(PrivacyAccessGuard.class), oidc(),
                new PrivacyDsarDecisions(approval, features, CLOCK), requestMapper, linkMapper, actionMapper,
                registry, new PrivacyDsarAudit(auditLog), nested(), CLOCK, actor::get);
    }

    private PrivacyExportPackageService service(Clock clock) {
        return service(clock, fileStorageService);
    }

    private PrivacyExportPackageService service(Clock clock, FileStorageService storage) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivacyApprovalProperties> approvalProvider = mock(ObjectProvider.class);
        when(approvalProvider.getIfAvailable()).thenAnswer(invocation -> approval);
        @SuppressWarnings("unchecked")
        ObjectProvider<ThirdPartyRedactor.Rules> rulesProvider = mock(ObjectProvider.class);
        when(rulesProvider.getIfUnique()).thenAnswer(invocation -> rules.get());
        return new PrivacyExportPackageService(mock(PrivacyAccessGuard.class), oidc(),
                new PrivacyDsarDecisions(approval, features, clock), requestMapper, linkMapper, actionMapper,
                packageMapper, new PrivacySearchService(registry, dataElementMapper), registry,
                new ThirdPartyRedactor(approvalProvider, rulesProvider), storage, holdGate::get,
                new PrivacyDsarAudit(auditLog), nested(), clock, actor::get);
    }

    /** scanner が使えない実 {@link com.ses.service.impl.FileStorageServiceImpl}（同じ upload 先・実 metadata mapper）。 */
    private FileStorageService unscannableStorage() {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.ses.service.security.FileScanner> scanner = mock(ObjectProvider.class);
        when(scanner.getIfAvailable(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.ses.service.security.impl.UnavailableFileScanner());
        @SuppressWarnings("unchecked")
        ObjectProvider<FileSecurityMetadataMapper> metadata = mock(ObjectProvider.class);
        when(metadata.getIfAvailable()).thenReturn(metadataMapper);
        return new com.ses.service.impl.FileStorageServiceImpl(uploadProperties, scanner, metadata);
    }

    /** 実体の削除だけが失敗する storage（交付記録の巻き戻しの確認用。review P3-2）。 */
    private static final class FailingDeleteStorage implements FileStorageService {

        private final FileStorageService delegate;

        FailingDeleteStorage(FileStorageService delegate) {
            this.delegate = delegate;
        }

        @Override
        public com.ses.dto.file.StoredFile store(org.springframework.web.multipart.MultipartFile file,
                                                 com.ses.common.enums.FileKind kind) {
            return delegate.store(file, kind);
        }

        @Override
        public com.ses.dto.file.StoredFile store(byte[] data, String originalName, com.ses.common.enums.FileKind kind) {
            return delegate.store(data, originalName, kind);
        }

        @Override
        public org.springframework.core.io.Resource load(String storedName) {
            return delegate.load(storedName);
        }

        @Override
        public org.springframework.core.io.Resource loadServerGenerated(String storedName,
                                                                        com.ses.common.enums.FileKind kind) {
            return delegate.loadServerGenerated(storedName, kind);
        }

        @Override
        public void delete(String storedName) {
            throw BusinessException.of("error.file.deleteFailed");
        }

        @Override
        public boolean rescan(String storedName) {
            return delegate.rescan(storedName);
        }
    }

    private TransactionTemplate nested() {
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return nested;
    }

    private static OidcSecurityProperties oidc() {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId(TENANT);
        return oidc;
    }

    private static PrivacyRequestService.Actor adminA() {
        return new PrivacyRequestService.Actor(TENANT, ADMIN_A, "privacy-admin-a");
    }

    private static PrivacyRequestService.Actor adminB() {
        return new PrivacyRequestService.Actor(TENANT, ADMIN_B, "privacy-admin-b");
    }

    /** A2a の service で SCOPED（版4）まで進めた case。 */
    private long scopedRequest(String ticketRef) {
        return scopedRequest(ticketRef, "ACCESS");
    }

    private long scopedRequest(String ticketRef, String requestType) {
        long id = requestService.intake(new PrivacyDsarCommands.Intake(requestType, "EMAIL", null, ticketRef, null))
                .requestId();
        requestService.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "EV-" + ticketRef));
        requestService.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        long linkId = linkMapper.selectList(new LambdaQueryWrapper<PrivacyRequestSubjectLink>()
                .eq(PrivacyRequestSubjectLink::getTenantId, TENANT)
                .eq(PrivacyRequestSubjectLink::getRequestId, id)).get(0).getId();
        requestService.resolveCandidate(id, linkId, new PrivacyDsarCommands.CandidateResolution(2L, "SELECTED", null));
        requestService.scope(id, new PrivacyDsarCommands.ScopeConfirmation(3L));
        provider.calls.clear();
        return id;
    }

    /** A が草案（版5）まで作った case。 */
    private long previewedRequest(String ticketRef) {
        long id = scopedRequest(ticketRef);
        service.preview(id, new PrivacyDsarCommands.Preview(4L));
        return id;
    }

    /** A が作り B が承認した（版6）case。actor は B のまま返す。 */
    private long approvedRequest(String ticketRef) {
        long id = previewedRequest(ticketRef);
        actor.set(adminB());
        service.approve(id, approval(5L, onlyPackage(id)));
        return id;
    }

    private static PrivacyDsarCommands.Approval approval(long version, PrivacyExportPackage pkg) {
        return new PrivacyDsarCommands.Approval(version, pkg.getId(), pkg.getContentSha256());
    }

    private static PrivacyDsarCommands.Delivery delivery(long version, PrivacyExportPackage pkg) {
        return new PrivacyDsarCommands.Delivery(version, pkg.getId(), pkg.getContentSha256(), "EMAIL", "SEND-2026-0001");
    }

    private List<PrivacyExportPackage> packages(long requestId) {
        return packageMapper.selectList(new LambdaQueryWrapper<PrivacyExportPackage>()
                .eq(PrivacyExportPackage::getTenantId, TENANT)
                .eq(PrivacyExportPackage::getRequestId, requestId)
                .orderByAsc(PrivacyExportPackage::getId));
    }

    private PrivacyExportPackage onlyPackage(long requestId) {
        List<PrivacyExportPackage> rows = packages(requestId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private List<PrivacyAction> actions(long requestId, String type) {
        return actionMapper.selectList(new LambdaQueryWrapper<PrivacyAction>()
                .eq(PrivacyAction::getTenantId, TENANT)
                .eq(PrivacyAction::getRequestId, requestId)
                .eq(PrivacyAction::getActionType, type)
                .orderByAsc(PrivacyAction::getId));
    }

    private void catalog(String elementId, String providerId) {
        jdbcTemplate.update("INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, "
                        + "provider_id, subject_attribution) VALUES (?, ?, 'DB_COLUMN', 't_fixture.column', ?, 'ATTRIBUTABLE')",
                TENANT, elementId, providerId);
    }

    private Path base() {
        return Paths.get(uploadProperties.getBasePath()).toAbsolutePath().normalize();
    }

    private Path published(String storedName) {
        return base().resolve("published").resolve(storedName);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(409);
            assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarConflict");
        });
    }

    private static void assertNotApproved(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(409);
            assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarPackageNotApproved");
        });
    }

    private static void assertNotDownloaded(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(409);
            assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarDeliveryNotDownloaded");
        });
    }

    /**
     * ENGINEER:101 の2行を返す fixture provider。1行目は第三者混在の PROFILE と本人だけの SKILL の両方に属し、第三者（顧客名）の
     * 値を持つ。2行目は SKILL だけに属する。
     */
    static final class SubjectProvider implements ProductionPrivacyProvider {

        static final String ID = "dsar-package-fixture";
        static final String PROFILE = "PKG_FIXTURE_PROFILE";
        static final String SKILL = "PKG_FIXTURE_SKILL";

        final List<String> calls = new ArrayList<>();
        /** 列を1つも持たない行も返す（review P3-5）。 */
        boolean emptyRecord;

        @Override
        public String providerId() {
            return ID;
        }

        @Override
        public List<ProviderDataElement> dataElements() {
            List<SubjectLink> links = List.of(new SubjectLink(PrivacySubjectType.ENGINEER, "t_engineer.id"));
            return List.of(ProviderDataElement.exact(PROFILE, SubjectAttribution.ATTRIBUTABLE, links),
                    ProviderDataElement.exact(SKILL, SubjectAttribution.ATTRIBUTABLE, DsarDeliveryClass.SELF, links));
        }

        @Override
        public List<ProviderTable> tables() {
            return List.of(new ProviderTable("t_engineer", List.of("id", "tenant_id", "name", "email", "customer_name"),
                            List.of()),
                    new ProviderTable("t_engineer_skill", List.of("id", "tenant_id", "skill_note"), List.of()));
        }

        @Override
        public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
            calls.add(subject.ref());
            if (!TENANT.equals(tenantId) || subject.type() != PrivacySubjectType.ENGINEER || subject.id() != 101L) {
                return List.of();
            }
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", 101L);
            profile.put("tenant_id", TENANT);
            profile.put("name", SUBJECT_NAME);
            profile.put("email", SUBJECT_EMAIL);
            profile.put("customer_name", THIRD_PARTY);
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("id", 1010L);
            skill.put("tenant_id", TENANT);
            skill.put("skill_note", SKILL_NOTE);
            if (emptyRecord) {
                return List.of(new PrivacyRecord(ID, "t_engineer", 101L, List.of(PROFILE, SKILL), false, profile, Set.of()),
                        new PrivacyRecord(ID, "t_engineer_skill", 1010L, List.of(SKILL), false, skill, Set.of()),
                        new PrivacyRecord(ID, "t_engineer_skill", 2020L, List.of(SKILL), false, Map.of(), Set.of()));
            }
            return List.of(new PrivacyRecord(ID, "t_engineer", 101L, List.of(PROFILE, SKILL), false, profile, Set.of()),
                    new PrivacyRecord(ID, "t_engineer_skill", 1010L, List.of(SKILL), false, skill, Set.of()));
        }
    }

    /** 範囲確定の後に登録された provider（範囲 snapshot の再計算を変える）。 */
    static final class ExtraProvider implements ProductionPrivacyProvider {

        @Override
        public String providerId() {
            return "dsar-package-extra";
        }

        @Override
        public List<ProviderDataElement> dataElements() {
            return List.of(ProviderDataElement.exact("PKG_FIXTURE_EXTRA", SubjectAttribution.ATTRIBUTABLE,
                    List.of(new SubjectLink(PrivacySubjectType.ENGINEER, "t_extra.engineer_id"))));
        }

        @Override
        public List<ProviderTable> tables() {
            return List.of(new ProviderTable("t_extra", List.of("id", "engineer_id"), List.of()));
        }

        @Override
        public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
            return List.of();
        }
    }
}
