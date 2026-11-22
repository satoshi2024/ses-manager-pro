package com.ses.service.security.epoch;

import com.ses.service.impl.EngineerAccountLinkServiceImpl;
import com.ses.service.impl.EngineerSalesServiceImpl;
import com.ses.service.impl.EngineerServiceImpl;
import com.ses.service.impl.OrganizationServiceImpl;
import com.ses.service.impl.RoleMenuServiceImpl;
import com.ses.service.impl.SysUserServiceImpl;
import com.ses.service.impl.SystemConfigServiceImpl;
import com.ses.service.lifecycle.ResignationGateChecker;
import com.ses.service.lifecycle.impl.LifecycleCaseServiceImpl;
import com.ses.service.lifecycle.impl.LifecycleTaskServiceImpl;
import com.ses.service.portal.impl.PortalEngineerLinkServiceImpl;
import com.ses.service.security.impl.OwnershipRepairServiceImpl;
import com.ses.service.security.impl.PermissionGroupManagementServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U15（NF01細粒度epoch計画 §2.4 E-3・§4.5・§6.2）: discoveryを伴うepoch writerが {@code READ_COMMITTED} を宣言し、
 * epoch writerが {@code NESTED} の中でfenceを呼ばないことを静的に固定する。
 *
 * <p>H2の {@code @Transactional} testでは外側transactionに参加するため分離レベルの指定が無視される。実行時assertではなく
 * 宣言を固定する（E-3）。W3-16a・W3-16b は各自の対象を {@link #READ_COMMITTED_WRITERS} へ追加する。</p>
 *
 * <p>NESTED（savepoint）のrollback後もfenceの「増分済み」集合は戻らないため、NESTED内でfenceを呼ぶと同じtransactionの
 * 後続の増分が飛ばされ失効が漏れる（W3-10 review P3-6）。fenceを使うクラスにNESTEDを置かない。</p>
 */
class WriterIsolationPolicyTest {

    /**
     * READ_COMMITTEDを宣言するwriter。W3-15分: 所属closeを内包する利用者の状態変更・削除（E-3）と、全体epoch行の
     * lockより前の通常読取がlock後の旧値読取のsnapshotにならないようにする全体epochのwriter（W3-15 review P3-3）。
     * W3-16a分（要員・link・営業）: discovery（連携ユーザー・紐付け）を伴う要員削除（E-3）と紐付け解除。
     * W3-16a後半分（組織・所属）: 計画 §2.4 E-3 の組織階層writer・統合・所属writerの全部（祖先鎖・依存集合・連携要員の
     * discoveryを伴う）。
     * W3-16b分（lifecycle）: discovery（連携ユーザー・所属・紐付け・担当営業・外部アカウント）→ 1つのplanの取得 →
     * 再discoveryを行う案件完了（計画 §2.4 E-3・§4.2）。
     * W3-02 P2-1(b)分（ポータル紐付け）: 要員lockの後に退社完了を通常読取で判定する紐付け登録（lock待ちの間にcommitされた
     * 退社完了を見るため）。
     * RB-ADMIN-GUARD分（管理者の継続性guard）: 全管理者の行の発見・lockの後に起動時条件を数え直す利用者の作成・更新と権限group
     * 割当（lock待ちの間にcommitされた無効化・role変更・割当替えをlock後の判定で見るため）。
     */
    private static final Map<Class<?>, List<String>> READ_COMMITTED_WRITERS = Map.ofEntries(
            Map.entry(PortalEngineerLinkServiceImpl.class, List.of("register")),
            Map.entry(SysUserServiceImpl.class, List.of("createUser", "updateUser", "updateUserStatus", "deleteUser")),
            Map.entry(PermissionGroupManagementServiceImpl.class, List.of("replaceAssignments")),
            Map.entry(RoleMenuServiceImpl.class, List.of("replaceMenus")),
            Map.entry(SystemConfigServiceImpl.class, List.of("updateAll")),
            Map.entry(EngineerServiceImpl.class, List.of("removeById")),
            Map.entry(EngineerAccountLinkServiceImpl.class, List.of("unlinkByEngineerId")),
            Map.entry(OrganizationServiceImpl.class, OrganizationWriters.ALL),
            Map.entry(LifecycleCaseServiceImpl.class, List.of("completeCase")),
            // 機能の開放（feature-enablement-console design §5.1）: 条件の評価で他の表の最新の commit を読む writer
            Map.entry(com.ses.featureenablement.service.FeatureEnablementServiceImpl.class, List.of("requestOpen", "approve",
                    "reject", "withdraw", "close", "autoClose", "expirePendingRequests")),
            Map.entry(com.ses.featureenablement.service.FeatureSignOffServiceImpl.class, List.of("register", "confirm",
                    "reject", "withdraw", "revoke", "requestExtension", "document")),
            Map.entry(com.ses.featureenablement.service.FeatureSignOffBundleServiceImpl.class, List.of("register",
                    "confirm", "reject", "withdraw", "revoke")));

    /** W3-16a後半でfenceへ接続した組織・所属writer（計画 §2.4 E-3 の対象と同じ）。 */
    private static final class OrganizationWriters {
        static final List<String> ALL = List.of("save", "updateOrganization", "updateStatus", "deactivate",
                "removeById", "merge", "assignUser", "updateUserOrganization", "transferUser", "releaseAssignment",
                "closeAssignmentsForUser");
    }

    /** W3-15でfenceへ接続したwriterクラス（NESTEDを置かない対象の下限）。 */
    private static final List<Class<?>> F1_WRITERS = List.of(
            RoleMenuServiceImpl.class, SystemConfigServiceImpl.class, SysUserServiceImpl.class,
            PermissionGroupManagementServiceImpl.class);

    /** W3-16aでfenceへ接続したwriterクラス（要員・link・営業・tenant補正、後半の組織・所属）。 */
    private static final List<Class<?>> F2_ENGINEER_WRITERS = List.of(
            EngineerServiceImpl.class, EngineerSalesServiceImpl.class, EngineerAccountLinkServiceImpl.class,
            OwnershipRepairServiceImpl.class, OrganizationServiceImpl.class);

    /** W3-16bでfenceへ接続したlifecycleのwriterクラス（タスク世代・退社完了）。 */
    private static final List<Class<?>> F2_LIFECYCLE_WRITERS = List.of(
            LifecycleCaseServiceImpl.class, LifecycleTaskServiceImpl.class, ResignationGateChecker.class);

    @Test
    void discoveryを伴うwriterはREAD_COMMITTEDを宣言する() {
        List<String> violations = new ArrayList<>();
        READ_COMMITTED_WRITERS.forEach((type, methods) -> {
            for (String name : methods) {
                List<Method> declared = Arrays.stream(type.getDeclaredMethods())
                        .filter(method -> method.getName().equals(name) && !method.isSynthetic())
                        .toList();
                if (declared.isEmpty()) {
                    violations.add(type.getSimpleName() + "#" + name + " が見つかりません");
                }
                for (Method method : declared) {
                    Transactional tx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
                    if (tx == null || tx.isolation() != Isolation.READ_COMMITTED) {
                        violations.add(type.getSimpleName() + "#" + name + " の分離レベル: "
                                + (tx == null ? "@Transactionalなし" : tx.isolation()));
                    }
                }
            }
        });
        assertEquals(List.of(), violations, "E-3: discoveryを伴うepoch writerは READ_COMMITTED に固定する");
    }

    @Test
    void epoch_writerはNESTEDのtransactionを宣言しない() {
        List<String> violations = new ArrayList<>();
        List<Class<?>> writers = new ArrayList<>(F1_WRITERS);
        writers.addAll(F2_ENGINEER_WRITERS);
        writers.addAll(F2_LIFECYCLE_WRITERS);
        for (Class<?> type : writers) {
            for (Method method : type.getDeclaredMethods()) {
                Transactional tx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
                if (tx != null && tx.propagation() == Propagation.NESTED) {
                    violations.add(type.getSimpleName() + "#" + method.getName());
                }
            }
        }
        int fenceUsers = 0;
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            String source = entry.getValue();
            boolean usesFence = source.contains("AuthorizationEpochFence")
                    && !entry.getKey().startsWith("com/ses/service/security/epoch/");
            if (!usesFence) {
                continue;
            }
            fenceUsers++;
            if (source.contains("Propagation.NESTED") || source.contains("PROPAGATION_NESTED")) {
                violations.add(entry.getKey() + ": fenceを使うクラスがNESTEDを使っています");
            }
        }
        assertEquals(List.of(), violations, "epoch writerをNESTED（savepoint）の中で呼ばない（W3-10 review P3-6）");
        assertTrue(fenceUsers >= writers.size(), "fenceを使うクラスを検出できていること: " + fenceUsers);
    }

    @Test
    void F2の要員writerの入口はrollbackForを持つ実transactionで宣言されている() {
        List<String> violations = new ArrayList<>();
        Map<Class<?>, List<String>> entries = Map.of(
                EngineerServiceImpl.class, List.of("save", "updateById", "updateWithStatusGuard", "removeById"),
                EngineerSalesServiceImpl.class, List.of("assign", "setPrimary", "release", "releaseAllByEngineerId"),
                EngineerAccountLinkServiceImpl.class, List.of("link", "unlinkByEngineerId"),
                OwnershipRepairServiceImpl.class, List.of("resolve"),
                OrganizationServiceImpl.class, OrganizationWriters.ALL);
        entries.forEach((type, names) -> {
            for (String name : names) {
                List<Method> declared = Arrays.stream(type.getDeclaredMethods())
                        .filter(method -> method.getName().equals(name) && !method.isSynthetic()
                                && !method.isBridge())
                        .toList();
                if (declared.isEmpty()) {
                    violations.add(type.getSimpleName() + "#" + name + " が見つかりません");
                }
                for (Method method : declared) {
                    Transactional tx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
                    if (tx == null || tx.propagation() != Propagation.REQUIRED
                            || !Arrays.asList(tx.rollbackFor()).contains(Exception.class)) {
                        violations.add(type.getSimpleName() + "#" + name);
                    }
                }
            }
        });
        assertEquals(List.of(), violations,
                "fenceはMANDATORYのため、要員writerの入口はREQUIRED＋rollbackFor=Exceptionで業務更新と増分を同じtransactionにする（E-1）");
    }

    @Test
    void lifecycleのepoch_writerの入口はrollbackForを持つ実transactionで宣言されている() {
        // W3-16b: タスク世代（保留・取消・免除・担当変更）と退社完了（actor・engineer）の増分は、業務更新と同じtransactionで行う。
        // 退社完了はCONFIRMのcoordinator（NEVER・自動再試行）を使わず、単一のREQUIREDで行う（計画 §4.2）。
        List<String> violations = new ArrayList<>();
        Map<Class<?>, List<String>> entries = Map.of(
                LifecycleCaseServiceImpl.class, List.of("holdCase", "cancelCase", "completeCase", "resumeCase"),
                LifecycleTaskServiceImpl.class, List.of("waiveTask", "reassignTask"));
        entries.forEach((type, names) -> {
            for (String name : names) {
                List<Method> declared = Arrays.stream(type.getDeclaredMethods())
                        .filter(method -> method.getName().equals(name) && !method.isSynthetic() && !method.isBridge())
                        .toList();
                if (declared.isEmpty()) {
                    violations.add(type.getSimpleName() + "#" + name + " が見つかりません");
                }
                for (Method method : declared) {
                    Transactional tx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
                    if (tx == null || tx.propagation() != Propagation.REQUIRED
                            || !Arrays.asList(tx.rollbackFor()).contains(Exception.class)) {
                        violations.add(type.getSimpleName() + "#" + name);
                    }
                }
            }
        });
        assertEquals(List.of(), violations, "lifecycleのepoch writerの入口はREQUIRED＋rollbackFor=Exception（E-1）");
    }

    @Test
    void 全体epochのwriterは旧値をlocking_readで読み外側transactionのsnapshotに依存しない() {
        // 外側transactionへ参加すると宣言した分離レベルは無視されるため、旧値はlocking readで読む（W3-15 review P3-3）。
        String roleMenu = WriterGuardSources.mainSource("com/ses/service/impl/RoleMenuServiceImpl.java");
        String systemConfig = WriterGuardSources.mainSource("com/ses/service/impl/SystemConfigServiceImpl.java");
        assertTrue(roleMenu.contains("baseMapper.selectMenuIdsByRoleForUpdate(role)"),
                "role menuの旧集合は t_role_menu のlocking readで読む");
        assertTrue(systemConfig.contains("systemConfigMapper.selectByIdForUpdate(key)")
                        && !systemConfig.contains("GLOBAL_EPOCH_LOCK_HELD"),
                "認可キーの旧値・新値は m_system_config のlocking readで読み、putはlockの有無に関係なく認可キーを拒否する");
    }

    @Test
    void F1_writerの入口はrollbackForを持つ実transactionで宣言されている() throws Exception {
        List<String> violations = new ArrayList<>();
        Map<Class<?>, List<String>> entries = Map.of(
                RoleMenuServiceImpl.class, List.of("replaceMenus"),
                SystemConfigServiceImpl.class, List.of("updateAll"),
                SysUserServiceImpl.class, List.of("createUser", "updateUser", "updateUserStatus", "deleteUser"),
                PermissionGroupManagementServiceImpl.class, List.of("replaceAssignments"));
        entries.forEach((type, names) -> {
            for (String name : names) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.getName().equals(name) || method.isSynthetic()) {
                        continue;
                    }
                    Transactional tx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
                    if (tx == null || tx.propagation() != Propagation.REQUIRED
                            || !Arrays.asList(tx.rollbackFor()).contains(Exception.class)) {
                        violations.add(type.getSimpleName() + "#" + name);
                    }
                }
            }
        });
        assertEquals(List.of(), violations,
                "fenceはMANDATORYのため、入口はREQUIRED＋rollbackFor=Exceptionで業務更新と増分を同じtransactionにする（E-1）");
    }
}
