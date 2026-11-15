package com.ses.service.security.epoch;

import com.ses.service.impl.SystemConfigServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U3（NF01細粒度epoch計画 §4.5・§6.2、W3-15）: 認可キー台帳 {@link AuthorizationConfigKeys} の閉包をDocker無しで固定する。
 *
 * <ul>
 *   <li>認可判定クラスが {@code systemConfigService.get*} と {@code systemConfigMapper.select*} で読むキーは、すべて台帳にある
 *       （台帳に無いキーを判定に使い始めると、その値の変更で全体epochが進まず、旧提出を確認できてしまう）。</li>
 *   <li>{@code SystemConfigServiceImpl} 以外で {@code m_system_config} を直接書くクラス・{@code put} を呼ぶクラスは、
 *       台帳のキーを使わない（全体epochを進めずに認可キーを書く経路を作らない）。</li>
 *   <li>台帳のキーは画面から更新できる既知キー（{@code SystemConfigServiceImpl} のschema）である。</li>
 * </ul>
 *
 * <p>キーを静的に解決できない呼出し（変数・連結式）は許可ではなく違反にする。</p>
 */
class AuthorizationConfigKeyFenceTest {

    /** 認可判定クラス（計画 §4.5 U3）。DocumentServiceImplはACL部（現状は設定を読まない）を含めてファイル単位で見る。 */
    private static final List<String> AUTHORIZATION_DECISION_SOURCES = List.of(
            "com/ses/service/impl/DataScopeServiceImpl.java",
            "com/ses/service/security/impl/OrganizationScopeServiceImpl.java",
            "com/ses/service/lifecycle/LifecycleScopeService.java",
            "com/ses/service/security/impl/AuthorizationServiceImpl.java",
            "com/ses/service/impl/DocumentServiceImpl.java",
            "com/ses/service/security/ExplicitActorAuthorizationEvaluator.java");

    private static final String SYSTEM_CONFIG_SERVICE_IMPL = "com/ses/service/impl/SystemConfigServiceImpl.java";

    private static final Pattern SERVICE_READ = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*get(?:String|Int|Decimal)\\s*\\(\\s*([^,)]+)");
    private static final Pattern MAPPER_READ = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*(?:selectById|selectByIdForUpdate)\\s*\\(\\s*([^,)]+)\\)");
    private static final Pattern MAPPER_WRITE = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*(?:insert|update|updateById|delete|deleteById|deleteBatchIds|deleteByMap)\\s*\\(");
    private static final Pattern SERVICE_PUT = Pattern.compile("\\b(\\w+)\\s*\\.\\s*put\\s*\\(\\s*([^,)]+)\\s*,");

    @Test
    void 認可判定クラスが読む設定キーはすべて台帳にある() {
        List<String> violations = new ArrayList<>();
        int resolvedReads = 0;
        for (String relative : AUTHORIZATION_DECISION_SOURCES) {
            String source = WriterGuardSources.mainSource(relative);
            Map<String, String> constants = WriterGuardSources.stringConstants(source);
            List<String> serviceVariables = WriterGuardSources.variablesOfType(source, "SystemConfigService");
            List<String> mapperVariables = WriterGuardSources.variablesOfType(source, "SystemConfigMapper");
            for (Pattern pattern : List.of(SERVICE_READ, MAPPER_READ)) {
                List<String> variables = pattern == SERVICE_READ ? serviceVariables : mapperVariables;
                Matcher matcher = pattern.matcher(source);
                while (matcher.find()) {
                    if (!variables.contains(matcher.group(1))) {
                        continue;
                    }
                    String key = WriterGuardSources.resolveKey(matcher.group(2), constants);
                    if (key == null) {
                        violations.add(relative + ": 設定キーを静的に解決できません: " + matcher.group(2).trim());
                    } else if (!AuthorizationConfigKeys.contains(key)) {
                        violations.add(relative + ": 台帳に無い設定キーを読んでいます: " + key);
                    } else {
                        resolvedReads++;
                    }
                }
            }
        }
        assertEquals(List.of(), violations,
                "認可判定に使う設定キーは AuthorizationConfigKeys へ載せ、変更時に全体epochを進めること（計画 §4.1）");
        // DataScopeServiceImpl（getString）と ExplicitActorAuthorizationEvaluator（selectById）の2件を読めていること。
        assertTrue(resolvedReads >= 2, "認可判定クラスの設定読取を検出できていること: " + resolvedReads);
    }

    @Test
    void SystemConfigServiceImpl以外の直接書込みとputは台帳のキーを使わない() {
        List<String> violations = new ArrayList<>();
        int directWriters = 0;
        int putCalls = 0;
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            String relative = entry.getKey();
            String source = entry.getValue();
            if (relative.equals(SYSTEM_CONFIG_SERVICE_IMPL)) {
                continue;
            }
            List<String> mapperVariables = WriterGuardSources.variablesOfType(source, "SystemConfigMapper");
            boolean writesDirectly = false;
            Matcher write = MAPPER_WRITE.matcher(source);
            while (write.find()) {
                if (mapperVariables.contains(write.group(1))) {
                    writesDirectly = true;
                }
            }
            if (writesDirectly) {
                directWriters++;
                if (source.contains("AuthorizationConfigKeys")) {
                    violations.add(relative + ": m_system_configを直接書くクラスが認可キー台帳を参照しています");
                }
                for (String literal : AuthorizationEpochColumnIsolationTest.stringLiterals(source)) {
                    for (String key : AuthorizationConfigKeys.KEYS) {
                        if (literal.contains(key)) {
                            violations.add(relative + ": m_system_configを直接書くクラスが認可キーを扱っています: " + key);
                        }
                    }
                }
            }
            List<String> serviceVariables = WriterGuardSources.variablesOfType(source, "SystemConfigService");
            Map<String, String> constants = WriterGuardSources.stringConstants(source);
            Matcher put = SERVICE_PUT.matcher(source);
            while (put.find()) {
                if (!serviceVariables.contains(put.group(1))) {
                    continue;
                }
                putCalls++;
                String key = WriterGuardSources.resolveKey(put.group(2), constants);
                if (key == null) {
                    violations.add(relative + ": putの設定キーを静的に解決できません: " + put.group(2).trim());
                } else if (AuthorizationConfigKeys.contains(key)) {
                    violations.add(relative + ": 認可キーをputで書いています（updateAllだけが書ける）: " + key);
                }
            }
        }
        assertEquals(List.of(), violations, "認可キーを書けるのは SystemConfigServiceImpl.updateAll だけ（計画 §4.1・§4.5）");
        // 勤怠同期・勤怠差異・会計照合・本番bootstrapの直接書込みと、勤怠同期・ポータル規約のputを検出できていること。
        assertTrue(directWriters >= 4, "m_system_configの直接書込みクラスを検出できていること: " + directWriters);
        assertTrue(putCalls >= 2, "SystemConfigService.putの呼出しを検出できていること: " + putCalls);
    }

    @Test
    void 台帳のキーは画面から更新できる既知の設定キーである() throws Exception {
        Field schemas = SystemConfigServiceImpl.class.getDeclaredField("SCHEMAS");
        schemas.setAccessible(true);
        Map<?, ?> known = (Map<?, ?>) schemas.get(null);
        for (String key : AuthorizationConfigKeys.KEYS) {
            assertTrue(known.containsKey(key), "台帳のキーがSystemConfigServiceImplのschemaにありません: " + key);
        }
        assertTrue(AuthorizationConfigKeys.contains(AuthorizationConfigKeys.SALES_OWN_DATA_ONLY));
        assertTrue(!AuthorizationConfigKeys.contains(null) && !AuthorizationConfigKeys.contains("notice.bench-warn-days"),
                "台帳外のキーとnullは認可キーではない");
    }
}
