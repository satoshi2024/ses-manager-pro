package com.ses.privacy.production.retention.hold;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * review rb-fe-a1 1a4fd48d 差分 P3-N3: catalog（{@code m_pii_data_element}）に入る {@code FILE_OBJECT} の data element のうち、保存先が
 * application の表（{@code storage_ref} が {@code t_} で始まる）のものは、全て {@link HoldSubjectResolver#resolveElementStoredNames} が
 * 保存名を解決できる（解決規則の無い element を migration で足すと、DATA_ELEMENT hold の間の孤児の清掃（D10）が毎回中止し、署名の文書の
 * 消去（D17）も止まる。P2-1 と同じ事故を次の migration で繰り返さない）。
 *
 * <p>migration（classpath の {@code db/migration}）を走査して element を集め、H2 の catalog にも同じ element があることを確かめた上で
 * 解決する（H2 の replay に無い migration の element は、解決を確かめられないため落とす）。{@code CONCAT('DOCUMENT:', …)} で文書種別
 * ごとに作る行は、接頭辞の規則（{@code DOCUMENT:}）で解決する。他の形の動的な行は解決規則を確かめられないため落とす。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HoldSubjectResolverFileObjectCoverageTest {

    private static final String TENANT = "default";
    /** {@code '<data_element_id>', 'FILE_OBJECT', '<storage_ref>'}（{@code INSERT … SELECT} の値の並び）。 */
    private static final Pattern LITERAL_ROW = Pattern.compile(
            "'([A-Za-z0-9_:]+)'\\s*,\\s*'FILE_OBJECT'\\s*,\\s*'([^']*)'");
    /** {@code CONCAT('<接頭辞>', …), 'FILE_OBJECT', '<storage_ref>'}（文書種別ごとの行）。 */
    private static final Pattern CONCAT_ROW = Pattern.compile(
            "CONCAT\\(\\s*'([^']*)'[^)]*\\)\\s*,\\s*'FILE_OBJECT'\\s*,\\s*'([^']*)'");
    /** 値の位置の {@code 'FILE_OBJECT'}（上の2つの形のどちらかでなければ落とす）。 */
    private static final Pattern VALUE_POSITION = Pattern.compile("'FILE_OBJECT'\\s*,\\s*'([^']*)'");

    @Autowired
    private HoldSubjectResolver resolver;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migrationが入れるFILE_OBJECTのapplication表のelementは全て保存名を解決でき清掃を中止させない() throws IOException {
        Set<String> migrationElements = new TreeSet<>();
        List<String> unknownForms = new ArrayList<>();
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql");
        assertThat(scripts).as("migration を classpath から読めない").isNotEmpty();
        for (Resource script : scripts) {
            String sql = script.getContentAsString(StandardCharsets.UTF_8);
            int literal = 0;
            Matcher literalRows = LITERAL_ROW.matcher(sql);
            while (literalRows.find()) {
                literal++;
                if (literalRows.group(2).trim().startsWith("t_")) {
                    migrationElements.add(literalRows.group(1));
                }
            }
            int concat = 0;
            Matcher concatRows = CONCAT_ROW.matcher(sql);
            while (concatRows.find()) {
                concat++;
                if (concatRows.group(2).trim().startsWith("t_") && !"DOCUMENT:".equals(concatRows.group(1))) {
                    unknownForms.add(script.getFilename() + ": CONCAT('" + concatRows.group(1) + "', …)");
                }
            }
            int values = 0;
            Matcher valueRows = VALUE_POSITION.matcher(sql);
            while (valueRows.find()) {
                values++;
            }
            if (values != literal + concat) {
                unknownForms.add(script.getFilename() + ": 値の位置の 'FILE_OBJECT' " + values + " 件のうち "
                        + (values - literal - concat) + " 件が既知の形でない");
            }
        }
        assertThat(unknownForms).as("解決規則を確かめられない形の FILE_OBJECT の行。HoldSubjectResolver の規則と本試験を足す")
                .isEmpty();
        assertThat(migrationElements).as("走査の自己確認（V196 の要員の写真・V203 の署名の文書）")
                .contains("ENGINEER_PHOTO", "FEATURE_SIGN_OFF_DOCUMENT");

        Set<String> catalogElements = new TreeSet<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT data_element_id, storage_ref FROM m_pii_data_element "
                + "WHERE tenant_id = ? AND storage_kind = 'FILE_OBJECT' AND deleted_flag = 0", TENANT)) {
            Object ref = row.get("storage_ref");
            if (ref != null && ref.toString().trim().startsWith("t_")) {
                catalogElements.add(row.get("data_element_id").toString());
            }
        }
        assertThat(catalogElements).as("H2 の catalog に無い migration の element（application-test.yml の replay に足す）")
                .containsAll(migrationElements);

        for (String element : catalogElements) {
            assertThatCode(() -> {
                HoldSubjectResolver.ElementStoredNames names = resolver.resolveElementStoredNames(TENANT, element);
                assertThat(names.outsideUploadRoot()).as("%s は upload root の中の実体", element).isFalse();
            }).as("%s の保存名を解決できない（HoldSubjectResolver.resolveElementStoredNames に規則を足す）", element)
                    .doesNotThrowAnyException();
        }
    }
}
