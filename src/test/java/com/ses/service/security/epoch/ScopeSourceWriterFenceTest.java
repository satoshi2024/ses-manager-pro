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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U4b（NF01細粒度epoch計画 §4.5・§4.6・§6.2、W3-16a）: 要員・組織のscope述語（§2.1(e)(f)）が読む行の書込みが、
 * fenceを通す許可クラスの契約の外に無いことをDocker無しで固定する。新しいwriterを足すとこのtestが失敗する
 * （漏れが許可ではなく失敗になる。CLAUDE.mdが禁じるaction keyの許可一覧とは向きが逆）。
 *
 * <p>対象の表: {@code t_engineer}（engineer epochのanchor）・{@code t_engineer_sales}・{@code t_engineer_account_link}・
 * {@code t_engineer_accounting_history}・{@code t_user_organization}・{@code m_organization_unit}（組織部分木epochの
 * anchor）・{@code t_organization_relation_history}。</p>
 *
 * <ul>
 *   <li>W3-16a前半（要員・link・営業・tenant補正）でfenceへ接続した書込みは「接続済み」の契約として、メソッド・件数を
 *       固定し、lock順（3 S → 7 X → 子行）と増分の呼出しが各writerに残っていることも確かめる。</li>
 *   <li>組織・所属の書込み（OrganizationServiceImpl）は W3-16a後半でfenceへ接続した。委託の例外から外し、「接続済み」の
 *       契約としてメソッド・件数を固定し、lock順（3 → 6 → 7 → 8 → 葉）と増分の呼出しが各writerに残っていること、
 *       公開の {@code updateById} override が無いこと（§11 F-8）を確かめる。</li>
 *   <li>要員行の述語外の列だけを書く既存の書込み（status・単価・version）は、理由付きの許可（計画 §4.4）として件数ごと
 *       固定する。</li>
 * </ul>
 */
class ScopeSourceWriterFenceTest {

    private static final String MAPPER_PACKAGE = "com/ses/mapper/";
    private static final String ENGINEER_SERVICE_IMPL = "com/ses/service/impl/EngineerServiceImpl.java";
    private static final String ENGINEER_SALES_SERVICE_IMPL = "com/ses/service/impl/EngineerSalesServiceImpl.java";
    private static final String ENGINEER_ACCOUNT_LINK_SERVICE_IMPL =
            "com/ses/service/impl/EngineerAccountLinkServiceImpl.java";
    private static final String ORGANIZATION_SERVICE_IMPL = "com/ses/service/impl/OrganizationServiceImpl.java";
    private static final String OWNERSHIP_REPAIR_SERVICE_IMPL =
            "com/ses/service/security/impl/OwnershipRepairServiceImpl.java";

    /** scope表を書くmapper（単純名）。 */
    private static final List<String> SCOPE_MAPPERS = List.of(
            "EngineerMapper", "EngineerSalesMapper", "EngineerAccountLinkMapper", "EngineerAccountingHistoryMapper",
            "UserOrganizationMapper", "OrganizationUnitMapper", "OrganizationRelationHistoryMapper");

    /** scope表（epochのanchor表は t_engineer と m_organization_unit）。 */
    private static final Set<String> SCOPE_TABLES = Set.of(
            "t_engineer", "t_engineer_sales", "t_engineer_account_link", "t_engineer_accounting_history",
            "t_user_organization", "m_organization_unit", "t_organization_relation_history");
    private static final Set<String> ANCHOR_TABLES = Set.of("t_engineer", "m_organization_unit");

    /**
     * mapper型の変数からの書込み呼出しの契約（"ファイル / Mapper#メソッド" → 件数）。契約外の呼出しを1件でも足す、
     * または件数が変わると失敗する。
     */
    private static final Map<String, Integer> MAPPER_WRITE_CONTRACT = contract(
            // 接続済み（W3-16a前半）
            OWNERSHIP_REPAIR_SERVICE_IMPL, "EngineerMapper#assignTenantForRepair", 1,
            OWNERSHIP_REPAIR_SERVICE_IMPL, "EngineerAccountLinkMapper#assignTenantForRepair", 1,
            ENGINEER_ACCOUNT_LINK_SERVICE_IMPL, "EngineerAccountLinkMapper#insert", 1,
            ENGINEER_ACCOUNT_LINK_SERVICE_IMPL, "EngineerAccountLinkMapper#deleteByIdForTenant", 1,
            // 会計履歴（rank 11）は呼出元の要員行X lock配下で書く。
            ENGINEER_SERVICE_IMPL, "EngineerAccountingHistoryMapper#updateById", 1,
            ENGINEER_SERVICE_IMPL, "EngineerAccountingHistoryMapper#closeCurrent", 1,
            ENGINEER_SERVICE_IMPL, "EngineerAccountingHistoryMapper#insert", 1,
            // 理由付きの許可（計画 §4.4: 述語外の列だけ）。CandidateServiceImpl は非lockの読取だが、読んだversionの
            // CASで書き戻すため、並行して変わった organization_id 等を旧値へ戻さない（根拠はFOR UPDATEではなくCAS）。
            "com/ses/service/impl/CandidateServiceImpl.java", "EngineerMapper#updateByIdForTenant", 1,
            "com/ses/service/impl/EngineerSkillServiceImpl.java", "EngineerMapper#bumpVersionForTenant", 1,
            "com/ses/service/impl/EngineerStatusServiceImpl.java", "EngineerMapper#updateById", 3,
            // W3-16b（退社完了の要員status。述語外の列。案件完了のlock planで rank 7 X を取得済みの行をlock後に読み、
            // @VersionのCASで書き戻す。engineer epochは入れ子の紐付け解除・担当営業の解除・所属の終了が進める）
            "com/ses/service/lifecycle/impl/LifecycleCaseServiceImpl.java", "EngineerMapper#updateById", 1,
            // 接続済み（W3-16a後半、組織・所属）。どれも呼出元のwriterが rank 3 → 6 → 7 → 8 のanchorをlockした後に書く。
            // 統合（merge）の要員付替（rank 7）・会計履歴（rank 11）。
            ORGANIZATION_SERVICE_IMPL, "EngineerMapper#reassignOrganization", 1,
            ORGANIZATION_SERVICE_IMPL, "EngineerAccountingHistoryMapper#updateById", 2,
            ORGANIZATION_SERVICE_IMPL, "EngineerAccountingHistoryMapper#insert", 1,
            // 所属行（rank 8）: merge 4（閉じる・原地更新・後続行・主所属の分割）、配属・更新・異動・解除・一括終了。
            ORGANIZATION_SERVICE_IMPL, "UserOrganizationMapper#updateById", 7,
            ORGANIZATION_SERVICE_IMPL, "UserOrganizationMapper#deleteById", 1,
            ORGANIZATION_SERVICE_IMPL, "UserOrganizationMapper#insert", 4,
            // 他人の所属行の上長解除。上長本人のactor epochで覆う（計画 §2.2）。
            ORGANIZATION_SERVICE_IMPL, "UserOrganizationMapper#clearManager", 1,
            // 親子履歴（葉）は当該組織行（rank 6）のX lock下で recordRelation だけが書く。
            ORGANIZATION_SERVICE_IMPL, "OrganizationRelationHistoryMapper#updateById", 1,
            ORGANIZATION_SERVICE_IMPL, "OrganizationRelationHistoryMapper#closeCurrent", 1,
            ORGANIZATION_SERVICE_IMPL, "OrganizationRelationHistoryMapper#insert", 1);

    /**
     * scope表のmapperを基底に持つServiceImplの内部書込み（継承した書込み・{@code baseMapper}・{@code super}）の契約。
     * キーは "限定子.メソッド"（限定子なしはメソッド名だけ）。
     */
    private static final Map<String, Map<String, Integer>> IMPL_WRITE_CONTRACT = Map.of(
            // 接続済み: 削除は自身の fence済みoverloadへ委ね、更新・削除はanchor lock後のCAS。
            ENGINEER_SERVICE_IMPL, Map.of("removeById", 1, "baseMapper.deleteByIdForTenant", 1,
                    "baseMapper.updateByIdForTenant", 1, "super.save", 1, "super.updateById", 1),
            // 接続済み: insert 1件と、列限定の wrapper 更新（主担当フラグ・解除日・一括解除）。
            ENGINEER_SALES_SERVICE_IMPL, Map.of("save", 1, "update", 3),
            // 接続済み（W3-16a後半）: 登録・削除は祖先鎖のlock後、組織行の更新（属性・子の付替・統合元・状態）は
            // lock済みの行のCAS。公開overrideの super.updateById は F-8 で削除した。
            ORGANIZATION_SERVICE_IMPL, Map.of("super.save", 1, "baseMapper.updateById", 4, "super.removeById", 1));

    /** scope表のmapperを基底に持ってよいServiceImpl（それ以外に継承書込みの入口を増やさない）。 */
    private static final Map<String, String> GUARDED_SERVICE_IMPLS = Map.of(
            "EngineerMapper", ENGINEER_SERVICE_IMPL,
            "EngineerSalesMapper", ENGINEER_SALES_SERVICE_IMPL,
            "OrganizationUnitMapper", ORGANIZATION_SERVICE_IMPL);

    /** scope表を書く注釈SQLとして許すmapperメソッド。 */
    private static final Set<String> SCOPE_SQL_WRITERS = Set.of(
            "AuthorizationEpochMapper#incrementEngineer", "AuthorizationEpochMapper#incrementOrganization",
            "EngineerMapper#assignTenantForRepair", "EngineerMapper#bumpVersionForTenant",
            "EngineerMapper#updateByIdForTenant", "EngineerMapper#deleteByIdForTenant",
            "EngineerMapper#reassignOrganization",
            "EngineerAccountLinkMapper#deleteByIdForTenant", "EngineerAccountLinkMapper#assignTenantForRepair",
            "EngineerAccountingHistoryMapper#closeCurrent", "OrganizationRelationHistoryMapper#closeCurrent",
            "UserOrganizationMapper#clearManager");

    /**
     * service経由の継承書込みで呼んでよいメソッド（fence済みのoverride）。EngineerSalesServiceは継承書込みを一切許さない。
     * OrganizationServiceの save・removeById は W3-16a後半でfenceへ接続したoverride（継承した updateById 等は許さない）。
     */
    private static final Map<String, Set<String>> SERVICE_INHERITED_ALLOWED = Map.of(
            "EngineerService", Set.of("save", "updateById", "removeById"),
            "EngineerSalesService", Set.of(),
            "OrganizationService", Set.of("save", "removeById"));

    private static final Pattern MAPPER_WRITE_CALL = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*((?:insert|update|delete|save|remove|replace|assign|reassign|bump|clear|close)\\w*)\\s*\\(");
    private static final Pattern QUALIFIED_IMPL_WRITE = Pattern.compile(
            "(?<![\\w.])(this|super|baseMapper|getBaseMapper\\(\\))\\s*\\.\\s*"
                    + "((?:insert|update|delete|save|remove|replace|assign|reassign|bump|clear|close|lambdaUpdate)\\w*)\\s*\\(");
    private static final Pattern UNQUALIFIED_INHERITED_WRITE = Pattern.compile(
            "(?<![\\w.])(save|saveBatch|saveOrUpdate|saveOrUpdateBatch|update|updateById|updateBatchById|remove"
                    + "|removeById|removeByIds|removeByMap|removeBatchByIds|lambdaUpdate)\\s*\\(");
    private static final Pattern SERVICE_INHERITED_WRITE = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*(save|saveBatch|saveOrUpdate|saveOrUpdateBatch|update|updateById|updateBatchById"
                    + "|remove|removeById|removeByIds|removeByMap|removeBatchByIds|lambdaUpdate)\\s*\\(");
    private static final Pattern WRITE_STATEMENT = Pattern.compile(
            "\\b(INSERT\\s+(?:IGNORE\\s+)?INTO|REPLACE\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+`?(\\w+)`?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ON_DUPLICATE = Pattern.compile("ON\\s+DUPLICATE\\s+KEY\\s+UPDATE", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TYPE_KEYWORDS = Set.of("boolean", "void", "int", "long");

    @Test
    void scope表のmapper書込み呼出しは許可クラスの契約どおり() {
        Map<String, Integer> seen = new TreeMap<>();
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            String relative = entry.getKey();
            String source = entry.getValue();
            for (String mapperType : SCOPE_MAPPERS) {
                List<String> variables = WriterGuardSources.variablesOfType(source, mapperType);
                Matcher call = MAPPER_WRITE_CALL.matcher(source);
                while (call.find()) {
                    if (variables.contains(call.group(1))) {
                        seen.merge(relative + " / " + mapperType + "#" + call.group(2), 1, Integer::sum);
                    }
                }
            }
        }
        assertEquals(MAPPER_WRITE_CONTRACT, seen,
                "scope表のmapper書込みは接続済み・理由付き許可・W3-16a後半委任の契約どおりだけ（計画 §4.5 U4b・§4.6）");
    }

    @Test
    void scope表のmapperを基底に持つServiceImplの内部書込みは契約どおり() {
        List<String> violations = new ArrayList<>();
        IMPL_WRITE_CONTRACT.forEach((relative, expected) -> {
            String source = WriterGuardSources.mainSource(relative);
            Map<String, Integer> writes = new TreeMap<>();
            Matcher qualified = QUALIFIED_IMPL_WRITE.matcher(source);
            while (qualified.find()) {
                writes.merge(qualified.group(1) + "." + qualified.group(2), 1, Integer::sum);
            }
            Matcher inherited = UNQUALIFIED_INHERITED_WRITE.matcher(source);
            while (inherited.find()) {
                if (!isDeclaration(source, inherited.start(1))) {
                    writes.merge(inherited.group(1), 1, Integer::sum);
                }
            }
            if (!new TreeMap<>(expected).equals(writes)) {
                violations.add(relative + ": 期待 " + new TreeMap<>(expected) + " 実際 " + writes);
            }
        });
        // 割当行は列限定の wrapper 更新だけで書く（entityの全体更新で他列を古い値へ戻さない）。
        String sales = WriterGuardSources.mainSource(ENGINEER_SALES_SERVICE_IMPL);
        Matcher update = Pattern.compile("(?<![\\w.])update\\s*\\(").matcher(sales);
        int wrapperUpdates = 0;
        while (update.find()) {
            String arguments = WriterGuardSources.callArguments(sales, update.end());
            if (!arguments.stripLeading().startsWith("new LambdaUpdateWrapper<EngineerSales>()")
                    || !arguments.contains(".set(")) {
                violations.add(ENGINEER_SALES_SERVICE_IMPL + ": 列限定でない割当行の更新 "
                        + WriterGuardSources.abbreviate(arguments));
            }
            wrapperUpdates++;
        }
        assertEquals(List.of(), violations, "scope表のServiceImplの書込み（計画 §4.5 U4b）");
        assertEquals(3, wrapperUpdates, "割当行の列限定更新（主担当フラグ・解除・一括解除）を読めていること");
    }

    @Test
    void scope表のmapperを基底に持つServiceImplを増やさない() {
        List<String> violations = new ArrayList<>();
        Pattern serviceImpl = Pattern.compile("extends\\s+(?:[\\w.]+\\.)?ServiceImpl\\s*<\\s*(?:[\\w.]+\\.)?(\\w+Mapper)\\s*,");
        int detected = 0;
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            Matcher matcher = serviceImpl.matcher(entry.getValue());
            while (matcher.find()) {
                String mapper = matcher.group(1);
                if (!SCOPE_MAPPERS.contains(mapper)) {
                    continue;
                }
                detected++;
                if (!entry.getKey().equals(GUARDED_SERVICE_IMPLS.get(mapper))) {
                    violations.add(entry.getKey() + ": " + mapper + " を基底に持つServiceImpl（継承書込みの入口が増える）");
                }
            }
        }
        assertEquals(List.of(), violations, "scope表の継承書込みの入口は既存のServiceImplだけ");
        assertEquals(GUARDED_SERVICE_IMPLS.size(), detected, "既存のServiceImpl 3件を検出できていること");
    }

    @Test
    void 要員_営業_組織serviceの継承書込みはfence済みのoverrideだけを呼ぶ() {
        List<String> violations = new ArrayList<>();
        Map<String, String> entityOf = Map.of("EngineerService", "Engineer", "EngineerSalesService", "EngineerSales",
                "OrganizationService", "OrganizationUnit");
        int detectedCalls = 0;
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            String relative = entry.getKey();
            String source = entry.getValue();
            for (Map.Entry<String, Set<String>> service : SERVICE_INHERITED_ALLOWED.entrySet()) {
                String type = service.getKey();
                Set<String> variables = new TreeSet<>(WriterGuardSources.variablesOfType(source, type));
                variables.addAll(WriterGuardSources.variablesOfType(source, type + "Impl"));
                Matcher inherited = Pattern.compile("IService\\s*<\\s*(?:[\\w.]+\\.)?" + entityOf.get(type)
                        + "\\s*>\\s+(\\w+)\\s*(?=[;,)=])").matcher(source);
                while (inherited.find()) {
                    variables.add(inherited.group(1));
                }
                for (String variable : variables) {
                    Matcher call = SERVICE_INHERITED_WRITE.matcher(source);
                    while (call.find()) {
                        if (!call.group(1).equals(variable)) {
                            continue;
                        }
                        detectedCalls++;
                        if (!service.getValue().contains(call.group(2))) {
                            violations.add(relative + ": " + type + "の継承書込み " + call.group(2)
                                    + "（fenceを通らない。専用メソッドを設けること）");
                        }
                    }
                }
                Matcher provider = Pattern.compile("ObjectProvider\\s*<\\s*(?:[\\w.]+\\.)?" + type
                        + "(?:Impl)?\\s*>\\s+(\\w+)\\s*(?=[;,)=])").matcher(source);
                while (provider.find()) {
                    Matcher call = Pattern.compile("\\b" + Pattern.quote(provider.group(1))
                            + "\\s*\\.\\s*get(?:Object|IfAvailable|IfUnique)\\s*\\(\\s*\\)\\s*\\.\\s*"
                            + "(save\\w*|update\\w*|remove\\w*|lambdaUpdate)\\s*\\(").matcher(source);
                    while (call.find()) {
                        violations.add(relative + ": ObjectProvider経由の" + type + "継承書込み " + call.group(1));
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "要員・営業・組織serviceの継承書込み（計画 §4.5 U4b）");
        // EngineerApiController・CSV取込・BP空き取込・履歴書取込の save、一括操作の updateById、API の removeById、
        // 組織APIの save・removeById を検出できていること。
        assertTrue(detectedCalls >= 7, "service経由の継承書込みの呼出しを検出できていること: " + detectedCalls);
    }

    @Test
    void scope表を書く注釈SQLは既知のメソッドだけでanchor表をREPLACE_物理削除_ON_DUPLICATE_KEYで書かない() throws Exception {
        List<String> violations = new ArrayList<>();
        int detected = 0;
        for (Class<?> mapper : mapperInterfaces()) {
            for (Method method : mapper.getDeclaredMethods()) {
                String id = mapper.getSimpleName() + "#" + method.getName();
                for (String sql : writeSql(method)) {
                    Set<String> targets = writeTargets(sql);
                    boolean scope = targets.stream().anyMatch(SCOPE_TABLES::contains);
                    if (scope) {
                        detected++;
                        if (!SCOPE_SQL_WRITERS.contains(id)) {
                            violations.add(id + ": scope表 " + targets + " を書く注釈SQL（fence経由の書込みへ集めること）");
                        }
                    }
                    violations.addAll(anchorTableViolations(id, sql));
                }
            }
        }
        assertEquals(List.of(), violations, "scope表の注釈SQL（計画 §4.5 U4b）");
        assertEquals(SCOPE_SQL_WRITERS.size(), detected, "scope表を書く既知の注釈SQLを読めていること");
    }

    @Test
    void mapper以外の文字列SQLでscope表を書かない() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            if (entry.getKey().startsWith(MAPPER_PACKAGE)) {
                continue; // mapperの注釈SQLはメソッド単位で別testが確かめる
            }
            for (String literal : AuthorizationEpochColumnIsolationTest.stringLiterals(entry.getValue())) {
                for (String table : writeTargets(literal)) {
                    if (SCOPE_TABLES.contains(table)) {
                        violations.add(entry.getKey() + ": scope表 " + table + " を書く文字列SQL "
                                + WriterGuardSources.abbreviate(literal));
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "scope表の書込みは mapper の既知のメソッドだけ（計画 §4.6）");
    }

    @Test
    void 接続済みwriterはlock順どおりにanchorを取りengineer_epochを進める() {
        List<String> violations = new ArrayList<>();
        String sales = WriterGuardSources.mainSource(ENGINEER_SALES_SERVICE_IMPL);
        // 3 S{営業ユーザー} → 7 X{要員} → 10（insert）→ 増分
        violations.addAll(order(ENGINEER_SALES_SERVICE_IMPL, "assign", sales, "public void assign(",
                "actorShareLock.lockActive(", "lockEngineer(engineerId)", "save(entity)", "authorizationEpochFence.advance("));
        violations.addAll(order(ENGINEER_SALES_SERVICE_IMPL, "setPrimary", sales, "public void setPrimary(",
                "lockEngineer(engineerId)", "getActiveAssignment(", "updatePrimaryFlag(", "authorizationEpochFence.advance("));
        violations.addAll(order(ENGINEER_SALES_SERVICE_IMPL, "release", sales, "public void release(",
                "lockEngineer(engineerId)", "getActiveAssignment(", "update(", "authorizationEpochFence.advance("));
        violations.addAll(order(ENGINEER_SALES_SERVICE_IMPL, "releaseAllByEngineerId", sales,
                "public void releaseAllByEngineerId(Long engineerId, LocalDate releasedAt)",
                "engineerMapper.selectScopeAnchorForUpdate(", "update(", "authorizationEpochFence.advance("));

        String link = WriterGuardSources.mainSource(ENGINEER_ACCOUNT_LINK_SERVICE_IMPL);
        // 3 S{連携ユーザー} → 7 X{要員} → 9 → 増分
        violations.addAll(order(ENGINEER_ACCOUNT_LINK_SERVICE_IMPL, "link", link, "public EngineerAccountLink link(",
                "actorShareLock.lockActive(", "engineerMapper.selectByIdForUpdateForTenant(", "linkMapper.insert(",
                "authorizationEpochFence.advance("));
        violations.addAll(order(ENGINEER_ACCOUNT_LINK_SERVICE_IMPL, "unlinkByEngineerId", link,
                "public void unlinkByEngineerId(", "actorShareLock.lockActive(",
                "engineerMapper.selectScopeAnchorForUpdate(", "linkMapper.deleteByIdForTenant(",
                "authorizationEpochFence.advance("));

        String engineer = WriterGuardSources.mainSource(ENGINEER_SERVICE_IMPL);
        // 6 S{新しい所属組織。外部キー検査の参照先}（W3-16b、W3-16a後半 review P3-1）→ 7 X{要員}、旧値はlock後の行で比べる
        // → 更新 → 11 → 増分
        violations.addAll(order(ENGINEER_SERVICE_IMPL, "updateWithStatusGuard", engineer,
                "public boolean updateWithStatusGuard(", "organizationShareLock.lockReferenced(engineer.getOrganizationId())",
                "baseMapper.selectByIdForUpdateForTenant(",
                "baseMapper.updateByIdForTenant(", "recordAccountingHistory(", "authorizationEpochFence.advance("));
        violations.addAll(order(ENGINEER_SERVICE_IMPL, "updateById", engineer, "public boolean updateById(",
                "organizationShareLock.lockReferenced(entity.getOrganizationId())",
                "baseMapper.selectByIdForUpdateForTenant(", "super.updateById(", "recordAccountingHistory(",
                "authorizationEpochFence.advance("));
        violations.addAll(order(ENGINEER_SERVICE_IMPL, "save", engineer, "public boolean save(",
                "super.save(", "baseMapper.selectScopeAnchorForUpdate(", "recordAccountingHistory("));
        // discovery → 3 X{連携ユーザー} → 7 X{要員} → 削除 → 増分 → 9 → 10（子行もrank順。§3.5 規則1）
        // → 連携ユーザーのstatusだけ（F-5）
        violations.addAll(order(ENGINEER_SERVICE_IMPL, "removeById", engineer,
                "public boolean removeById(Serializable id, Integer expectedVersion)",
                "engineerAccountLinkService.findByEngineerId(", "sysUserMapper.selectByIdAndTenantForUpdate(",
                "baseMapper.selectByIdForUpdateForTenant(", "baseMapper.deleteByIdForTenant(",
                "authorizationEpochFence.advance(", "engineerAccountLinkService.unlinkByEngineerId(",
                "engineerSalesService.releaseAllByEngineerId(", "disableLinkedUser("));
        violations.addAll(order(ENGINEER_SERVICE_IMPL, "disableLinkedUser", engineer, "private void disableLinkedUser(",
                "sysUserMapper.update(null, new UpdateWrapper<SysUser>()", ".set(\"status\", 0)",
                "authorizationEpochFence.advanceIfChanged("));

        String repair = WriterGuardSources.mainSource(OWNERSHIP_REPAIR_SERVICE_IMPL);
        violations.addAll(order(OWNERSHIP_REPAIR_SERVICE_IMPL, "resolve(ENGINEER)", repair, "\"ENGINEER\".equals(",
                "engineerMapper.selectScopeAnchorForUpdate(", "engineerMapper.assignTenantForRepair(", "advanceEngineer("));
        violations.addAll(order(OWNERSHIP_REPAIR_SERVICE_IMPL, "resolve(ENGINEER_ACCOUNT_LINK)", repair,
                "\"ENGINEER_ACCOUNT_LINK\".equals(", "engineerMapper.selectScopeAnchorForUpdate(",
                "engineerAccountLinkMapper.assignTenantForRepair(", "advanceEngineer("));
        assertEquals(List.of(), violations, "接続済みwriterのlock順と増分（計画 §3.4・§4.2）");
    }

    @Test
    void 組織_所属writerはlock順どおりにanchorを取り組織部分木_actor_engineer_epochを進める() {
        List<String> violations = new ArrayList<>();
        String organization = WriterGuardSources.mainSource(ORGANIZATION_SERVICE_IMPL);
        // 組織階層writer: discovery{祖先鎖} → 6 X（ID昇順）→ 検査・書込み → 葉（親子履歴）→ 組織部分木epoch
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "save", organization,
                "public boolean save(OrganizationUnit entity)", "lockOrganizations(", "validateOrganization(",
                "super.save(", "recordRelation(", "advanceOrganizations("));
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "updateOrganization", organization,
                "public boolean updateOrganization(", "lockOrganizations(", "validateOrganization(",
                "baseMapper.updateById(entity)", "recordRelation(", "advanceOrganizations("));
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "updateStatus", organization,
                "public boolean updateStatus(", "lockOrganizations(", "baseMapper.updateById(patch)",
                "recordRelation(", "advanceOrganizations("));
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "removeById", organization,
                "public boolean removeById(Serializable id)", "lockOrganizations(", "isReferenced(",
                "super.removeById(", "advanceOrganizations("));
        // 統合: discovery → 3（影響ユーザーX・上長S）→ 6 → 7 → 読み直し → 8 → 書込み（7・8・11・葉）→ 増分
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "merge", organization, "public boolean merge(",
                "discoverMergeScope(", "lockUsers(", "lockOrganizations(", "lockEngineers(", "discoverMergeScope(",
                "userOrganizationMapper.selectByUserForUpdate(", "baseMapper.updateById(child)",
                ".selectByUserOrganizationForUpdate(", "engineerMapper.reassignOrganization(",
                "engineerAccountingHistoryMapper.updateById(", "baseMapper.updateById(current)",
                "authorizationEpochFence.advanceAll("));
        // 所属writer: 3 X{本人}・S{上長} →（6 X{対象組織}）→ 7 X{連携要員} → 8 X{本人の所属行} → 書込み → 増分
        // （組織行を所属行より先に取る。§11 F-4）
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "assignUser", organization,
                "public UserOrganization assignUser(", "lockSubject(", "linkedEngineerId(", "lockOrganizations(",
                "lockEngineer(", "userOrganizationMapper.selectByUserForUpdate(", "validateAssignment(",
                "userOrganizationMapper.insert(", "advanceAssignmentChange("));
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "updateUserOrganization", organization,
                "public boolean updateUserOrganization(", "userOrganizationMapper.selectById(", "lockSubject(",
                "lockOrganizations(", "lockEngineer(", "lockedAssignment(", "userOrganizationMapper.updateById(",
                "advanceAssignmentChange("));
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "transferUser", organization,
                "public UserOrganization transferUser(", "lockSubject(", "lockOrganizations(", "lockEngineer(",
                "userOrganizationMapper.selectByUserForUpdate(", "validateAssignmentBasics(",
                "userOrganizationMapper.insert(", "advanceAssignmentChange("));
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "releaseAssignment", organization,
                "public boolean releaseAssignment(", "userOrganizationMapper.selectById(", "lockSubject(",
                "lockEngineer(", "lockedAssignment(", "userOrganizationMapper.updateById(patch)",
                "advanceAssignmentChange("));
        // 一括終了: 3 X{本人} → 7 X{連携要員} → 8 X → 上長解除 → 増分は自分で行う（W3-15 review P3-6）
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "closeAssignmentsForUser", organization,
                "public int closeAssignmentsForUser(", "lockSubject(", "lockEngineer(",
                "userOrganizationMapper.selectByUserForUpdate(", "userOrganizationMapper.updateById(patch)",
                "userOrganizationMapper.clearManager(", "advanceAssignmentChange(",
                "authorizationEpochFence.advance(ActorEpochAnchors.of(user))"));
        // 補助: 祖先鎖はdiscoveryの後にID昇順（TreeSet）でlockし、lock後に読み直して不一致なら409。
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "lockOrganizations", organization,
                "private Map<Long, OrganizationUnit> lockOrganizations(", "new TreeSet<>()", "organizationChain(start)",
                "baseMapper.selectByIdForUpdate(id)", "organizationChain(start)", "error.organization.versionConflict"));
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "lockUsers", organization,
                "private Map<Long, SysUser> lockUsers(", "new TreeSet<>(exclusive)", "actorShareLock.lockActive(",
                "sysUserMapper.selectByIdForUpdateIncludingDeleted("));
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "lockEngineer", organization,
                "private Engineer lockEngineer(", "engineerMapper.selectScopeAnchorForUpdate("));
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "advanceOrganizations", organization,
                "private void advanceOrganizations(", "authorizationEpochFence.advanceAll(",
                "AuthorizationEpochAnchor::organization"));
        violations.addAll(order(ORGANIZATION_SERVICE_IMPL, "advanceAssignmentChange", organization,
                "private void advanceAssignmentChange(", "ActorEpochAnchors.of(lockedUser)",
                "EngineerEpochAnchors.of(lockedLinkedEngineer)", "authorizationEpochFence.advanceAll("));
        assertEquals(List.of(), violations, "組織・所属writerのlock順と増分（計画 §3.4・§4.2、§11 F-4）");
    }

    @Test
    void 退社完了は連携ユーザーを要員より先に1つのplanで取りlock後に再discoveryしてから書く() {
        // W3-16b（計画 §3.4 completeCase 列、§3.5 規則1・5）: discovery → plan（3 S{操作者}・X{連携ユーザー} → 4 → 5 → 7 →
        // 8 → 9 → 10 → 14）の1回の取得 → 再discoveryと比較 → 取得済みの行（3・8・9・10 → 14 → 7 → 4）→ 葉（session）。
        // 旧実装の「案件X → 要員X → 自動処理で連携ユーザーX」（4 → 7 → 3）を戻さない。
        List<String> violations = new ArrayList<>();
        String relative = "com/ses/service/lifecycle/impl/LifecycleCaseServiceImpl.java";
        String lifecycle = WriterGuardSources.mainSource(relative);
        violations.addAll(order(relative, "completeCase", lifecycle, "public void completeCase(",
                "caseMapper.selectByIdAndTenant(caseId, tenantId)", "assertCompletable(", "completionLockPlanner.plan(",
                "acquireCompletionLocks(plan, caseId)", "caseMapper.selectByIdAndTenant(caseId, tenantId)",
                "requireSameCompletionDependencies(plan, completionLockPlanner.plan(", "assertCompletable(",
                "resignationGateChecker.applyAutomaticScopeActions(", "revocationOutbox.enqueueForResignation(",
                "engineerMapper.updateById(engineer)", "caseMapper.updateById(lcCase)", "eventMapper.insert(",
                "resignationGateChecker.revokeSessionsAfterScopeActions("));
        violations.addAll(order(relative, "acquireCompletionLocks", lifecycle, "private void acquireCompletionLocks(",
                "lockExecutor.acquire(plan)", "error.common.optimisticLock"));
        String body = methodBody(lifecycle, "public void completeCase(");
        for (String forbidden : List.of("selectByIdForUpdateAndTenant(", "selectByIdForUpdateForTenant(",
                "executeAutomaticGateActions(")) {
            if (body.contains(forbidden)) {
                violations.add(relative + "#completeCase: plan外のlocking read・合成の自動処理 " + forbidden
                        + " を使わない（lockは1つのplanで1回だけ取る）");
            }
        }
        // 合成の自動処理（planを通らない）は業務経路から呼ばない（review P3-7。package-privateで、試験だけが使う）。
        String gateRelative = "com/ses/service/lifecycle/ResignationGateChecker.java";
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            if (!entry.getKey().equals(gateRelative) && entry.getValue().contains("executeAutomaticGateActions(")) {
                violations.add(entry.getKey() + ": 退社ゲートの合成の自動処理を業務経路から呼ばない"
                        + "（案件完了のplanの取得後に applyAutomaticScopeActions を呼ぶ）");
            }
        }
        if (WriterGuardSources.mainSource(gateRelative).contains("public void executeAutomaticGateActions(")) {
            violations.add(gateRelative + ": executeAutomaticGateActions は package-private にする");
        }
        // planの組立て: 3（操作者S・連携ユーザーX）→ 4 → 5 → 7 → 8（本人の所属・上長解除の対象）→ 9 → 10 → 14。
        String plannerRelative = "com/ses/service/lifecycle/LifecycleCompletionLockPlanner.java";
        String planner = WriterGuardSources.mainSource(plannerRelative);
        violations.addAll(order(plannerRelative, "plan", planner, "public LifecycleLockPlan plan(",
                "LockKey.shared(Rank.SYS_USER, tenantId, operatorId)", "Rank.LIFECYCLE_CASE", "Rank.LIFECYCLE_TASK",
                "addResignationTargets("));
        violations.addAll(order(plannerRelative, "addResignationTargets", planner,
                "private void addResignationTargets(", "Rank.ENGINEER", "selectByEngineerIdAndTenant(",
                "error.lifecycle.selfResignationCompletion", "LockKey.exclusive(Rank.SYS_USER, tenantId, linkedUserId)",
                "UserOrganization::getUserId", "UserOrganization::getManagerUserId", "Rank.ENGINEER_ACCOUNT_LINK",
                "Rank.ENGINEER_SALES", "selectUnrevokedForEngineer(", "Rank.EXTERNAL_ACCOUNT_REFERENCE"));
        assertEquals(List.of(), violations, "退社完了のlock順（計画 §3.4・§4.2、G4r review P3-1、W3-16a review P3-1・P2-1）");
    }

    @Test
    void lock後の読み直しがMyBatisのsession_cacheで古い結果にならないようanchorのlock文はcacheを消す() {
        // W3-16b M6（実MySQL）で判明: 同じtransactionでlock前に実行した照会をlock後に繰り返すと、MyBatisのsession cacheが
        // lock前の結果を返し、退社完了の再discovery・紐付け解除・要員削除の読み直しが待っていた相手のcommitを見落とした。
        // anchorのlock文（とexecutorの全lock文）は実行前にsession cacheを消すこと。
        List<String> violations = new ArrayList<>();
        int executorStatements = 0;
        for (Method method : com.ses.mapper.LifecycleLockMapper.class.getDeclaredMethods()) {
            if (method.isSynthetic() || method.getAnnotation(org.apache.ibatis.annotations.Select.class) == null) {
                continue;
            }
            executorStatements++;
            if (!flushesCache(method)) {
                violations.add("LifecycleLockMapper#" + method.getName());
            }
        }
        for (String name : List.of("selectScopeAnchorForUpdate", "selectByIdForUpdateForTenant")) {
            for (Method method : com.ses.mapper.EngineerMapper.class.getDeclaredMethods()) {
                if (method.getName().equals(name) && !flushesCache(method)) {
                    violations.add("EngineerMapper#" + name);
                }
            }
        }
        assertEquals(List.of(), violations, "lock文は @Options(flushCache = TRUE) を持つ");
        assertTrue(executorStatements >= 20, "executorのlock文を読めていること: " + executorStatements);
    }

    private static boolean flushesCache(Method method) {
        org.apache.ibatis.annotations.Options options = method.getAnnotation(org.apache.ibatis.annotations.Options.class);
        return options != null && options.flushCache() == org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE;
    }

    @Test
    void 要員行をX_lockする業務writerは外部キー参照先の利用者行と組織行を先に共有lockする() {
        // W3-16a後半 review P3-1: 要員行（rank 7）のX lockの後にinsert・更新の外部キー検査が組織行（rank 6）へS lockを付けると、
        // 組織統合・所属writer（6 X → 7 X）と循環し得る。参照先の組織行を要員行より先に共有lockする。
        // W3-16b review P3-1: 休暇申請の created_by・applicant_id は申請者本人の利用者行（rank 3）を参照する。後から付く
        // S lockは退社完了（3 X{連携ユーザー} → 7 X）と循環し得るため、本人の利用者行を最初に共有lockする（3 → 6 → 7）。
        List<String> violations = new ArrayList<>();
        String relative = "com/ses/service/impl/LeaveServiceImpl.java";
        String leave = WriterGuardSources.mainSource(relative);
        violations.addAll(order(relative, "apply", leave, "public LeaveApplicationResult apply(",
                "actorShareLock.lockActive(applicantUserId, null)",
                "organizationShareLock.lockReferenced(leave.getOrganizationId())", "assertNoOverlap(engineerId, leave)",
                "leaveRequestMapper.insert(leave)", "approvalEngineService.request("));
        violations.addAll(order(relative, "assertNoOverlap", leave, "private void assertNoOverlap(",
                "engineerMapper.selectByIdForUpdate(engineerId)"));
        String shareLock = WriterGuardSources.mainSource("com/ses/service/security/epoch/OrganizationShareLock.java");
        violations.addAll(order("OrganizationShareLock", "lockReferenced", shareLock, "public void lockReferenced(",
                "sharedLockSupport.supported()"));
        assertEquals(List.of(), violations, "要員writerの組織行の先行lock（計画 §3.5 規則1）");
    }

    @Test
    void 組織serviceは公開のupdateById_overrideを持たない() {
        // 計画 §11 F-8: transaction・親子履歴・epochの増分なしに parent_id・status を書ける公開入口を残さない。
        // 継承した updateById の呼出しは「継承書込みはfence済みのoverrideだけを呼ぶ」testが検出する。
        List<String> declared = new ArrayList<>();
        for (Method method : com.ses.service.impl.OrganizationServiceImpl.class.getDeclaredMethods()) {
            if (method.getName().equals("updateById") && !method.isSynthetic()) {
                declared.add(method.toGenericString());
            }
        }
        assertEquals(List.of(), declared, "OrganizationServiceImpl.updateById の override は削除する（F-8）");
        assertTrue(!SERVICE_INHERITED_ALLOWED.get("OrganizationService").contains("updateById"),
                "組織serviceの継承 updateById は呼出しを許さない");
    }

    @Test
    void 契約のwriterはengineer_epochを進めない() {
        // 計画 §4.4: ENGINEER対象のscope述語は契約を読まないため、契約の sales_user_id 変更等でengineer epochを
        // 進めない（進めると無関係な提出まで失効する）。M4の「契約の sales_user_id NULL→B」の静的な代替。
        for (String relative : List.of("com/ses/service/impl/ContractServiceImpl.java",
                "com/ses/service/impl/ContractRenewalServiceImpl.java")) {
            String source = WriterGuardSources.mainSource(relative);
            assertTrue(!source.contains("AuthorizationEpochFence") && !source.contains("AuthorizationEpochMapper")
                            && !source.contains("selectScopeAnchorForUpdate"),
                    relative + " は要員のepoch anchorに触れない");
        }
    }

    // ===== helper =====

    private static Map<String, Integer> contract(Object... entries) {
        Map<String, Integer> result = new TreeMap<>();
        for (int i = 0; i < entries.length; i += 3) {
            result.put(entries[i] + " / " + entries[i + 1], (Integer) entries[i + 2]);
        }
        return result;
    }

    /** 直前の語が型（{@code boolean}・{@code void} 等、または大文字で始まる型名）ならメソッド宣言とみなす。 */
    private static boolean isDeclaration(String source, int nameStart) {
        int end = nameStart;
        while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(source.charAt(start - 1)) || source.charAt(start - 1) == '_'
                || source.charAt(start - 1) == '>' || source.charAt(start - 1) == '<')) {
            start--;
        }
        String previous = source.substring(start, end);
        return TYPE_KEYWORDS.contains(previous)
                || (!previous.isEmpty() && Character.isUpperCase(previous.charAt(0)));
    }

    /** {@code marker} から始まるメソッド本体（対応する波括弧まで）。見つからなければ空文字。 */
    private static String methodBody(String source, String marker) {
        int at = source.indexOf(marker);
        int open = at < 0 ? -1 : source.indexOf('{', at);
        return open < 0 ? "" : WriterGuardSources.callArguments(source, open + 1);
    }

    /**
     * {@code marker} から始まるメソッド本体（対応する波括弧まで）の中に、{@code tokens} がこの順で現れることを確かめる。
     */
    private static List<String> order(String relative, String label, String source, String marker, String... tokens) {
        int at = source.indexOf(marker);
        if (at < 0) {
            return List.of(relative + "#" + label + ": " + marker + " が見つかりません");
        }
        int open = source.indexOf('{', at);
        String body = open < 0 ? "" : WriterGuardSources.callArguments(source, open + 1);
        List<String> problems = new ArrayList<>();
        int cursor = 0;
        for (String token : tokens) {
            int found = body.indexOf(token, cursor);
            if (found < 0) {
                problems.add(relative + "#" + label + ": " + token + " が順序どおりに見つかりません（期待順 "
                        + String.join(" → ", tokens) + "）");
                break;
            }
            cursor = found + token.length();
        }
        return problems;
    }

    private static List<String> anchorTableViolations(String location, String sql) {
        List<String> violations = new ArrayList<>();
        Matcher statement = WRITE_STATEMENT.matcher(sql);
        while (statement.find()) {
            String verb = statement.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            String table = statement.group(2).toLowerCase(Locale.ROOT);
            if (!ANCHOR_TABLES.contains(table)) {
                continue;
            }
            if (verb.startsWith("REPLACE") || verb.startsWith("DELETE")) {
                violations.add(location + ": anchor表 " + table + " への " + verb + "（epochがDEFAULT 0へ戻る。E-5）");
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
