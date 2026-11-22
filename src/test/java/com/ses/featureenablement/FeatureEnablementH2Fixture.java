package com.ses.featureenablement;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffKind;
import com.ses.featureenablement.condition.SignOffRequirements;
import com.ses.featureenablement.service.FeatureEnablementActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 機能の開放の H2 試験の行の用意（試験の transaction の中で入れ、rollback で消える）。
 */
public final class FeatureEnablementH2Fixture {

    public static final String TENANT = "default";
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000L);

    private final JdbcTemplate jdbc;
    private final FeatureEnablementCatalog catalog;

    public FeatureEnablementH2Fixture(JdbcTemplate jdbc, FeatureEnablementCatalog catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    /** 有効な管理者を作る。 */
    public FeatureEnablementActor admin() {
        return user("管理者");
    }

    /** 利用者を作る。 */
    public FeatureEnablementActor user(String role) {
        String username = "fe" + SEQUENCE.incrementAndGet() + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("INSERT INTO sys_user (tenant_id, username, password, real_name, role, status, failed_count, "
                + "deleted_flag) VALUES (?, ?, 'pw', 'FE試験', ?, 1, 0, 0)", TENANT, username, role);
        Long id = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        return new FeatureEnablementActor(id, username);
    }

    /**
     * 開放単位が要する全ての種別の確認済みの署名を入れる（組に属する種別は確認済みの組も作る）。種別 → 署名 ID を返す。
     */
    public Map<String, Long> confirmedSignOffs(Feature feature, LocalDate signedOn, LocalDate expiresOn) {
        Map<String, Long> ids = new LinkedHashMap<>();
        Map<String, Map<String, Long>> bundles = new LinkedHashMap<>();
        for (String kind : SignOffRequirements.applicableKinds(catalog, feature, Map.of())) {
            Long existing = confirmedId(kind);
            long id = existing != null ? existing : signOff(kind, "CONFIRMED", signedOn, expiresOn);
            ids.put(kind, id);
            catalog.kind(kind).flatMap(SignOffKind::bundle)
                    .ifPresent(bundle -> bundles.computeIfAbsent(bundle, key -> new LinkedHashMap<>()).put(kind, id));
        }
        bundles.forEach((bundle, members) -> {
            if (confirmedBundleId(bundle) == null) {
                bundle(bundle, "CONFIRMED", members);
            }
        });
        return ids;
    }

    /** 署名を1件入れる（登録者・確認者は別の利用者 ID）。 */
    public long signOff(String kind, String status, LocalDate signedOn, LocalDate expiresOn) {
        String stored = UUID.randomUUID().toString().replace("-", "") + ".pdf";
        boolean confirmed = List.of("CONFIRMED", "SUPERSEDED", "EXPIRED").contains(status);
        SignOffKind definition = catalog.kind(kind).orElseThrow();
        jdbc.update("INSERT INTO t_feature_sign_off (tenant_id, kind_code, reference, signer_role, signed_on, expires_on, "
                        + "kind_class, content_sha256, size_bytes, stored_name, status, registered_by, registered_at, "
                        + "confirmed_by, confirmed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 10, ?, ?, 990001, "
                        + "TIMESTAMP '2026-11-01 10:00:00', ?, ?)", TENANT, kind, "REF-" + stored.substring(0, 8),
                definition.signerRoles().get(0), signedOn, expiresOn, definition.kindClass().name(), "a".repeat(64),
                stored, status, confirmed ? 990002L : null,
                confirmed ? java.sql.Timestamp.valueOf("2026-11-01 11:00:00") : null);
        return jdbc.queryForObject("SELECT id FROM t_feature_sign_off WHERE stored_name = ?", Long.class, stored);
    }

    /** 組を1件入れる（member は種別 → 署名 ID）。 */
    public long bundle(String bundleKind, String status, Map<String, Long> members) {
        boolean confirmed = List.of("CONFIRMED", "SUPERSEDED").contains(status);
        jdbc.update("INSERT INTO t_feature_sign_off_bundle (tenant_id, bundle_kind, status, registered_by, registered_at, "
                        + "confirmed_by, confirmed_at) VALUES (?, ?, ?, 990001, TIMESTAMP '2026-11-01 10:00:00', ?, ?)",
                TENANT, bundleKind, status, confirmed ? 990002L : null,
                confirmed ? java.sql.Timestamp.valueOf("2026-11-01 11:00:00") : null);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM t_feature_sign_off_bundle WHERE tenant_id = ? AND bundle_kind = ?",
                Long.class, TENANT, bundleKind);
        members.forEach((kind, signOffId) -> jdbc.update("INSERT INTO t_feature_sign_off_bundle_member (bundle_id, "
                + "tenant_id, kind_code, sign_off_id, carried_over) VALUES (?, ?, ?, ?, 0)", id, TENANT, kind, signOffId));
        return id;
    }

    public Long confirmedId(String kind) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM t_feature_sign_off WHERE tenant_id = ? AND kind_code = ? "
                + "AND status = 'CONFIRMED'", Long.class, TENANT, kind);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Long confirmedBundleId(String bundleKind) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM t_feature_sign_off_bundle WHERE tenant_id = ? "
                + "AND bundle_kind = ? AND status = 'CONFIRMED'", Long.class, TENANT, bundleKind);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 開放状態（行が無ければ CLOSED）。 */
    public String state(Feature feature) {
        List<String> states = jdbc.queryForList("SELECT state FROM t_feature_enablement_state WHERE tenant_id = ? "
                + "AND feature_key = ?", String.class, TENANT, feature.name());
        return states.isEmpty() ? "CLOSED" : states.get(0);
    }

    public String closedReason(Feature feature) {
        return jdbc.queryForObject("SELECT closed_reason FROM t_feature_enablement_state WHERE tenant_id = ? "
                + "AND feature_key = ?", String.class, TENANT, feature.name());
    }

    public String requestStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM t_feature_enablement_request WHERE id = ?", String.class, id);
    }

    public int requestVersion(long id) {
        return jdbc.queryForObject("SELECT version FROM t_feature_enablement_request WHERE id = ?", Integer.class, id);
    }

    public long countAudit(String applicationCode) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE application_code = ? AND method = 'FEAT_EN'",
                Long.class, applicationCode);
    }

    public long countEvents(String subjectType, String eventType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_enablement_event WHERE tenant_id = ? "
                + "AND subject_type = ? AND event_type = ?", Long.class, TENANT, subjectType, eventType);
    }

    public long countNotifications(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_notification WHERE recipient_user_id = ? AND type = 'FEATURE_ENABLEMENT'",
                Long.class, userId);
    }

    /**
     * 試験の transaction に登録された afterCommit を呼ぶ（試験の transaction は commit しないため、commit の後の通知と snapshot の
     * 読直しを同じ transaction の中で確かめる）。本機能の部品が登録したものだけを1回ずつ呼ぶ（MyBatis・接続の後始末の登録には触れない。
     * rollback では afterCommit は呼ばれないため二重にならない）。
     */
    public static void runAfterCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization.getClass().getName().startsWith("com.ses.featureenablement.")
                    && INVOKED.add(synchronization)) {
                synchronization.afterCommit();
            }
        }
    }

    private static final java.util.Set<TransactionSynchronization> INVOKED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
}
