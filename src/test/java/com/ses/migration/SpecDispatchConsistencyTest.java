package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * spec文書のMigration予約番号が全ての派工資料で一致していることを、DB無し・Docker無しで検証する。
 *
 * <p>{@link MigrationScriptIntegritySpec 相当の} 実スクリプト側の重複は
 * {@link MigrationScriptIntegrityTest} が見ているが、<b>実装AIが従うのは spec文書の方</b>である。
 * V61/V62が実使用された際の繰り上げは `design.md` / README / copyable conversation には
 * 適用されたのに `tasks.md` だけ漏れ、14 spec全てが2つ低い番号（archiveがV62、productivityがV63）を
 * 保持していた。どちらも既に適用済みの番号なので、その指示どおりに実装すると
 * {@code FlywayException: Found more than one migration with version NN} で全環境が起動不能になる。
 *
 * <p>その時のReviewは「同期は完全」と報告していたが、grepの対象集合に `tasks.md` が入っていなかった。
 * 人手のgrepは対象を取りこぼすので、対象集合そのものをテストで固定する。
 *
 * <p>派工資料の正本は日本語版だけである（`spec-start-conversations.md` と
 * `copyable-conversations/*.txt`）。翻訳版を別に持つと同期対象が増えて必ず漂流するため、
 * 翻訳版は保持しない。新しい派工成果物を追加する場合は本テストの検査対象へも追加する。
 */
class SpecDispatchConsistencyTest {

    /** `.kiro/specs` 配下。Maven Surefireの作業ディレクトリはbasedirなので相対で解決できる。 */
    private static final Path SPECS = Path.of(".kiro", "specs");

    private static final Path EXPANSION = SPECS.resolve("customer-product-expansion-2026");

    /** 実装対象の spec（S06〜S17）。S01は採番未定、S02〜S05は適用済みのため対象外。 */
    private static final Map<String, String> SPEC_BY_CONVERSATION = new LinkedHashMap<>();

    static {
        // SPEC_BY_CONVERSATION.put("S05", "productivity-search-saved-view");
        // SPEC_BY_CONVERSATION.put("S06", "bp-company-master-procurement-compliance");
        SPEC_BY_CONVERSATION.put("S07", "approval-workflow-internal-control");
        SPEC_BY_CONVERSATION.put("S08", "crm-contact-opportunity");
        SPEC_BY_CONVERSATION.put("S09", "order-acceptance-workflow");
        SPEC_BY_CONVERSATION.put("S10", "dispatch-outsourcing-compliance-ledger");
        SPEC_BY_CONVERSATION.put("S11", "attendance-leave-overtime-compliance");
        SPEC_BY_CONVERSATION.put("S12", "staffing-capacity-planning");
        SPEC_BY_CONVERSATION.put("S13", "external-customer-bp-portal");
        SPEC_BY_CONVERSATION.put("S14", "engineer-self-service-portal-v2");
        SPEC_BY_CONVERSATION.put("S15", "accounting-payment-integration");
        SPEC_BY_CONVERSATION.put("S16", "jp-pint-digital-invoice");
        SPEC_BY_CONVERSATION.put("S17", "ai-feedback-learning");
    }

    private static final Pattern DESIGN_RESERVED = Pattern.compile("予約V(\\d+)");
    private static final Pattern TASKS_HEADER = Pattern.compile("予約番号は \\*\\*V(\\d+)\\*\\*");
    private static final Pattern TASKS_GUIDANCE = Pattern.compile("\\*\\*V(\\d+)\\*\\*/V1/H2");
    private static final Pattern MIGRATION_LINE = Pattern.compile("^- Migration: V(\\d+)", Pattern.MULTILINE);

    private String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "spec文書が見つかりません: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private int firstGroup(Pattern pattern, String text, String what) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), what + " が見つかりません（表記を変えた場合は本テストも更新してください）");
        return Integer.parseInt(m.group(1));
    }

    /**
     * 予約番号の唯一の正は `design.md` の「予約V##」。
     * tasks.md（冒頭の宣言と実装ガイダンスの2箇所）、派工対話、個別 .txt の全てが
     * 同じ番号を指していること。
     */
    @Test
    void 予約Migration番号が全ての派工資料で一致すること() throws Exception {
        String startConversations = read(EXPANSION.resolve("spec-start-conversations.md"));

        List<String> mismatches = new ArrayList<>();

        for (Map.Entry<String, String> entry : SPEC_BY_CONVERSATION.entrySet()) {
            String conversation = entry.getKey();
            String spec = entry.getValue();

            String design = read(SPECS.resolve(spec).resolve("design.md"));
            String tasks = read(SPECS.resolve(spec).resolve("tasks.md"));

            int expected = firstGroup(DESIGN_RESERVED, design, spec + " の design.md の予約V##");

            int tasksHeader = firstGroup(TASKS_HEADER, tasks, spec + " の tasks.md 冒頭の予約番号");
            if (tasksHeader != expected) {
                mismatches.add(spec + " tasks.md冒頭: V" + tasksHeader + " ≠ design.md V" + expected);
            }

            int tasksGuidance = firstGroup(TASKS_GUIDANCE, tasks, spec + " の tasks.md 実装ガイダンスの V##/V1/H2");
            if (tasksGuidance != expected) {
                mismatches.add(spec + " tasks.md実装ガイダンス: V" + tasksGuidance + " ≠ design.md V" + expected);
            }

            // 個別 .txt（実際にコピーされるファイル）
            Path txt = EXPANSION.resolve("copyable-conversations")
                    .resolve(conversation + "__" + spec + "__start.txt");
            int txtVersion = firstGroup(MIGRATION_LINE, read(txt), txt + " の Migration行");
            if (txtVersion != expected) {
                mismatches.add(txt.getFileName() + ": V" + txtVersion + " ≠ design.md V" + expected);
            }

            // 派工対話（該当specの節）
            int jpVersion = versionInSection(startConversations, "## " + conversation + " — ", spec);
            if (jpVersion != expected) {
                mismatches.add("spec-start-conversations.md " + conversation
                        + ": V" + jpVersion + " ≠ design.md V" + expected);
            }
        }

        assertTrue(mismatches.isEmpty(),
                "Migration予約番号が派工資料間でずれています。実装AIが従うのはtasks.mdと派工対話なので、"
                        + "ここがずれると適用済み番号を再利用してFlywayが起動時に例外を投げます。"
                        + "design.mdを正として全資料を揃えてください:\n  " + String.join("\n  ", mismatches));
    }

    /** 派工対話の該当節（次の "## " 見出しまで）から Migration 行を読む。 */
    private int versionInSection(String document, String headingPrefix, String spec) {
        int start = document.indexOf(headingPrefix);
        assertTrue(start >= 0, "派工対話に節 " + headingPrefix + " がありません");
        int next = document.indexOf("\n## ", start + headingPrefix.length());
        String section = next < 0 ? document.substring(start) : document.substring(start, next);
        return firstGroup(MIGRATION_LINE, section, spec + " の派工対話のMigration行");
    }

    /**
     * 予約番号が重複していないこと。並行ブランチが同じ番号を取ると、
     * 両方マージされた時点で初めて全環境が起動不能になる。
     */
    @Test
    void 予約Migration番号が重複していないこと() throws Exception {
        Map<Integer, List<String>> bySpec = new LinkedHashMap<>();
        for (String spec : SPEC_BY_CONVERSATION.values()) {
            int reserved = firstGroup(DESIGN_RESERVED, read(SPECS.resolve(spec).resolve("design.md")),
                    spec + " の予約V##");
            bySpec.computeIfAbsent(reserved, k -> new ArrayList<>()).add(spec);
        }

        List<String> duplicates = bySpec.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " -> " + e.getValue())
                .toList();

        assertTrue(duplicates.isEmpty(),
                "複数のspecが同じMigration番号を予約しています。後発を未使用の最新番号へ繰り上げてください"
                        + "（前の欠番は埋めない。適用済みDBがFlywayValidateExceptionで拒否します）: " + duplicates);
    }

    /**
     * 予約番号が既に適用済みの番号と衝突していないこと。
     * これがまさに `tasks.md` で起きていた事故（archiveがV62、productivityがV63を指していた）。
     */
    @Test
    void 予約Migration番号が実在スクリプトと衝突しないこと() throws Exception {
        Path migrationDir = Path.of("src", "main", "resources", "db", "migration");
        assertTrue(Files.isDirectory(migrationDir), "db/migration が見つかりません: " + migrationDir);

        Pattern versioned = Pattern.compile("^V(\\d+)__.+\\.sql$");
        int latest = 0;
        List<Integer> existing = new ArrayList<>();
        try (var stream = Files.list(migrationDir)) {
            for (Path p : stream.toList()) {
                Matcher m = versioned.matcher(String.valueOf(p.getFileName()));
                if (m.matches()) {
                    int v = Integer.parseInt(m.group(1));
                    existing.add(v);
                    latest = Math.max(latest, v);
                }
            }
        }
        assertTrue(latest > 0, "db/migration からバージョン付きスクリプトを読み込めていません");

        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, String> entry : SPEC_BY_CONVERSATION.entrySet()) {
            String spec = entry.getValue();
            int reserved = firstGroup(DESIGN_RESERVED, read(SPECS.resolve(spec).resolve("design.md")),
                    spec + " の予約V##");
            if (existing.contains(reserved)) {
                conflicts.add(entry.getKey() + " " + spec + " が実在するV" + reserved + " を予約しています");
            }
        }

        assertTrue(conflicts.isEmpty(),
                "予約番号が既に適用済みのMigrationと衝突しています（この指示どおりに実装すると全環境が起動不能）。"
                        + "実在最新はV" + latest + " です:\n  " + String.join("\n  ", conflicts));
    }

    /** V59は永久欠番。どのspecも予約してはならない。 */
    @Test
    void V59を予約しているspecが無いこと() throws Exception {
        for (Map.Entry<String, String> entry : SPEC_BY_CONVERSATION.entrySet()) {
            String spec = entry.getValue();
            int reserved = firstGroup(DESIGN_RESERVED, read(SPECS.resolve(spec).resolve("design.md")),
                    spec + " の予約V##");
            assertEquals(false, reserved == 59,
                    entry.getKey() + " " + spec + " がV59を予約しています。V59は永久欠番です");
        }
    }

    /**
     * S04以降の各specが「決定表」3表を持っていること。
     * この3表が無いままtaskを開始すると、時間モデル・認可母集団・状態機械をその場で決めることになり、
     * S02の21ラウンドの反復を繰り返す。
     */
    @Test
    void S04以降の各specが決定表3表を持つこと() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : SPEC_BY_CONVERSATION.entrySet()) {
            String design = read(SPECS.resolve(entry.getValue()).resolve("design.md"));
            for (String heading : List.of("時間・asOf", "主体 × 操作", "状態機械と競合")) {
                if (!design.contains(heading)) {
                    missing.add(entry.getKey() + " " + entry.getValue() + " に「" + heading + "」表がありません");
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "design.mdの決定表が欠けています（platform-invariants.mdの既定解と対応する3表が必須）:\n  "
                        + String.join("\n  ", missing));
    }

    /**
     * 派工対話が必須基線を読ませ、決定表を確定済みとして扱わせていること。
     * 読まれない基線は存在しないのと同じで、S02の再発防止がそのまま無効化される。
     */
    @Test
    void S04以降の派工対話が必須基線と決定表を指示すること() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : SPEC_BY_CONVERSATION.entrySet()) {
            String conversation = entry.getKey();
            String spec = entry.getValue();

            Path start = EXPANSION.resolve("copyable-conversations")
                    .resolve(conversation + "__" + spec + "__start.txt");
            String startText = read(start);
            for (String required : List.of(
                    "platform-invariants.md",
                    "execution-review-handbook.md",
                    "test-execution-policy-s03-s17.md",
                    "既定解と決定表",
                    "前の欠番は埋めない")) {
                if (!startText.contains(required)) {
                    missing.add(start.getFileName() + " に「" + required + "」がありません");
                }
            }

            Path review = EXPANSION.resolve("copyable-conversations")
                    .resolve(conversation.replace('S', 'R') + "__" + spec + "__review.txt");
            String reviewText = read(review);
            for (String required : List.of(
                    "platform-invariants.md",
                    "execution-review-handbook.md",
                    "test-execution-policy-s03-s17.md",
                    // 決定表を逐項照合させる。これが無いと「実装中に決めた設計」を見逃す
                    "既定解と決定表の照合",
                    // issue ID・再現条件・discovered in を必須にし、Round 4で収束させる
                    "指摘形式と収束規則",
                    // Review Packet不足時に推測でReviewしない
                    "NOT REVIEWABLE")) {
                if (!reviewText.contains(required)) {
                    missing.add(review.getFileName() + " に「" + required + "」がありません");
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "派工対話が必須基線を読ませていません（既定解が参照されず、S02と同じ再発見が起きます）:\n  "
                        + String.join("\n  ", missing));
    }

    /**
     * S11の開始条件が社労士確認を待たないこと。
     * 時間外計算の値は `overtime-rules.md` で確定済みで、社労士確認は本番releaseのgateである。
     * これを開工条件に書くとS11が永久に着手できない。
     */
    @Test
    void S11の開始条件が社労士確認で止まらないこと() throws Exception {
        Path start = EXPANSION.resolve("copyable-conversations")
                .resolve("S11__attendance-leave-overtime-compliance__start.txt");
        String text = read(start);

        assertTrue(text.contains("overtime-rules.md"),
                "S11がovertime-rules.mdを読むよう指示していません（時間外計算の値の唯一の正）");
        assertTrue(text.contains("本番releaseのgate"),
                "S11の開始条件が社労士確認を本番gateとして明記していません。開工条件に書くと着手できなくなります");
        assertTrue(text.contains("1メソッド1ルール"),
                "S11にcalculatorの構造制約（1メソッド1ルール）がありません");

        // Review側も同じ扱いにする。合否条件にすると未確認だけでFAILが出る
        Path review = EXPANSION.resolve("copyable-conversations")
                .resolve("R11__attendance-leave-overtime-compliance__review.txt");
        String reviewText = read(review);
        assertTrue(reviewText.contains("本番releaseのgate"),
                "R11が社労士確認を本番gateとして扱っていません。合否条件にすると未確認だけでFAILになります");
        assertTrue(reviewText.contains("月100時間だけが"),
                "R11に境界の重点確認（月100時間だけが >= 判定）がありません。ここがoff-by-oneの本命です");
        assertTrue(reviewText.contains("overtime-rules.md"),
                "R11がovertime-rules.mdを照合の正として指示していません");
    }

    /**
     * S13は `platform-invariants.md` §2の既定解が適用できない唯一のspecである。
     * 一律「既定解に従え」と指示すると、portal userに存在しないDataScope/組織scopeを
     * 流用しようとして破綻するため、例外であることを明示させる。
     */
    @Test
    void S13が認可母集団の例外であることを明示すること() throws Exception {
        for (String file : List.of(
                "S13__external-customer-bp-portal__start.txt",
                "R13__external-customer-bp-portal__review.txt")) {
            Path path = EXPANSION.resolve("copyable-conversations").resolve(file);
            assertTrue(read(path).contains("唯一のspec"),
                    file + " がplatform-invariants §2の例外であることを明示していません"
                            + "（portal userはDataScope・組織scope・menu権限を持たない。"
                            + "Review側に無いと『既存scope serviceを使っていない』が誤指摘になります）");
        }
    }
}
