package com.ses.service.security.epoch;

import com.ses.service.impl.EngineerAccountLinkServiceImpl;
import com.ses.service.impl.EngineerSalesServiceImpl;
import com.ses.service.impl.EngineerServiceImpl;
import com.ses.service.impl.RoleMenuServiceImpl;
import com.ses.service.impl.SysUserServiceImpl;
import com.ses.service.impl.SystemConfigServiceImpl;
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
     */
    private static final Map<Class<?>, List<String>> READ_COMMITTED_WRITERS = Map.of(
            SysUserServiceImpl.class, List.of("updateUserStatus", "deleteUser"),
            RoleMenuServiceImpl.class, List.of("replaceMenus"),
            SystemConfigServiceImpl.class, List.of("updateAll"),
            EngineerServiceImpl.class, List.of("removeById"),
            EngineerAccountLinkServiceImpl.class, List.of("unlinkByEngineerId"));

    /** W3-15でfenceへ接続したwriterクラス（NESTEDを置かない対象の下限）。 */
    private static final List<Class<?>> F1_WRITERS = List.of(
            RoleMenuServiceImpl.class, SystemConfigServiceImpl.class, SysUserServiceImpl.class,
            PermissionGroupManagementServiceImpl.class);

    /** W3-16aでfenceへ接続したwriterクラス（要員・link・営業・tenant補正）。 */
    private static final List<Class<?>> F2_ENGINEER_WRITERS = List.of(
            EngineerServiceImpl.class, EngineerSalesServiceImpl.class, EngineerAccountLinkServiceImpl.class,
            OwnershipRepairServiceImpl.class);

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
                OwnershipRepairServiceImpl.class, List.of("resolve"));
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
