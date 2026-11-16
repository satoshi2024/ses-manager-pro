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
        assertThat(service.section(deletion).actions()).isEmpty();

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
        assertThat(service.section(id).actions()).containsExactly("APPROVE");
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
        PrivacyHoldGate gate = mock(PrivacyHoldGate.class);
        holdGate.set(gate);
        long expiring = previewedRequest("T-HELD-EXPIRE");
        PrivacyExportPackage expiringPackage = onlyPackage(expiring);
        long delivering = approvedRequest("T-HELD-DELIVER");
        PrivacyExportPackage deliveringPackage = onlyPackage(delivering);

        // 交付記録の削除（期限内の承認済み package の実体を同じ tx で消す）
        actor.set(adminA());
        service.recordDelivery(delivering, delivery(6L, deliveringPackage));
        assertThat(packageMapper.selectById(deliveringPackage.getId()).getStatus()).isEqualTo("PURGED");
        assertThat(Files.exists(published(deliveringPackage.getStoredName()))).isFalse();

        // 期限超過の削除（scheduler の経路）
        assertThat(service.purgeDue(NOW.plusDays(31))).isEqualTo(1);
        assertThat(packageMapper.selectById(expiringPackage.getId()).getStatus()).isEqualTo("PURGED");
        assertThat(Files.exists(published(expiringPackage.getStoredName()))).isFalse();
        org.mockito.Mockito.verifyNoInteractions(gate);
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

    // ---------------------------------------------------------------- helpers

    private PrivacyRequestService requestService() {
        return new PrivacyRequestService(mock(PrivacyAccessGuard.class), oidc(),
                new PrivacyDsarDecisions(approval, features, CLOCK), requestMapper, linkMapper, actionMapper,
                registry, new PrivacyDsarAudit(auditLog), nested(), CLOCK, actor::get);
    }

    private PrivacyExportPackageService service(Clock clock) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivacyApprovalProperties> approvalProvider = mock(ObjectProvider.class);
        when(approvalProvider.getIfAvailable()).thenAnswer(invocation -> approval);
        @SuppressWarnings("unchecked")
        ObjectProvider<ThirdPartyRedactor.Rules> rulesProvider = mock(ObjectProvider.class);
        when(rulesProvider.getIfUnique()).thenAnswer(invocation -> rules.get());
        return new PrivacyExportPackageService(mock(PrivacyAccessGuard.class), oidc(),
                new PrivacyDsarDecisions(approval, features, clock), requestMapper, linkMapper, actionMapper,
                packageMapper, new PrivacySearchService(registry, dataElementMapper), registry,
                new ThirdPartyRedactor(approvalProvider, rulesProvider), fileStorageService, holdGate::get,
                new PrivacyDsarAudit(auditLog), nested(), clock, actor::get);
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

    /**
     * ENGINEER:101 の2行を返す fixture provider。1行目は第三者混在の PROFILE と本人だけの SKILL の両方に属し、第三者（顧客名）の
     * 値を持つ。2行目は SKILL だけに属する。
     */
    static final class SubjectProvider implements ProductionPrivacyProvider {

        static final String ID = "dsar-package-fixture";
        static final String PROFILE = "PKG_FIXTURE_PROFILE";
        static final String SKILL = "PKG_FIXTURE_SKILL";

        final List<String> calls = new ArrayList<>();

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
