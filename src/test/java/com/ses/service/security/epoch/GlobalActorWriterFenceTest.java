package com.ses.service.security.epoch;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U4a（NF01細粒度epoch計画 §4.5・§4.6・§6.2、W3-15）: 全体epoch・actor epochの述語が読む行の書込みが、fenceを
 * 通す許可クラスの外に無いことをDocker無しで固定する。新しいwriterを足すとこのtestが失敗する（漏れが許可ではなく失敗になる）。
 *
 * <ul>
 *   <li>権限表（{@code t_role_menu}・{@code m_menu}・{@code m_permission_group}・{@code t_permission_group_action}・
 *       {@code t_user_permission_group}）への書込みは RoleMenuServiceImpl・PermissionGroupManagementServiceImpl だけ。
 *       menu・group・actionの定義には実行時writerが無い（migration seedのみ。計画 §4.4）。</li>
 *   <li>{@code SysUserMapper} の書込み呼出しは許可クラスだけ。{@code SysUserService} に {@code IService} から継承された
 *       書込みメソッドを他クラスから呼ばない。{@code SysUser} entityの全体更新は、fenceを通す
 *       {@code SysUserServiceImpl.updateUser} と、後続バッチへ委ねた既知の例外以外で禁止する。</li>
 *   <li>anchor表（{@code sys_user}・全体epoch表）への {@code REPLACE}・{@code ON DUPLICATE KEY UPDATE}・物理DELETEを
 *       置かない（epochがDEFAULT 0へ戻り、旧提出が復活する。E-5、W3-10 review P3-8）。</li>
 *   <li>連携中ユーザーの削除拒否が残っている（engineer epochの増分契機に連携ユーザーの削除を加えない前提。計画 §2.2）。</li>
 * </ul>
 */
class GlobalActorWriterFenceTest {

    /** 権限表のmapper（単純名）。 */
    private static final List<String> PERMISSION_MAPPERS = List.of(
            "RoleMenuMapper", "MenuMapper", "PermissionGroupMapper", "PermissionGroupActionMapper",
            "UserPermissionGroupMapper");

    /** 権限表（m_permission_group は計画本文の t_permission_group の実表名）。 */
    private static final Set<String> PERMISSION_TABLES = Set.of(
            "t_role_menu", "m_menu", "m_permission_group", "t_permission_group", "t_permission_group_action",
            "t_user_permission_group");

    /** 権限表へ書いてよいクラス。 */
    private static final Set<String> PERMISSION_WRITERS = Set.of(
            "com/ses/service/impl/RoleMenuServiceImpl.java",
            "com/ses/service/security/impl/PermissionGroupManagementServiceImpl.java");

    /** 権限表への注釈SQLの書込みとして許すmapperメソッド。 */
    private static final Set<String> PERMISSION_SQL_WRITERS = Set.of("UserPermissionGroupMapper#deleteAllForUser");

    /**
     * {@code SysUserMapper} の書込みを呼んでよいクラスと、その理由・担当バッチ。
     * EngineerServiceImpl（status、W3-16a F-5）とResignationGateChecker（status、W3-16b）はfence接続を後続バッチへ委ねた。
     */
    private static final Map<String, String> SYS_USER_MAPPER_WRITERS = Map.of(
            "com/ses/service/impl/EngineerChangeRequestApprovalAdapter.java", "email列だけ（F-7、W3-15で列限定化）",
            "com/ses/service/security/impl/AccountLockServiceImpl.java", "failed_count・locked_untilだけ（updateLockState）",
            "com/ses/service/impl/EngineerServiceImpl.java", "要員削除時の連携ユーザー停止（W3-16a F-5でfence接続）",
            "com/ses/service/lifecycle/ResignationGateChecker.java", "退社完了時の連携ユーザー停止（W3-16bでfence接続）");

    /** SysUser entityの全体更新（{@code updateById(entity)}）を残してよい既知の例外（後続バッチで列限定へ改める）。 */
    private static final Set<String> SYS_USER_ENTITY_UPDATE_EXCEPTIONS = Set.of(
            "com/ses/service/impl/EngineerServiceImpl.java");

    private static final String SYS_USER_SERVICE_IMPL = "com/ses/service/impl/SysUserServiceImpl.java";
    private static final String MAPPER_PACKAGE = "com/ses/mapper/";

    private static final Pattern MAPPER_WRITE_CALL = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*((?:insert|update|delete|save|remove|replace)\\w*)\\s*\\(");
    private static final Pattern SERVICE_INHERITED_WRITE = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*(save|saveBatch|saveOrUpdate|saveOrUpdateBatch|update|updateById|updateBatchById"
                    + "|remove|removeById|removeByIds|removeByMap|removeBatchByIds|lambdaUpdate|update)\\s*\\(");
    private static final Pattern WRITE_STATEMENT = Pattern.compile(
            "\\b(INSERT\\s+(?:IGNORE\\s+)?INTO|REPLACE\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+`?(\\w+)`?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ON_DUPLICATE = Pattern.compile("ON\\s+DUPLICATE\\s+KEY\\s+UPDATE", Pattern.CASE_INSENSITIVE);

    @Test
    void 権限表への書込みは許可クラスの外に無い() {
        List<String> violations = new ArrayList<>();
        int detectedWrites = 0;
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            String relative = entry.getKey();
            String source = entry.getValue();
            for (String mapperType : PERMISSION_MAPPERS) {
                List<String> variables = WriterGuardSources.variablesOfType(source, mapperType);
                Matcher call = MAPPER_WRITE_CALL.matcher(source);
                while (call.find()) {
                    if (!variables.contains(call.group(1))) {
                        continue;
                    }
                    detectedWrites++;
                    if (!PERMISSION_WRITERS.contains(relative)) {
                        violations.add(relative + ": " + mapperType + "." + call.group(2));
                    }
                }
            }
            for (String variable : WriterGuardSources.variablesOfType(source, "RoleMenuService")) {
                Matcher call = SERVICE_INHERITED_WRITE.matcher(source);
                while (call.find()) {
                    if (call.group(1).equals(variable)) {
                        violations.add(relative + ": RoleMenuServiceの継承書込み " + call.group(2));
                    }
                }
            }
            if (relative.startsWith(MAPPER_PACKAGE)) {
                continue; // mapperの注釈SQLはメソッド単位で別testが確かめる
            }
            for (String literal : AuthorizationEpochColumnIsolationTest.stringLiterals(source)) {
                for (String table : writeTargets(literal)) {
                    if (PERMISSION_TABLES.contains(table)) {
                        violations.add(relative + ": 権限表への文字列SQL " + WriterGuardSources.abbreviate(literal));
                    }
                }
            }
        }
        assertEquals(List.of(), violations,
                "権限表を書けるのは RoleMenuServiceImpl（全体epoch）と PermissionGroupManagementServiceImpl（actor epoch）だけ");
        // PermissionGroupManagementServiceImpl の deleteAllForUser・insert を検出できていること。
        assertTrue(detectedWrites >= 2, "権限表の書込み呼出しを検出できていること: " + detectedWrites);
    }

    @Test
    void 権限表とanchor表を書く注釈SQLは既知のメソッドだけにある() throws Exception {
        List<String> violations = new ArrayList<>();
        int sysUserUpdates = 0;
        for (Class<?> mapper : mapperInterfaces()) {
            for (Method method : mapper.getDeclaredMethods()) {
                String id = mapper.getSimpleName() + "#" + method.getName();
                for (String sql : writeSql(method)) {
                    Set<String> targets = writeTargets(sql);
                    for (String table : targets) {
                        if (PERMISSION_TABLES.contains(table) && !PERMISSION_SQL_WRITERS.contains(id)) {
                            violations.add(id + ": 権限表 " + table + " を書く注釈SQL");
                        }
                    }
                    violations.addAll(anchorTableViolations(id, sql));
                    if (targets.contains("sys_user")) {
                        sysUserUpdates++;
                        if (!id.equals("SysUserMapper#updateLockState") && !id.equals("AuthorizationEpochMapper#incrementActor")) {
                            violations.add(id + ": sys_userを書く注釈SQLはfence経由の専用メソッドへ集めること");
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "権限表・anchor表の注釈SQL");
        assertTrue(sysUserUpdates >= 2, "sys_userを書く注釈SQL（updateLockState・incrementActor）を読めていること");
    }

    @Test
    void main配下の文字列SQLはanchor表をREPLACE_物理削除_ON_DUPLICATE_KEYで書かずsys_userを直接書かない() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            boolean mapperSource = entry.getKey().startsWith(MAPPER_PACKAGE);
            for (String literal : AuthorizationEpochColumnIsolationTest.stringLiterals(entry.getValue())) {
                violations.addAll(anchorTableViolations(entry.getKey(), literal));
                // mapperの注釈SQLによるsys_userの書込みはメソッド単位で別testが確かめる
                if (!mapperSource && writeTargets(literal).contains("sys_user")) {
                    violations.add(entry.getKey() + ": sys_userを書く文字列SQL " + WriterGuardSources.abbreviate(literal));
                }
            }
        }
        assertEquals(List.of(), violations, "anchor表のepochをDEFAULT 0へ戻す書込み（E-5）と、fenceを通らないsys_userの書込み");
    }

    @Test
    void SysUserMapperの書込み呼出しは許可クラスの列限定更新だけ() {
        List<String> violations = new ArrayList<>();
        Set<String> seenWriters = new TreeSet<>();
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            String relative = entry.getKey();
            String source = entry.getValue();
            List<String> variables = WriterGuardSources.variablesOfType(source, "SysUserMapper");
            Matcher call = MAPPER_WRITE_CALL.matcher(source);
            while (call.find()) {
                if (!variables.contains(call.group(1))) {
                    continue;
                }
                String method = call.group(2);
                String rest = source.substring(call.end());
                if (!SYS_USER_MAPPER_WRITERS.containsKey(relative)) {
                    violations.add(relative + ": SysUserMapper." + method + "（許可クラス外の書込み）");
                    continue;
                }
                seenWriters.add(relative);
                if (method.equals("updateById") || (method.equals("update") && !rest.stripLeading().startsWith("null"))) {
                    if (!SYS_USER_ENTITY_UPDATE_EXCEPTIONS.contains(relative)) {
                        violations.add(relative + ": SysUser entityの全体更新 " + method + "（列限定のUpdateWrapperにすること）");
                    }
                } else if (relative.endsWith("AccountLockServiceImpl.java") && !method.equals("updateLockState")) {
                    violations.add(relative + ": AccountLockServiceImplはupdateLockStateだけを使う: " + method);
                } else if (relative.endsWith("EngineerChangeRequestApprovalAdapter.java") && !method.equals("update")) {
                    violations.add(relative + ": 変更申請adapterはemail列の列限定updateだけを使う: " + method);
                }
            }
        }
        String adapter = WriterGuardSources.mainSource("com/ses/service/impl/EngineerChangeRequestApprovalAdapter.java");
        if (!adapter.contains(".set(\"email\", email)") || adapter.contains("sysUserMapper.updateById")) {
            violations.add("EngineerChangeRequestApprovalAdapter: 連携ユーザーはemail列だけを更新すること（F-7）");
        }
        assertEquals(List.of(), violations, "sys_userの書込みはfenceを通す許可クラスの列限定更新だけ（計画 §4.5 U4a）");
        assertTrue(seenWriters.containsAll(Set.of(
                        "com/ses/service/impl/EngineerChangeRequestApprovalAdapter.java",
                        "com/ses/service/security/impl/AccountLockServiceImpl.java")),
                "既知のSysUserMapper書込みを検出できていること: " + seenWriters);
    }

    @Test
    void SysUserServiceの継承書込みは他クラスから呼ばず実装内の全体更新はupdateUserだけ() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            String relative = entry.getKey();
            if (relative.equals(SYS_USER_SERVICE_IMPL)) {
                continue;
            }
            String source = entry.getValue();
            for (String variable : WriterGuardSources.variablesOfType(source, "SysUserService")) {
                Matcher call = SERVICE_INHERITED_WRITE.matcher(source);
                while (call.find()) {
                    if (call.group(1).equals(variable)) {
                        violations.add(relative + ": SysUserServiceの継承書込み " + call.group(2)
                                + "（列限定の専用メソッドを SysUserService に設けること）");
                    }
                }
            }
        }
        String impl = WriterGuardSources.mainSource(SYS_USER_SERVICE_IMPL);
        int updateUser = impl.indexOf("public void updateUser(");
        int nextMethod = impl.indexOf("public void ", updateUser + 1);
        Matcher entityUpdate = Pattern.compile("(?<![\\w.])updateById\\s*\\(").matcher(impl);
        int entityUpdates = 0;
        while (entityUpdate.find()) {
            entityUpdates++;
            if (entityUpdate.start() < updateUser || entityUpdate.start() > nextMethod) {
                violations.add(SYS_USER_SERVICE_IMPL + ": updateUser以外のSysUser全体更新（列限定のUpdateWrapperにすること）");
            }
        }
        if (entityUpdates != 1) {
            violations.add(SYS_USER_SERVICE_IMPL + ": fenceを通す全体更新はupdateUserの1箇所だけ: " + entityUpdates);
        }
        assertEquals(List.of(), violations, "SysUserServiceの書込み経路（計画 §4.5 U4a）");
    }

    @Test
    void 連携中ユーザーの削除拒否とtenant変更の拒否が残っている() {
        String impl = WriterGuardSources.mainSource(SYS_USER_SERVICE_IMPL);
        assertTrue(impl.contains("engineerAccountLinkService.isUserLinked(id)")
                        && impl.contains("\"error.engineerAccount.linkedUserDelete\""),
                "連携中ユーザーの削除拒否（engineer epochの増分契機に連携ユーザーの削除を加えない前提）");
        assertTrue(impl.contains("\"error.user.tenantImmutable\""), "利用者のtenant変更の拒否（F-1）");
        assertTrue(impl.contains("sysUser.setTenantId(operator.getTenantId())"),
                "作成時はbodyのtenantではなく操作者のtenantを使う（F-1）");
    }

    // ===== helper =====

    private static List<String> anchorTableViolations(String location, String sql) {
        List<String> violations = new ArrayList<>();
        Matcher statement = WRITE_STATEMENT.matcher(sql);
        while (statement.find()) {
            String verb = statement.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            String table = statement.group(2).toLowerCase(Locale.ROOT);
            boolean anchor = table.equals("sys_user") || table.equals("t_authorization_global_epoch");
            if (!anchor) {
                continue;
            }
            if (verb.startsWith("REPLACE") || verb.startsWith("DELETE")) {
                violations.add(location + ": anchor表 " + table + " への " + verb);
            }
            if (verb.startsWith("INSERT") && ON_DUPLICATE.matcher(sql).find()) {
                violations.add(location + ": anchor表 " + table + " への INSERT ... ON DUPLICATE KEY UPDATE");
            }
        }
        return violations;
    }

    private static Set<String> writeTargets(String sql) {
        Set<String> targets = new TreeSet<>();
        Matcher statement = WRITE_STATEMENT.matcher(sql);
        while (statement.find()) {
            targets.add(statement.group(2).toLowerCase(Locale.ROOT));
        }
        return targets;
    }

    private static List<String> writeSql(Method method) {
        List<String> statements = new ArrayList<>();
        Update update = method.getAnnotation(Update.class);
        if (update != null) statements.add(String.join(" ", update.value()));
        Insert insert = method.getAnnotation(Insert.class);
        if (insert != null) statements.add(String.join(" ", insert.value()));
        Delete delete = method.getAnnotation(Delete.class);
        if (delete != null) statements.add(String.join(" ", delete.value()));
        return statements;
    }

    private static List<Class<?>> mapperInterfaces() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:com/ses/mapper/**/*.class");
        Set<String> names = new TreeSet<>();
        for (Resource resource : resources) {
            String url = resource.getURL().toString().replace('\\', '/');
            if (url.contains("/test-classes/") || url.contains("-tests.jar")) {
                continue;
            }
            String name = url.substring(url.indexOf("com/ses/mapper/")).replace('/', '.').replace(".class", "");
            if (!name.contains("$")) {
                names.add(name);
            }
        }
        List<Class<?>> mappers = new ArrayList<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (String name : names) {
            Class<?> type = Class.forName(name, false, loader);
            if (type.isInterface()) {
                mappers.add(type);
            }
        }
        assertTrue(mappers.size() > 50, "com.ses.mapper のMapperを読めていること: " + mappers.size());
        return mappers;
    }
}
