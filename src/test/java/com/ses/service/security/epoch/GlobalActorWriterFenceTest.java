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
     * {@code SysUserMapper} の書込みを呼んでよいクラスごとの契約（件数・メソッド・書いてよい列）。ファイル単位では許さず、
     * 契約外の書込みを1件でも足すと失敗する（W3-15 review P3-2）。
     *
     * <ul>
     *   <li>EngineerServiceImpl（要員削除時の連携ユーザー停止）は W3-16a F-5 で、lock済みの連携ユーザー行の status列だけを
     *       書く列限定更新＋actor epochのfence接続へ改めた（entityの全体更新の既知例外は無くなった）。</li>
     *   <li>ResignationGateChecker（退社完了時の連携ユーザー停止）は W3-16b で、lock済みの連携ユーザー行の status・updated_at列
     *       だけを書く列限定更新＋actor epochのfence接続（status変化時だけ）にした。退社完了では連携ユーザー行を案件完了の
     *       lock plan（rank 3 X、要員の rank 7 より先）で取得済みである。</li>
     * </ul>
     */
    private static final Map<String, SysUserWriteContract> SYS_USER_MAPPER_WRITERS = Map.of(
            "com/ses/service/impl/EngineerChangeRequestApprovalAdapter.java",
            new SysUserWriteContract("email列だけ（F-7、W3-15で列限定化）", 1, Set.of("update"), Set.of("email")),
            "com/ses/service/security/impl/AccountLockServiceImpl.java",
            new SysUserWriteContract("failed_count・locked_untilだけ（列限定の注釈SQL）", 3, Set.of("updateLockState"), Set.of()),
            "com/ses/service/impl/EngineerServiceImpl.java",
            new SysUserWriteContract("要員削除時の連携ユーザー停止（status列だけ、F-5。W3-16aでfence接続）", 1,
                    Set.of("update"), Set.of("status")),
            "com/ses/service/lifecycle/ResignationGateChecker.java",
            new SysUserWriteContract("退社完了時の連携ユーザー停止（status・updated_at列だけ。W3-16bでactor epochのfenceへ接続）", 1,
                    Set.of("update"), Set.of("status", "updated_at")));

    /**
     * SysUser entityの全体更新（{@code updateById(entity)}・{@code update(entity, wrapper)}）を残してよい既知の例外。
     * W3-16a F-5 で EngineerServiceImpl の全体更新を列限定へ改めたため、例外は無い（SysUserServiceImpl.updateUser は別契約）。
     */
    private static final Set<String> SYS_USER_ENTITY_UPDATE_EXCEPTIONS = Set.of();

    /**
     * SysUserServiceImpl 内の書込み呼出しの契約（メソッド → 件数）。fenceを通す入口（create/update/status/delete/
     * パスワード変更）以外の書込みを足すと失敗する。
     */
    private static final Map<String, Integer> SYS_USER_SERVICE_IMPL_WRITES = Map.of(
            "save", 1, "updateById", 1, "update", 2, "removeById", 1);

    /** SysUserServiceImpl の列限定更新（UpdateWrapper）が書いてよい列（status、本人のpassword変更の2列）。 */
    private static final Set<String> SYS_USER_SERVICE_IMPL_SET_COLUMNS = Set.of(
            "status", "password", "password_change_required");

    /** 権限表・sys_userのmapperを基底に持ってよいServiceImpl（それ以外に継承書込みの入口を増やさない）。 */
    private static final Map<String, String> GUARDED_SERVICE_IMPLS = Map.of(
            "SysUserMapper", "com/ses/service/impl/SysUserServiceImpl.java",
            "RoleMenuMapper", "com/ses/service/impl/RoleMenuServiceImpl.java");

    private static final String SYS_USER_SERVICE_IMPL = "com/ses/service/impl/SysUserServiceImpl.java";
    private static final Pattern SET_COLUMN = Pattern.compile("\\.set\\s*\\(\\s*\"(\\w+)\"");
    private static final Pattern IMPL_WRITE_CALL = Pattern.compile(
            "(?<![\\w.])((?:this|super|baseMapper|getBaseMapper\\(\\))\\s*\\.\\s*)?(save|saveBatch|saveOrUpdate"
                    + "|saveOrUpdateBatch|update|updateById|updateBatchById|remove|removeById|removeByIds|removeByMap"
                    + "|removeBatchByIds|lambdaUpdate|insert|insertOrUpdate|delete|deleteById|deleteBatchIds|deleteByMap)\\s*\\(");
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
    void SysUserMapperの書込み呼出しは許可クラスの契約どおりの列限定更新だけ() {
        List<String> violations = new ArrayList<>();
        Map<String, Integer> seen = new java.util.TreeMap<>();
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
                SysUserWriteContract contract = SYS_USER_MAPPER_WRITERS.get(relative);
                if (contract == null) {
                    violations.add(relative + ": SysUserMapper." + method + "（許可クラス外の書込み）");
                    continue;
                }
                seen.merge(relative, 1, Integer::sum);
                String statement = statementFrom(source, call.end());
                if (!contract.methods().contains(method)) {
                    violations.add(relative + ": 契約外の書込みメソッド SysUserMapper." + method + "（" + contract.reason() + "）");
                }
                boolean entityUpdate = method.equals("updateById")
                        || (method.equals("update") && !statement.stripLeading().startsWith("null"));
                if (entityUpdate && !SYS_USER_ENTITY_UPDATE_EXCEPTIONS.contains(relative)) {
                    violations.add(relative + ": SysUser entityの全体更新 " + method + "（列限定のUpdateWrapperにすること）");
                }
                Set<String> columns = setColumns(statement);
                if (!contract.setColumns().containsAll(columns) || (method.equals("update") && columns.isEmpty())) {
                    violations.add(relative + ": 契約外の列を書いています " + columns + "（許可 " + contract.setColumns() + "）");
                }
            }
        }
        SYS_USER_MAPPER_WRITERS.forEach((relative, contract) -> {
            if (seen.getOrDefault(relative, 0) != contract.count()) {
                violations.add(relative + ": SysUserMapperの書込みは " + contract.count() + " 件の契約: 実際 "
                        + seen.getOrDefault(relative, 0) + " 件（" + contract.reason() + "）");
            }
        });
        assertEquals(List.of(), violations, "sys_userの書込みはfenceを通す許可クラスの契約どおりの更新だけ（計画 §4.5 U4a）");
    }

    @Test
    void SysUserServiceの継承書込みは他クラスから呼ばず実装内の書込みは契約どおり() {
        List<String> violations = new ArrayList<>();
        Pattern inheritedTypes = Pattern.compile("(?:IService\\s*<\\s*(?:[\\w.]+\\.)?SysUser\\s*>)\\s+(\\w+)\\s*(?=[;,)=])");
        Pattern providerTypes = Pattern.compile(
                "ObjectProvider\\s*<\\s*(?:[\\w.]+\\.)?SysUserService(?:Impl)?\\s*>\\s+(\\w+)\\s*(?=[;,)=])");
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            String relative = entry.getKey();
            if (relative.equals(SYS_USER_SERVICE_IMPL)) {
                continue;
            }
            String source = entry.getValue();
            Set<String> variables = new TreeSet<>(WriterGuardSources.variablesOfType(source, "SysUserService"));
            variables.addAll(WriterGuardSources.variablesOfType(source, "SysUserServiceImpl"));
            Matcher inherited = inheritedTypes.matcher(source);
            while (inherited.find()) {
                variables.add(inherited.group(1));
            }
            for (String variable : variables) {
                Matcher call = SERVICE_INHERITED_WRITE.matcher(source);
                while (call.find()) {
                    if (call.group(1).equals(variable)) {
                        violations.add(relative + ": SysUserServiceの継承書込み " + call.group(2)
                                + "（列限定の専用メソッドを SysUserService に設けること）");
                    }
                }
            }
            Matcher provider = providerTypes.matcher(source);
            while (provider.find()) {
                Matcher call = Pattern.compile("\\b" + Pattern.quote(provider.group(1))
                        + "\\s*\\.\\s*get(?:Object|IfAvailable|IfUnique)\\s*\\(\\s*\\)\\s*\\.\\s*(save\\w*|update\\w*|remove\\w*|lambdaUpdate)\\s*\\(")
                        .matcher(source);
                while (call.find()) {
                    violations.add(relative + ": ObjectProvider経由のSysUserService継承書込み " + call.group(1));
                }
            }
        }

        String impl = WriterGuardSources.mainSource(SYS_USER_SERVICE_IMPL);
        int updateUser = impl.indexOf("public void updateUser(");
        int nextMethod = impl.indexOf("public void ", updateUser + 1);
        Map<String, Integer> writes = new java.util.TreeMap<>();
        Matcher call = IMPL_WRITE_CALL.matcher(impl);
        while (call.find()) {
            String method = call.group(2);
            if (call.group(1) != null) {
                violations.add(SYS_USER_SERVICE_IMPL + ": " + call.group(1).trim() + method
                        + " の直接書込み（継承した列限定の入口だけを使う）");
            }
            writes.merge(method, 1, Integer::sum);
            if (method.equals("updateById") && (call.start() < updateUser || call.start() > nextMethod)) {
                violations.add(SYS_USER_SERVICE_IMPL + ": updateUser以外のSysUser全体更新（列限定のUpdateWrapperにすること）");
            }
            if (method.equals("update")) {
                String statement = statementFrom(impl, call.end());
                Set<String> columns = setColumns(statement);
                if (!statement.stripLeading().startsWith("new UpdateWrapper") || columns.isEmpty()
                        || !SYS_USER_SERVICE_IMPL_SET_COLUMNS.containsAll(columns)) {
                    violations.add(SYS_USER_SERVICE_IMPL + ": update は認可列status・本人のpassword変更だけの列限定更新: "
                            + columns);
                }
            }
        }
        assertEquals(SYS_USER_SERVICE_IMPL_WRITES, writes,
                "SysUserServiceImplの書込み（create・updateUser・status・password変更・delete）の件数契約");
        assertEquals(List.of(), violations, "SysUserServiceの書込み経路（計画 §4.5 U4a）");
    }

    @Test
    void sys_userを書く委任先はlock済みの行の列限定更新の後にactor_epochを進める() {
        // W3-16b: 退社完了の連携ユーザー停止は、lock済みの行（rank 3 X）の status列だけを書き、変化した時だけ
        // そのlock行のtenantでactor epochを進める（計画 §2.2・§4.2）。EngineerServiceImpl（W3-16a F-5）と同じ型。
        Map<String, List<String>> expected = Map.of(
                "com/ses/service/lifecycle/ResignationGateChecker.java", List.of(
                        "public AutomaticActionResult applyAutomaticScopeActions(", "lockLinkedUserInTenant(userId, tenantId)",
                        "sysUserMapper.update(null, new UpdateWrapper<SysUser>()", ".set(\"status\", 0)",
                        "authorizationEpochFence.advanceIfChanged(ActorEpochAnchors.of(user), user.getStatus(), 0)",
                        "organizationService.closeAssignmentsForUser(", "engineerAccountLinkService.unlinkByEngineerId(",
                        "engineerSalesService.releaseAllByEngineerId("),
                "com/ses/service/impl/EngineerServiceImpl.java", List.of(
                        "private void disableLinkedUser(", "sysUserMapper.update(null, new UpdateWrapper<SysUser>()",
                        ".set(\"status\", 0)", "authorizationEpochFence.advanceIfChanged(ActorEpochAnchors.of(lockedUser)"));
        List<String> violations = new ArrayList<>();
        expected.forEach((relative, tokens) -> {
            String source = WriterGuardSources.mainSource(relative);
            int cursor = source.indexOf(tokens.get(0));
            if (cursor < 0) {
                violations.add(relative + ": " + tokens.get(0) + " が見つかりません");
                return;
            }
            for (String token : tokens.subList(1, tokens.size())) {
                int found = source.indexOf(token, cursor);
                if (found < 0) {
                    violations.add(relative + ": " + token + " が順序どおりに見つかりません（期待順 "
                            + String.join(" → ", tokens) + "）");
                    break;
                }
                cursor = found + token.length();
            }
        });
        assertEquals(List.of(), violations, "sys_userの委任先の列限定更新とactor epoch（計画 §4.5 U4a）");
    }

    @Test
    void 権限表とsys_userのmapperを基底に持つServiceImplを増やさない() {
        List<String> violations = new ArrayList<>();
        Pattern serviceImpl = Pattern.compile("extends\\s+(?:[\\w.]+\\.)?ServiceImpl\\s*<\\s*(?:[\\w.]+\\.)?(\\w+Mapper)\\s*,");
        Set<String> guardedMappers = new TreeSet<>(PERMISSION_MAPPERS);
        guardedMappers.add("SysUserMapper");
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            Matcher matcher = serviceImpl.matcher(entry.getValue());
            while (matcher.find()) {
                String mapper = matcher.group(1);
                if (guardedMappers.contains(mapper) && !entry.getKey().equals(GUARDED_SERVICE_IMPLS.get(mapper))) {
                    violations.add(entry.getKey() + ": " + mapper + " を基底に持つServiceImpl（継承書込みの入口が増える）");
                }
            }
        }
        assertEquals(List.of(), violations, "権限表・sys_userの継承書込みの入口は既存のServiceImplだけ");
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

    /** sys_userを書いてよいクラスの契約（理由・件数・メソッド・{@code .set("列")} で書いてよい列）。 */
    private record SysUserWriteContract(String reason, int count, Set<String> methods, Set<String> setColumns) {
    }

    /** 呼出しの開き括弧の直後から、対応する閉じ括弧までの引数の文字列（文字列literal内の括弧は数えない）。 */
    private static String statementFrom(String source, int afterOpenParen) {
        int depth = 1;
        boolean inString = false;
        for (int i = afterOpenParen; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' && source.charAt(i - 1) != '\\') {
                inString = !inString;
            } else if (!inString && c == '(') {
                depth++;
            } else if (!inString && c == ')' && --depth == 0) {
                return source.substring(afterOpenParen, i);
            }
        }
        return source.substring(afterOpenParen);
    }

    private static Set<String> setColumns(String statement) {
        Set<String> columns = new TreeSet<>();
        Matcher matcher = SET_COLUMN.matcher(statement);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        return columns;
    }

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
