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
 *
 * <p>migration の中の全ての {@code 'FILE_OBJECT'}（注釈を除く）を既知の形（値の並び・{@code CONCAT('DOCUMENT:', …)}・比較・
 * {@code storage_kind IN (…)} の一覧）の位置と突き合わせ、どれにも当たらないものがあれば落とす（{@code UPDATE … SET storage_kind =
 * 'FILE_OBJECT'} や列の並びが隣り合わない {@code INSERT} で足した element を、H2 の replay に無い時に見落とさない。A2 の復審 P3-7）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HoldSubjectResolverFileObjectCoverageTest {

    private static final String TENANT = "default";
    /** {@code '<data_element_id>', 'FILE_OBJECT', '<storage_ref>'}（{@code INSERT … SELECT} の値の並び）。 */
    private static final Pattern LITERAL_ROW = Pattern.compile(
            "'([A-Za-z0-9_:]+)'\\s*,\\s*('FILE_OBJECT')\\s*,\\s*'([^']*)'");
    /** {@code CONCAT('<接頭辞>', …), 'FILE_OBJECT', '<storage_ref>'}（文書種別ごとの行）。 */
    private static final Pattern CONCAT_ROW = Pattern.compile(
            "CONCAT\\(\\s*'([^']*)'[^)]*\\)\\s*,\\s*('FILE_OBJECT')\\s*,\\s*'([^']*)'");
    /**
     * 行を足さない比較の位置の {@code 'FILE_OBJECT'}（{@code WHERE|AND|OR|ON|WHEN [別名.]storage_kind =|<>|!= 'FILE_OBJECT'}）。
     * {@code SET storage_kind = 'FILE_OBJECT'} は比較ではない（element を足し得る）ので含めない。
     */
    private static final Pattern COMPARISON = Pattern.compile(
            "(?i)\\b(?:WHERE|AND|OR|ON|WHEN)\\s+(?:\\w+\\.)?storage_kind\\s*(?:=|<>|!=)\\s*('FILE_OBJECT')");
    /** {@code storage_kind [NOT] IN (…)} の一覧（V190 の CHECK 制約・照会の条件）。一覧の中の {@code 'FILE_OBJECT'} は行を足さない。 */
    private static final Pattern IN_LIST = Pattern.compile("(?i)\\bstorage_kind\\s+(?:NOT\\s+)?IN\\s*\\(([^)]*)\\)");
    /** migration の中の全ての {@code 'FILE_OBJECT'}（上のどの形にも当たらないものがあれば落とす）。 */
    private static final Pattern ANY = Pattern.compile("'FILE_OBJECT'");
    /** 注釈（行末までの注釈とブロック注釈）。注釈の中の語は数えない。 */
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|--[^\\n]*");

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
            scan(script.getFilename(), script.getContentAsString(StandardCharsets.UTF_8), migrationElements, unknownForms);
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

    /** A2 の復審 P3-7: 走査が UPDATE … SET の形と列の並びが隣り合わない形を落とし、比較と CHECK の一覧は落とさないことの自己確認。 */
    @Test
    void 走査は既知の形でないFILE_OBJECTを落とし比較と一覧は落とさない() {
        Set<String> elements = new TreeSet<>();
        List<String> unknown = new ArrayList<>();
        scan("set.sql", "UPDATE m_pii_data_element SET storage_kind = 'FILE_OBJECT', storage_ref = 't_x.stored_name' "
                + "WHERE data_element_id = 'X';", elements, unknown);
        scan("columns.sql", "INSERT INTO m_pii_data_element (storage_kind, data_element_id, storage_ref) VALUES "
                + "('FILE_OBJECT', 'Y', 't_y.stored_name');", elements, unknown);
        assertThat(unknown).hasSize(2).allMatch(message -> message.contains("既知の形でない"));

        unknown.clear();
        scan("known.sql", "-- storage_kind = 'FILE_OBJECT' の注釈\n"
                + "CHECK (storage_kind IN ('DB_COLUMN', 'FILE_OBJECT', 'AI_PAYLOAD'))\n"
                + "SELECT 'Z', 'FILE_OBJECT', 't_z.stored_name' UNION ALL SELECT CONCAT('DOCUMENT:', dt.code), "
                + "'FILE_OBJECT', 't_document_version.storage_key'\n"
                + "UPDATE m_pii_data_element e SET e.purge_rule = 'X' WHERE e.storage_kind = 'FILE_OBJECT' "
                + "AND storage_kind <> 'FILE_OBJECT';", elements, unknown);
        assertThat(unknown).isEmpty();
        assertThat(elements).containsExactly("Z");
    }

    /**
     * 1つの migration を走査し、application の表を保存先に持つ FILE_OBJECT の element を集める。既知の形（値の並び・文書種別ごとの
     * {@code CONCAT('DOCUMENT:', …)}・比較・{@code storage_kind IN (…)} の一覧）のどれにも当たらない {@code 'FILE_OBJECT'} があれば
     * {@code unknownForms} に足す（全ての出現を既知の形の位置と突き合わせる。A2 の復審 P3-7）。
     */
    static void scan(String filename, String script, Set<String> migrationElements, List<String> unknownForms) {
        String sql = COMMENT.matcher(script).replaceAll(" ");
        Set<Integer> known = new TreeSet<>();
        Matcher literalRows = LITERAL_ROW.matcher(sql);
        while (literalRows.find()) {
            known.add(literalRows.start(2));
            if (literalRows.group(3).trim().startsWith("t_")) {
                migrationElements.add(literalRows.group(1));
            }
        }
        Matcher concatRows = CONCAT_ROW.matcher(sql);
        while (concatRows.find()) {
            known.add(concatRows.start(2));
            if (concatRows.group(3).trim().startsWith("t_") && !"DOCUMENT:".equals(concatRows.group(1))) {
                unknownForms.add(filename + ": CONCAT('" + concatRows.group(1) + "', …)");
            }
        }
        Matcher comparisons = COMPARISON.matcher(sql);
        while (comparisons.find()) {
            known.add(comparisons.start(1));
        }
        Matcher lists = IN_LIST.matcher(sql);
        while (lists.find()) {
            Matcher inList = ANY.matcher(lists.group(1));
            while (inList.find()) {
                known.add(lists.start(1) + inList.start());
            }
        }
        Matcher all = ANY.matcher(sql);
        while (all.find()) {
            if (!known.contains(all.start())) {
                long line = sql.substring(0, all.start()).chars().filter(c -> c == '\n').count() + 1;
                unknownForms.add(filename + ":" + line + "（注釈を除いた行）: 既知の形でない 'FILE_OBJECT'");
            }
        }
    }
}
