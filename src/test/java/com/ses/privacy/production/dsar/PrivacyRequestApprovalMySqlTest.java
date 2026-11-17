package com.ses.privacy.production.dsar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.config.MyBatisPlusConfig;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.UploadProperties;
import com.ses.dto.file.StoredFile;
import com.ses.entity.FileSecurityMetadata;
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
import com.ses.service.FileStorageService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.impl.FileSecurityMetadataReferenceProvider;
import com.ses.service.impl.FileStorageServiceImpl;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import com.ses.service.security.impl.UnavailableFileScanner;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlConstraintRejections;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * NF07-A2b: 実 MySQL（Flyway で V190 以降を適用）で DSAR の承認・交付記録の直列化と、DB 層の作成者≠承認者・承認の束縛を確かめる
 * （production-bridge-phased-plan.md §3.5.2 の対象限定テスト）。
 *
 * <ul>
 *   <li>二重承認・二重交付記録: 同じ版の要求を2つの transaction が出すと、後の要求の CAS は先の transaction の request 行の X lock を
 *       待ち、先の commit 後に0件となって 409 になる（lock の待機は {@link InnoDbRecordLockWaits#waitEdgesOwnedBy} で確かめる。
 *       {@code data_locks.LOCK_DATA} の値には依存しない）。package の PURGED と実体の削除は1回だけ。</li>
 *   <li>DB の CHECK: {@code prepared_by} が NULL のままの承認、作成者と同じ承認者、承認4項目の一部だけの記録、package の作成者による
 *       承認を拒否する。複合FK: 別の request の package を承認対象にできない。</li>
 *   <li>承認後に作った別の package へ承認を付け替えても、内容 hash が承認時と違えば専用ダウンロードを拒否する。</li>
 *   <li>review P2-1 の SQL: 汎用経路の遮断の照会（package 行と PRIVACY_EXPORT の metadata の副問合せの和。metadata は照会者の
 *       tenant だけで、一意索引を使える。HYG-FINAL P3-D）、保存名の大文字小文字の照合順序（package は utf8mb4_bin、metadata は
 *       utf8mb4_unicode_ci）、孤児の選び方（created_at を書いた時計から計算した境界と、_bin・_ci 混在の表をまたぐ保存名の比較）。</li>
 *   <li>HYG-FINAL（A2b delta review §4 の 2・4〜8）: DB・JVM の時刻帯によらない孤児の猶予、孤児の削除の end-to-end、scan できない
 *       草案の autocommit での後始末、並行の専用ダウンロードの重複 key の吸収、手動対応の完了の並行 CAS、期限切れの承認 package の
 *       作り直しの CHECK・FK。</li>
 * </ul>
 * 開示 package の実体は既定では in-memory の {@link FileStorageService} に置く（file の quarantine → published は H2 の
 * {@code PrivacyExportPackageServiceTest} が実装で確かめる。孤児の削除と scan の失敗のケースだけ、一時 directory の実
 * {@link FileStorageServiceImpl} を使う）。各 test は専用の tenant を使い、commit した行は class 専用の container DB に残る
 * （他の test の行は読まない）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class PrivacyRequestApprovalMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf07_dsar_approval")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    /** JST 2026-11-16 10:00。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), ZoneId.of("Asia/Tokyo"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 10, 0);
    private static final long ADMIN_A = 1_516_401L;
    private static final long ADMIN_B = 1_516_402L;
    private static final long ADMIN_C = 1_516_403L;

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
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FileSecurityMetadataMapper metadataMapper;
    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    private String tenant;
    private InMemoryFileStorage storage;
    private ProductionPrivacyProviderRegistry registry;

    @BeforeEach
    void setUp() {
        tenant = "dsar-approval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        storage = new InMemoryFileStorage();
        registry = new ProductionPrivacyProviderRegistry(List.of(new FixtureProvider()));
    }

    @Test
    void 同じ草案の二重承認はrequest行のCASで直列化され後の承認は409になる() throws Exception {
        long id = previewed("T-DOUBLE-APPROVE");
        PrivacyExportPackage pkg = onlyPackage(id);
        PrivacyExportPackageService approverB = service(ADMIN_B);
        PrivacyExportPackageService approverC = service(ADMIN_C);

        Outcome outcome = race(() -> approverB.approve(id, approval(pkg)), () -> approverC.approve(id, approval(pkg)));

        assertThat(outcome.first).isNull();
        assertConflict(outcome.second);
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("APPROVED");
        assertThat(row.getApprovedBy()).isEqualTo(ADMIN_B);
        assertThat(row.getRowVersion()).isEqualTo(6L);
        assertThat(packageMapper.selectById(pkg.getId()).getApprovedBy()).isEqualTo(ADMIN_B);
    }

    @Test
    void 二重の交付記録はrequest行のCASで直列化され後の記録は409でpackageは1回だけPURGEDになる() throws Exception {
        long id = approved("T-DOUBLE-DELIVERY");
        PrivacyExportPackage pkg = onlyPackage(id);
        PrivacyExportPackageService recorderA = service(ADMIN_A);
        PrivacyExportPackageService recorderB = service(ADMIN_B);
        // 交付の記録は専用ダウンロードの取得の印を要する（HYG-FINAL P3-B）
        recorderA.download(id, pkg.getId());

        Outcome outcome = race(() -> recorderA.recordDelivery(id, delivery(pkg, "SEND-A")),
                () -> recorderB.recordDelivery(id, delivery(pkg, "SEND-B")));

        assertThat(outcome.first).isNull();
        assertConflict(outcome.second);
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("CONFIRMED");
        assertThat(row.getDeliveredBy()).isEqualTo(ADMIN_A);
        assertThat(row.getDeliveryEvidenceRef()).isEqualTo("SEND-A");
        assertThat(packageMapper.selectById(pkg.getId()).getStatus()).isEqualTo("PURGED");
        assertThat(storage.deletes.get()).as("実体の削除は1回だけ").isEqualTo(1);
        assertThat(storage.files).doesNotContainKey(pkg.getStoredName());
    }

    @Test
    void 作成者NULLの承認と作成者と同じ承認者と束縛の一部だけの記録とpackageの作成者による承認はDBが拒否する() {
        long id = previewed("T-DB-CHECK");
        PrivacyExportPackage pkg = onlyPackage(id);
        String sha = pkg.getContentSha256();

        // MySQL の CHECK 違反（3819）は SQLState HY000 で UncategorizedSQLException に翻訳されるため、例外の型ではなく
        // error code と制約名で DB の拒否を判定する（統合候補 174632c8 の Runner で検出）
        jdbc.update("UPDATE t_privacy_request SET prepared_by = NULL WHERE tenant_id = ? AND id = ?", tenant, id);
        MySqlConstraintRejections.assertRejectedBy(() -> bindApproval(id, ADMIN_B, pkg.getId(), sha),
                MySqlConstraintRejections.CHECK_VIOLATION, "chk_privacy_request_sod");
        jdbc.update("UPDATE t_privacy_request SET prepared_by = ? WHERE tenant_id = ? AND id = ?", ADMIN_A, tenant, id);
        MySqlConstraintRejections.assertRejectedBy(() -> bindApproval(id, ADMIN_A, pkg.getId(), sha),
                MySqlConstraintRejections.CHECK_VIOLATION, "chk_privacy_request_sod");
        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("UPDATE t_privacy_request SET approved_by = ?, "
                        + "approved_at = ? WHERE tenant_id = ? AND id = ?", ADMIN_B, NOW, tenant, id),
                MySqlConstraintRejections.CHECK_VIOLATION, "chk_privacy_request_approval_binding");
        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("UPDATE t_privacy_export_package SET status = "
                        + "'APPROVED', approved_by = ?, approved_at = ? WHERE tenant_id = ? AND id = ?",
                        ADMIN_A, NOW, tenant, pkg.getId()),
                MySqlConstraintRejections.CHECK_VIOLATION, "chk_privacy_export_package_sod");

        long otherRequest = previewed("T-DB-CHECK-OTHER");
        PrivacyExportPackage otherPackage = onlyPackage(otherRequest);
        MySqlConstraintRejections.assertRejectedBy(
                () -> bindApproval(id, ADMIN_B, otherPackage.getId(), otherPackage.getContentSha256()),
                MySqlConstraintRejections.FOREIGN_KEY_VIOLATION, "fk_privacy_request_approved_package");

        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getApprovedBy()).isNull();
        assertThat(row.getStatus()).isEqualTo("PREVIEWED");
        // service 経由の承認は作成者以外なら通り、DB の CHECK と同じ条件を満たす
        service(ADMIN_B).approve(id, approval(pkg));
        assertThat(requestMapper.selectById(id).getApprovedPackageId()).isEqualTo(pkg.getId());
    }

    @Test
    void 承認後に作った別のpackageへ承認を付け替えても内容hashが違えば専用ダウンロードを拒否する() {
        long id = approved("T-REBIND");
        PrivacyExportPackage approvedPackage = onlyPackage(id);
        PrivacyExportPackage other = new PrivacyExportPackage();
        other.setTenantId(tenant);
        other.setRequestId(id);
        other.setStoredName(storage.put("{\"forged\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        other.setContentSha256(PrivacyExportPackageContent.sha256("{\"forged\":true}"));
        other.setSizeBytes(15L);
        other.setStatus("DRAFT");
        other.setPurgeAfter(NOW.plusDays(30));
        other.setCreatedBy(ADMIN_C);
        other.setCreatedAt(NOW);
        packageMapper.insert(other);
        // 承認の package だけを別の package へ付け替える（同じ request の package なので複合FK・CHECK は通る）
        jdbc.update("UPDATE t_privacy_request SET approved_package_id = ? WHERE tenant_id = ? AND id = ?",
                other.getId(), tenant, id);

        PrivacyExportPackageService downloader = service(ADMIN_A);
        assertNotApproved(() -> downloader.download(id, other.getId()));
        assertNotApproved(() -> downloader.download(id, approvedPackage.getId()));
        assertNotApproved(() -> downloader.recordDelivery(id, new PrivacyDsarCommands.Delivery(6L, other.getId(),
                approvedPackage.getContentSha256(), "EMAIL", "SEND-1")));
        assertThat(requestMapper.selectById(id).getStatus()).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------- review P2-1 の SQL（実 MySQL の照合順序と時計）

    /** 汎用 file 経路の遮断の照会（{@code countByStoredNames} の2つの副問合せの和）。 */
    @Test
    void 汎用経路の照合はpackage行とPRIVACY_EXPORTのmetadataのどちらに当たっても数え業務fileは数えない() {
        long id = previewed("T-SQL-COUNT");
        String draft = onlyPackage(id).getStoredName();
        String purged = name();
        insertPackage(id, purged, "PURGED");
        String liveMetadata = name();
        String deletedMetadata = name();
        String photo = UUID.randomUUID().toString().replace("-", "") + ".png";
        insertMetadata(liveMetadata, "PRIVACY_EXPORT", old(), 0);
        insertMetadata(deletedMetadata, "PRIVACY_EXPORT", old(), 1);
        insertMetadata(photo, "PHOTO", old(), 0);

        assertThat(packageMapper.countByStoredNames(tenant, draft, draft)).as("DRAFT の package 行").isEqualTo(1L);
        assertThat(packageMapper.countByStoredNames(tenant, purged, purged)).as("PURGED の package 行").isEqualTo(1L);
        assertThat(packageMapper.countByStoredNames(tenant, liveMetadata, liveMetadata)).as("PRIVACY_EXPORT の metadata")
                .isEqualTo(1L);
        assertThat(packageMapper.countByStoredNames(tenant, deletedMetadata, deletedMetadata))
                .as("論理削除した PRIVACY_EXPORT の metadata").isEqualTo(1L);
        assertThat(packageMapper.countByStoredNames(tenant, photo, photo)).as("業務 file の metadata").isZero();
        assertThat(packageMapper.countByStoredNames(tenant, name(), name())).as("どこにも無い保存名").isZero();
        // 生の名前と正規化した名前の IN（同じ値を2つ渡しても重ねて数えない）
        assertThat(packageMapper.countByStoredNames(tenant, photo, draft)).isEqualTo(1L);
        // HYG-FINAL P3-D: package の行は照会者の tenant に依らず数え、PRIVACY_EXPORT の metadata は照会者の tenant だけで数える
        // （他 tenant の metadata だけを持つ名前は、汎用の load・rescan が照会者の tenant の metadata を要して拒否する）
        String otherTenant = tenant + "-x";
        assertThat(packageMapper.countByStoredNames(otherTenant, draft, draft)).as("他 tenant から見た package 行")
                .isEqualTo(1L);
        assertThat(packageMapper.countByStoredNames(otherTenant, purged, purged)).isEqualTo(1L);
        assertThat(packageMapper.countByStoredNames(otherTenant, liveMetadata, liveMetadata))
                .as("他 tenant の metadata は数えない").isZero();
    }

    /**
     * HYG-FINAL P3-D（review §4 の 1）: 遮断の照会の metadata 側は {@code uk_file_security_stored_name (tenant_id, stored_name)} を
     * 使える（tenant 無しの照会は使える索引が無く全表走査だった）。最適化の選択は表の大きさで変わり得るため、使える索引
     * （{@code possible_keys}）を確かめる。SQL は mapper に登録された文をそのまま使う。
     */
    @Test
    void 遮断の照会のmetadata側はtenantと保存名の一意索引を使える() {
        String stored = name();
        insertMetadata(stored, "PRIVACY_EXPORT", old(), 0);
        MappedStatement statement = sqlSessionFactory.getConfiguration()
                .getMappedStatement(PrivacyExportPackageMapper.class.getName() + ".countByStoredNames");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenant);
        parameters.put("storedName", stored);
        parameters.put("normalizedName", stored);
        BoundSql bound = statement.getBoundSql(parameters);
        Object[] arguments = bound.getParameterMappings().stream()
                .map(mapping -> parameters.get(mapping.getProperty())).toArray();

        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN " + bound.getSql(), arguments);

        assertThat(plan).filteredOn(row -> "t_file_security_metadata".equals(row.get("table"))).singleElement()
                .satisfies(row -> assertThat(String.valueOf(row.get("possible_keys")))
                        .contains("uk_file_security_stored_name"));
    }

    /**
     * 保存名の大文字小文字の扱い（照合順序の実際の挙動）: package の表は utf8mb4_bin で区別し、file metadata の表は
     * utf8mb4_unicode_ci で区別しない。遮断は生の名前と小文字に正規化した名前の両方を渡すため、どちらの表でも大文字の別名を拒否できる。
     */
    @Test
    void 保存名の大文字小文字はpackage行では区別しmetadataでは区別せず遮断は正規化した名前で両方に当たる() {
        assertThat(columnCollation("t_privacy_export_package", "stored_name")).isEqualTo("utf8mb4_bin");
        assertThat(columnCollation("t_file_security_metadata", "stored_name")).isEqualTo("utf8mb4_unicode_ci");
        long id = previewed("T-SQL-CASE");
        String packageName = onlyPackage(id).getStoredName();
        String metadataName = name();
        insertMetadata(metadataName, "PRIVACY_EXPORT", old(), 0);
        String packageUpper = packageName.toUpperCase(java.util.Locale.ROOT);
        String metadataUpper = metadataName.toUpperCase(java.util.Locale.ROOT);

        assertThat(packageMapper.countByStoredNames(tenant, packageUpper, packageUpper))
                .as("utf8mb4_bin の package 行は大文字の別名に当たらない").isZero();
        assertThat(packageMapper.countByStoredNames(tenant, packageUpper, packageName))
                .as("遮断は小文字に正規化した名前で package 行に当たる").isEqualTo(1L);
        assertThat(packageMapper.countByStoredNames(tenant, metadataUpper, metadataUpper))
                .as("utf8mb4_unicode_ci の metadata は大文字の別名にも当たる").isEqualTo(1L);
    }

    /**
     * 孤児の選び方（{@code selectOrphanFiles}）: created_at を書いた時計で猶予（1時間）を過ぎ、論理削除されておらず、PURGED でない
     * package の行を持たない PRIVACY_EXPORT の metadata だけ。package 行との保存名の比較は utf8mb4_bin と utf8mb4_unicode_ci の混在で、
     * MySQL は _bin の照合順序で比べる（大文字小文字の違う名前は同じ file とみなさない。保存名は両表とも同じ小文字の値で書かれる）。
     */
    @Test
    void 孤児は猶予を過ぎてpackage行を持たないPRIVACY_EXPORTのmetadataだけを選び猶予内と業務fileと論理削除済みは選ばない() {
        long id = previewed("T-SQL-ORPHAN");
        String live = onlyPackage(id).getStoredName();
        String purged = name();
        insertPackage(id, purged, "PURGED");
        String liveOther = name();
        insertPackage(id, liveOther, "DRAFT");

        String orphanOld = name();
        String orphanFresh = name();
        String deleted = name();
        String photo = UUID.randomUUID().toString().replace("-", "") + ".png";
        String caseVariant = liveOther.toUpperCase(java.util.Locale.ROOT);
        insertMetadata(orphanOld, "PRIVACY_EXPORT", old(), 0);
        insertMetadata(orphanFresh, "PRIVACY_EXPORT", fresh(), 0);
        insertMetadata(live, "PRIVACY_EXPORT", old(), 0);
        insertMetadata(purged, "PRIVACY_EXPORT", old(), 0);
        insertMetadata(deleted, "PRIVACY_EXPORT", old(), 1);
        insertMetadata(photo, "PHOTO", old(), 0);
        insertMetadata(caseVariant, "PRIVACY_EXPORT", old(), 0);

        Set<String> selected = orphanNames(cutoffAt(Instant.now()));

        assertThat(selected).as("猶予を過ぎ行の無いもの・PURGED の行だけのもの・_bin で別名になるもの")
                .containsExactlyInAnyOrder(orphanOld, purged, caseVariant);
        assertThat(selected).doesNotContain(orphanFresh, live, deleted, photo);
    }

    // ---------------------------------------------------------------- HYG-FINAL（A2b delta review §4 の 2・4〜8）

    /**
     * §4 の 2（HYG-FINAL P3-A）: 孤児の猶予は created_at を書いた時計（{@code AutoFillMetaObjectHandler}）で数え、DB の session の
     * 時刻帯（UTC・+09:00）にも JVM の時刻帯（Asia/Tokyo・UTC）にも依らない。created_at は MetaObjectHandler が書き、境界は service が
     * 同じ時計から計算して渡す。修正前の {@code TIMESTAMPADD(HOUR, -1, LOCALTIMESTAMP)} は JVM=UTC・DB=+09:00 で猶予が負になり保存
     * 直後の行を選び、JVM=Asia/Tokyo・DB=UTC で10時間選ばなかった。行は transaction ごと巻き戻す。
     */
    @Test
    void 孤児の猶予はDBとJVMの時刻帯がUTCでもJSTでも書いた時計で数え保存直後の行を選ばない() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String jvmZone : List.of("Asia/Tokyo", "UTC")) {
                for (String dbZone : List.of("+00:00", "+09:00")) {
                    TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
                    String label = "JVM=" + jvmZone + " DB=" + dbZone;
                    String fresh = name();
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        String previous = jdbc.queryForObject("SELECT @@session.time_zone", String.class);
                        jdbc.execute("SET time_zone = '" + dbZone + "'");
                        try {
                            metadataMapper.insert(metadataRow(fresh));
                            Instant afterWrite = Instant.now();
                            assertThat(orphanNames(cutoffAt(afterWrite))).as(label + ": 猶予の内は選ばない")
                                    .doesNotContain(fresh);
                            assertThat(orphanNames(cutoffAt(afterWrite.plus(Duration.ofMinutes(61)))))
                                    .as(label + ": 猶予を過ぎたら選ぶ").contains(fresh);
                        } finally {
                            jdbc.execute("SET time_zone = '" + previous + "'");
                            status.setRollbackOnly();
                        }
                    });
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    /**
     * §4 の 4: 孤児の削除の end-to-end。{@code purgeDue} が tenant ごとに実の {@code FileStorageService.delete} を呼び、実体を消して
     * metadata を論理削除し、{@code FileSecurityMetadataReferenceProvider} はその名前を申告しなくなる。生きている package の
     * metadata は猶予を過ぎていても消さない。
     */
    @Test
    void 孤児の削除は実体とmetadataを消して参照の申告から外し生きているpackageの実体は残す(@TempDir Path uploads)
            throws Exception {
        long id = previewed("T-ORPHAN-E2E");
        String live = onlyPackage(id).getStoredName();
        String orphan = name();
        Files.createDirectories(uploads.resolve("published"));
        Files.writeString(uploads.resolve("published").resolve(orphan), "{\"orphan\":true}");
        Files.writeString(uploads.resolve("published").resolve(live), "{\"live\":true}");
        insertMetadata(orphan, "PRIVACY_EXPORT", old(), 0);
        insertMetadata(live, "PRIVACY_EXPORT", old(), 0);
        FileSecurityMetadataReferenceProvider references = new FileSecurityMetadataReferenceProvider(metadataMapper);
        assertThat(references.referencedFileNames()).contains(orphan, live);
        FileStorageService real = realStorage(uploads, (path, kind) -> FileScanResult.clean("mysql-test"));

        // package の削除の基準は固定の NOW（どの package も期限前）、孤児の猶予は実時間の時計で数える
        service(ADMIN_A, real, Clock.systemUTC(), new PrivacyRequestServiceTest.CapturingAuditLogService()).purgeDue(NOW);

        assertThat(Files.exists(uploads.resolve("published").resolve(orphan))).as("孤児の実体").isFalse();
        assertThat(deletedFlag(orphan)).as("孤児の metadata は論理削除").isEqualTo(1);
        assertThat(references.referencedFileNames()).as("論理削除した名前は申告しない").doesNotContain(orphan)
                .contains(live);
        assertThat(Files.exists(uploads.resolve("published").resolve(live))).as("生きている package の実体").isTrue();
        assertThat(deletedFlag(live)).isZero();
        assertThat(packageMapper.selectById(onlyPackage(id).getId()).getStatus()).isEqualTo("DRAFT");
    }

    /**
     * §4 の 5: scan できず保存できない server 生成の file（autocommit）。metadata は拒否の結果（QUARANTINED・UNAVAILABLE）を書いた
     * まま論理削除され、実体は quarantine にも published にも残らない。{@code uk(tenant_id, stored_name)} の行は残るが、次の保存は
     * 別の保存名で通り衝突しない。
     */
    @Test
    void scanできない草案はautocommitでも拒否の結果を書いたmetadataを論理削除し実体を残さず次の保存と衝突しない(
            @TempDir Path uploads) {
        byte[] content = "{\"format\":\"nf07-dsar-export-package-v1\"}".getBytes(StandardCharsets.UTF_8);
        FileStorageService unscannable = realStorage(uploads, new UnavailableFileScanner());

        assertThatThrownBy(() -> AccountingTenantContextHolder.runWithTenant(tenant,
                () -> unscannable.store(content, "dsar.json", FileKind.PRIVACY_EXPORT)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.file.scanRejected"));

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT stored_name, storage_state, scan_status, deleted_flag "
                + "FROM t_file_security_metadata WHERE tenant_id = ?", tenant);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("storage_state")).isEqualTo("QUARANTINED");
            assertThat(row.get("scan_status")).isEqualTo("UNAVAILABLE");
            assertThat(((Number) row.get("deleted_flag")).intValue()).isEqualTo(1);
        });
        String discarded = (String) rows.get(0).get("stored_name");
        assertThat(Files.exists(uploads.resolve("quarantine").resolve(discarded))).isFalse();
        assertThat(Files.exists(uploads.resolve("published").resolve(discarded))).isFalse();
        assertThat(new FileSecurityMetadataReferenceProvider(metadataMapper).referencedFileNames())
                .doesNotContain(discarded);

        FileStorageService scannable = realStorage(uploads, (path, kind) -> FileScanResult.clean("mysql-test"));
        StoredFile stored = AccountingTenantContextHolder.runWithTenant(tenant,
                () -> scannable.store(content, "dsar.json", FileKind.PRIVACY_EXPORT));
        assertThat(stored.getStoredName()).isNotEqualTo(discarded);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_file_security_metadata WHERE tenant_id = ? "
                + "AND storage_state = 'PUBLISHED' AND deleted_flag = 0", Integer.class, tenant)).isEqualTo(1);
    }

    /**
     * §4 の 6: 並行の専用ダウンロード。後の取得は先の取得の未 commit の印（{@code uk_privacy_action_idem}）を待ち、先の commit の後に
     * 重複 key（1062）になる。{@code markDownloaded} がそれを transaction の中で捕まえても MySQL の transaction は巻き戻し専用に
     * ならず、後の取得も commit して内容を返す。印は1行、取得の監査は2件。
     */
    @Test
    void 並行の専用ダウンロードは取得の印を1行にし後の取得は重複keyを捕まえてもcommitでき監査は2件残る() throws Exception {
        long id = approved("T-DOUBLE-DOWNLOAD");
        PrivacyExportPackage pkg = onlyPackage(id);
        PrivacyRequestServiceTest.CapturingAuditLogService audit = new PrivacyRequestServiceTest.CapturingAuditLogService();
        PrivacyExportPackageService downloaderA = service(ADMIN_A, storage, CLOCK, audit);
        PrivacyExportPackageService downloaderC = service(ADMIN_C, storage, CLOCK, audit);

        Outcome outcome = race(() -> downloaderA.download(id, pkg.getId()), () -> downloaderC.download(id, pkg.getId()),
                "t_privacy_action");

        assertThat(outcome.first).isNull();
        assertThat(outcome.second).as("重複 key を捕まえた後の取得の transaction は commit できる").isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_privacy_action WHERE tenant_id = ? AND request_id = ? "
                        + "AND idempotency_key = ?", Integer.class, tenant, id,
                PrivacyExportPackageService.DOWNLOAD_KEY_PREFIX + id + ":" + pkg.getId())).isEqualTo(1);
        assertThat(audit.codes).filteredOn("NF07_DSAR_PACKAGE_DOWNLOADED"::equals).hasSize(2);
        assertThat(requestMapper.selectById(id).getStatus()).isEqualTo("APPROVED");
    }

    /**
     * §4 の 7: 開示以外の請求の手動対応の完了。同じ版の2つの記録は request 行の CAS で直列化され、後の記録は 409。最も長い結果
     * コード（{@code blocked_reason} VARCHAR(50)）と128文字の証跡の参照（{@code evidence_ref} VARCHAR(128)）が入り、CONFIRMED で
     * 交付の4項目が NULL のままの行は request の CHECK を全て満たす（commit できること自体で確かめる）。
     */
    @Test
    void 手動対応の完了の並行CASは1件だけ通り最長の結果コードと証跡参照が入りCONFIRMEDの行はCHECKを満たす() throws Exception {
        long id = scoped("T-MANUAL-RACE", "DELETION");
        String longest = java.util.Arrays.stream(PrivacyDsarVocabulary.ManualResolution.values())
                .filter(code -> code.appliesTo("DELETION")).map(Enum::name)
                .max(java.util.Comparator.comparingInt(String::length)).orElseThrow();
        String evidence = "EV-" + "a".repeat(125);
        PrivacyRequestService first = requestService(ADMIN_A);
        PrivacyRequestService second = requestService(ADMIN_C);

        Outcome outcome = race(
                () -> first.recordManualResolution(id, new PrivacyDsarCommands.ManualResolution(4L, longest, evidence)),
                () -> second.recordManualResolution(id, new PrivacyDsarCommands.ManualResolution(4L, "DELETED", "EV-2")),
                "t_privacy_request");

        assertThat(outcome.first).isNull();
        assertConflict(outcome.second);
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("CONFIRMED");
        assertThat(row.getRowVersion()).isEqualTo(5L);
        assertThat(row.getDeliveredBy()).isNull();
        assertThat(row.getDeliveredAt()).isNull();
        assertThat(row.getDeliveryMethod()).isNull();
        assertThat(row.getDeliveryEvidenceRef()).isNull();
        List<Map<String, Object>> manual = jdbc.queryForList("SELECT blocked_reason, evidence_ref FROM t_privacy_action "
                + "WHERE tenant_id = ? AND request_id = ? AND idempotency_key LIKE ?", tenant, id,
                PrivacyRequestService.MANUAL_KEY_PREFIX + id + ":%");
        assertThat(manual).singleElement().satisfies(action -> {
            assertThat(action.get("blocked_reason")).isEqualTo(longest);
            assertThat(action.get("evidence_ref")).isEqualTo(evidence);
        });
    }

    /**
     * §4 の 8: 取得していない期限切れの承認 package の作り直し。{@code DSAR-SUPERSEDED:} の行の追加、request の承認4項目の消去、
     * 旧 package の PURGED（承認者・承認日時・hash は残す）、新しい草案の追加が1つの transaction で
     * {@code chk_privacy_request_approval_binding}・{@code chk_privacy_export_package_approval} と複合 FK を満たす。
     */
    @Test
    void 期限切れの承認packageの作り直しは外れた承認の行と承認の消去と旧packageのPURGEDをCHECKとFKを満たして書く() {
        long id = approved("T-REDRAFT-APPROVED");
        PrivacyExportPackage approvedPackage = onlyPackage(id);
        Clock afterExpiry = Clock.fixed(Instant.parse("2026-12-17T01:00:00Z"), ZoneId.of("Asia/Tokyo"));

        PrivacyDsarViews.Transition redone = service(ADMIN_A, storage, afterExpiry,
                new PrivacyRequestServiceTest.CapturingAuditLogService()).preview(id, new PrivacyDsarCommands.Preview(6L));

        assertThat(redone.status()).isEqualTo("PREVIEWED");
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("PREVIEWED");
        assertThat(row.getApprovedBy()).isNull();
        assertThat(row.getApprovedAt()).isNull();
        assertThat(row.getApprovedPackageId()).isNull();
        assertThat(row.getApprovedContentSha256()).isNull();
        PrivacyExportPackage purged = packageMapper.selectById(approvedPackage.getId());
        assertThat(purged.getStatus()).isEqualTo("PURGED");
        assertThat(purged.getApprovedBy()).as("承認者は残す").isEqualTo(ADMIN_B);
        assertThat(purged.getApprovedAt()).isNotNull();
        assertThat(purged.getContentSha256()).isEqualTo(approvedPackage.getContentSha256());
        Map<String, Object> superseded = jdbc.queryForMap("SELECT blocked_reason, evidence_ref, result_hash "
                + "FROM t_privacy_action WHERE tenant_id = ? AND request_id = ? AND idempotency_key LIKE ?", tenant, id,
                PrivacyExportPackageService.SUPERSEDED_KEY_PREFIX + id + ":%");
        assertThat(superseded.get("blocked_reason")).isEqualTo("APPROVAL_EXPIRED");
        assertThat(superseded.get("evidence_ref")).isEqualTo("PKG:" + approvedPackage.getId());
        assertThat(superseded.get("result_hash")).isEqualTo(approvedPackage.getContentSha256());
        List<PrivacyExportPackage> packages = packageMapper.selectList(new LambdaQueryWrapper<PrivacyExportPackage>()
                .eq(PrivacyExportPackage::getTenantId, tenant)
                .eq(PrivacyExportPackage::getRequestId, id)
                .orderByAsc(PrivacyExportPackage::getId));
        assertThat(packages).hasSize(2);
        assertThat(packages.get(1).getStatus()).isEqualTo("DRAFT");
        assertThat(storage.files).doesNotContainKey(approvedPackage.getStoredName())
                .containsKey(packages.get(1).getStoredName());
    }

    // ---------------------------------------------------------------- helpers

    /** created_at を書く時計（{@code AutoFillMetaObjectHandler}）で、猶予（1時間）を過ぎた作成日時。 */
    private static LocalDateTime old() {
        return MyBatisPlusConfig.AutoFillMetaObjectHandler.rowTimestamp(Clock.systemUTC()).minusHours(2);
    }

    /** created_at を書く時計で、猶予の内の作成日時。 */
    private static LocalDateTime fresh() {
        return MyBatisPlusConfig.AutoFillMetaObjectHandler.rowTimestamp(Clock.systemUTC()).minusMinutes(10);
    }

    private static String name() {
        return UUID.randomUUID().toString().replace("-", "") + ".json";
    }

    /** 瞬間 {@code at} に動く service が孤児の選択に渡す作成日時の上限（本番と同じ計算）。 */
    private LocalDateTime cutoffAt(Instant at) {
        return service(ADMIN_A, storage, Clock.fixed(at, ZoneId.of("Asia/Tokyo")),
                new PrivacyRequestServiceTest.CapturingAuditLogService()).orphanCreatedBefore();
    }

    /** この test の tenant で孤児として選ばれる保存名（全 tenant の照会から絞る）。 */
    private Set<String> orphanNames(LocalDateTime createdBefore) {
        Set<String> selected = new java.util.TreeSet<>();
        for (FileSecurityMetadata row : packageMapper.selectOrphanFiles(createdBefore, 1000)) {
            if (tenant.equals(row.getTenantId())) {
                selected.add(row.getStoredName());
            }
        }
        return selected;
    }

    /** MetaObjectHandler が created_at を書く PRIVACY_EXPORT の metadata の行（mapper で insert する）。 */
    private FileSecurityMetadata metadataRow(String storedName) {
        FileSecurityMetadata row = new FileSecurityMetadata();
        row.setTenantId(tenant);
        row.setStoredName(storedName);
        row.setFileKind("PRIVACY_EXPORT");
        row.setStorageState("PUBLISHED");
        row.setScanStatus("CLEAN");
        return row;
    }

    private int deletedFlag(String storedName) {
        return jdbc.queryForObject("SELECT deleted_flag FROM t_file_security_metadata WHERE tenant_id = ? "
                + "AND stored_name = ?", Integer.class, tenant, storedName);
    }

    /** 一時 directory を upload 先にした実の {@link FileStorageServiceImpl}（実の metadata mapper）。 */
    private FileStorageService realStorage(Path uploads, FileScanner scanner) {
        UploadProperties properties = new UploadProperties();
        properties.setBasePath(uploads.toString());
        @SuppressWarnings("unchecked")
        ObjectProvider<FileScanner> scanners = mock(ObjectProvider.class);
        org.mockito.Mockito.when(scanners.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(scanner);
        @SuppressWarnings("unchecked")
        ObjectProvider<FileSecurityMetadataMapper> metadata = mock(ObjectProvider.class);
        org.mockito.Mockito.when(metadata.getIfAvailable()).thenReturn(metadataMapper);
        return new FileStorageServiceImpl(properties, scanners, metadata);
    }

    private void insertPackage(long requestId, String storedName, String status) {
        PrivacyExportPackage row = new PrivacyExportPackage();
        row.setTenantId(tenant);
        row.setRequestId(requestId);
        row.setStoredName(storedName);
        row.setContentSha256("e".repeat(64));
        row.setSizeBytes(2L);
        row.setStatus(status);
        row.setPurgeAfter(NOW.plusDays(30));
        row.setCreatedBy(ADMIN_A);
        row.setCreatedAt(NOW);
        if ("PURGED".equals(status)) {
            row.setPurgedAt(NOW);
        }
        packageMapper.insert(row);
    }

    /** file metadata の行（作成日時は created_at を書く時計の値で与える）。 */
    private void insertMetadata(String storedName, String fileKind, LocalDateTime createdAt, int deletedFlag) {
        jdbc.update("INSERT INTO t_file_security_metadata (tenant_id, stored_name, file_kind, storage_state, scan_status, "
                + "created_at, deleted_flag) VALUES (?, ?, ?, 'PUBLISHED', 'CLEAN', ?, ?)",
                tenant, storedName, fileKind, createdAt, deletedFlag);
    }

    private String columnCollation(String table, String column) {
        return jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = ? AND COLUMN_NAME = ?", String.class, table, column);
    }

    /** SCOPED（版4）まで進めた case。 */
    private long scoped(String ticketRef, String requestType) {
        PrivacyRequestService requests = requestService();
        long id = requests.intake(new PrivacyDsarCommands.Intake(requestType, "EMAIL", null, ticketRef, null))
                .requestId();
        requests.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "EV-" + ticketRef));
        requests.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        long linkId = linkMapper.selectList(new LambdaQueryWrapper<PrivacyRequestSubjectLink>()
                .eq(PrivacyRequestSubjectLink::getTenantId, tenant)
                .eq(PrivacyRequestSubjectLink::getRequestId, id)).get(0).getId();
        requests.resolveCandidate(id, linkId, new PrivacyDsarCommands.CandidateResolution(2L, "SELECTED", null));
        requests.scope(id, new PrivacyDsarCommands.ScopeConfirmation(3L));
        return id;
    }

    private long previewed(String ticketRef) {
        long id = scoped(ticketRef, "ACCESS");
        service(ADMIN_A).preview(id, new PrivacyDsarCommands.Preview(4L));
        return id;
    }

    private long approved(String ticketRef) {
        long id = previewed(ticketRef);
        service(ADMIN_B).approve(id, approval(onlyPackage(id)));
        return id;
    }

    private void bindApproval(long requestId, long approver, long packageId, String sha) {
        jdbc.update("UPDATE t_privacy_request SET approved_by = ?, approved_at = ?, approved_package_id = ?, "
                + "approved_content_sha256 = ? WHERE tenant_id = ? AND id = ?", approver, NOW, packageId, sha, tenant,
                requestId);
    }

    private PrivacyExportPackage onlyPackage(long requestId) {
        List<PrivacyExportPackage> rows = packageMapper.selectList(new LambdaQueryWrapper<PrivacyExportPackage>()
                .eq(PrivacyExportPackage::getTenantId, tenant)
                .eq(PrivacyExportPackage::getRequestId, requestId)
                .orderByAsc(PrivacyExportPackage::getId));
        assertThat(rows).isNotEmpty();
        return rows.get(0);
    }

    private static PrivacyDsarCommands.Approval approval(PrivacyExportPackage pkg) {
        return new PrivacyDsarCommands.Approval(5L, pkg.getId(), pkg.getContentSha256());
    }

    private static PrivacyDsarCommands.Delivery delivery(PrivacyExportPackage pkg, String evidence) {
        return new PrivacyDsarCommands.Delivery(6L, pkg.getId(), pkg.getContentSha256(), "EMAIL", evidence);
    }

    private PrivacyApprovalProperties decisions() {
        PrivacyApprovalProperties approval = new PrivacyApprovalProperties();
        approval.getD01().setDecisionId("D01-NF07-2026-0001");
        approval.getD01().setSignedOn("2026-11-01");
        approval.getD01().setExpiresOn("2027-10-31");
        approval.getD06().setDecisionId("D06-NF07-2026-0006");
        approval.getD06().setSignedOn("2026-11-05");
        approval.getD06().setExpiresOn("2027-11-04");
        return approval;
    }

    private PrivacyFeatureProperties features() {
        PrivacyFeatureProperties features = new PrivacyFeatureProperties();
        features.getDsar().setDueDays(30);
        features.getDsar().setPackageMaxAge(Duration.ofDays(30));
        return features;
    }

    private OidcSecurityProperties oidc() {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId(tenant);
        return oidc;
    }

    private PrivacyRequestService requestService() {
        return requestService(ADMIN_A);
    }

    private PrivacyRequestService requestService(long actorId) {
        // 本番と同じ REQUIRED（呼出し側の transaction があれば参加し、無ければ自分で commit する）
        return new PrivacyRequestService(mock(PrivacyAccessGuard.class), oidc(),
                new PrivacyDsarDecisions(decisions(), features(), CLOCK), requestMapper, linkMapper, actionMapper,
                registry, new PrivacyDsarAudit(new PrivacyRequestServiceTest.CapturingAuditLogService()),
                new TransactionTemplate(transactionManager), CLOCK,
                () -> new PrivacyRequestService.Actor(tenant, actorId, "privacy-admin-" + actorId));
    }

    private PrivacyExportPackageService service(long actorId) {
        return service(actorId, storage, CLOCK, new PrivacyRequestServiceTest.CapturingAuditLogService());
    }

    private PrivacyExportPackageService service(long actorId, FileStorageService fileStorage, Clock clock,
                                                PrivacyRequestServiceTest.CapturingAuditLogService auditLog) {
        PrivacyApprovalProperties approval = decisions();
        @SuppressWarnings("unchecked")
        ObjectProvider<PrivacyApprovalProperties> approvalProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ThirdPartyRedactor.Rules> rulesProvider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(approvalProvider.getIfAvailable()).thenReturn(approval);
        return new PrivacyExportPackageService(mock(PrivacyAccessGuard.class), oidc(),
                new PrivacyDsarDecisions(approval, features(), clock), requestMapper, linkMapper, actionMapper,
                packageMapper, new PrivacySearchService(registry, dataElementMapper), registry,
                new ThirdPartyRedactor(approvalProvider, rulesProvider), fileStorage, () -> null,
                new PrivacyDsarAudit(auditLog), new TransactionTemplate(transactionManager), clock,
                () -> new PrivacyRequestService.Actor(tenant, actorId, "privacy-admin-" + actorId));
    }

    /**
     * 先の操作を transaction の中で実行して commit 前に止め、後の操作が request 行の lock を待つことを確かめてから先を commit する。
     * 戻り値は各操作の例外（成功なら null）。
     */
    private Outcome race(Supplier<?> firstOperation, Supplier<?> secondOperation) throws Exception {
        return race(firstOperation, secondOperation, "t_privacy_request");
    }

    /** {@link #race(Supplier, Supplier)} の、後の操作が待つ lock の表を指定する版。 */
    private Outcome race(Supplier<?> firstOperation, Supplier<?> secondOperation, String lockedTable) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong blocker = new AtomicLong();
        try {
            Future<Throwable> first = pool.submit(() -> capture(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        blocker.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        firstOperation.get();
                        firstApplied.countDown();
                        await(releaseFirst);
                    })));
            assertTrue(firstApplied.await(20, TimeUnit.SECONDS), "先の操作が commit 前の状態に届く");
            Future<Throwable> second = pool.submit(() -> capture(secondOperation::get));
            awaitWaitOn(blocker.get(), lockedTable);
            releaseFirst.countDown();
            return new Outcome(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    private void awaitWaitOn(long blockerConnectionId, String lockedTable) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (!InnoDbRecordLockWaits.waitEdgesOwnedBy(jdbc, blockerConnectionId, lockedTable, null).isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("後の操作が " + lockedTable + " の行の lock を待っていない: "
                + InnoDbRecordLockWaits.describeWaitsForBlocker(jdbc, blockerConnectionId));
    }

    private static Throwable capture(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("解放待ちが時間切れ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void assertConflict(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(BusinessException.class, e -> {
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

    private record Outcome(Throwable first, Throwable second) {
    }

    /** 開示 package の実体を memory に置く {@link FileStorageService}（削除の回数を数える）。 */
    private static final class InMemoryFileStorage implements FileStorageService {

        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final AtomicInteger deletes = new AtomicInteger();

        String put(byte[] data) {
            String name = UUID.randomUUID().toString().replace("-", "") + ".json";
            files.put(name, data.clone());
            return name;
        }

        @Override
        public StoredFile store(MultipartFile file, FileKind kind) {
            throw new UnsupportedOperationException("upload は扱わない");
        }

        @Override
        public StoredFile store(byte[] data, String originalName, FileKind kind) {
            return new StoredFile(put(data), originalName, data.length);
        }

        @Override
        public Resource load(String storedName) {
            byte[] data = files.get(storedName);
            if (data == null) {
                throw BusinessException.of("error.file.notFound");
            }
            return new ByteArrayResource(data.clone());
        }

        @Override
        public void delete(String storedName) {
            deletes.incrementAndGet();
            files.remove(storedName);
        }

        @Override
        public boolean rescan(String storedName) {
            return false;
        }
    }

    /** どの tenant でも ENGINEER:101 に、第三者混在の1行と本人だけの1行を返す fixture provider。 */
    private final class FixtureProvider implements ProductionPrivacyProvider {

        @Override
        public String providerId() {
            return "dsar-approval-mysql";
        }

        @Override
        public List<ProviderDataElement> dataElements() {
            List<SubjectLink> links = List.of(new SubjectLink(PrivacySubjectType.ENGINEER, "t_engineer.id"));
            return List.of(ProviderDataElement.exact("APPROVAL_MYSQL_PROFILE", SubjectAttribution.ATTRIBUTABLE, links),
                    ProviderDataElement.exact("APPROVAL_MYSQL_SKILL", SubjectAttribution.ATTRIBUTABLE,
                            DsarDeliveryClass.SELF, links));
        }

        @Override
        public List<ProviderTable> tables() {
            return List.of(new ProviderTable("t_engineer", List.of("id", "tenant_id", "name"), List.of()));
        }

        @Override
        public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
            if (subject.type() != PrivacySubjectType.ENGINEER || subject.id() != 101L) {
                return List.of();
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("id", 101L);
            fields.put("tenant_id", tenantId);
            fields.put("name", "承認 試験");
            return List.of(new PrivacyRecord(providerId(), "t_engineer", 101L,
                            List.of("APPROVAL_MYSQL_PROFILE", "APPROVAL_MYSQL_SKILL"), false, fields, Set.of()),
                    new PrivacyRecord(providerId(), "t_engineer", 1010L, List.of("APPROVAL_MYSQL_SKILL"), false, fields,
                            Set.of()));
        }
    }
}
