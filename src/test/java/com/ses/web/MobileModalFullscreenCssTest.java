package com.ses.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-PRERUN 7: 幅390pxで {@code modal-fullscreen-sm-down} のモーダル（二者確認）が右へ約6pxはみ出していた。
 * {@code common.css} の携帯幅の {@code .modal-dialog { margin }} と {@code .modal-content { max-height / border-radius }} が、
 * Bootstrapの全画面変種の {@code margin: 0}・全高を上書きしていたため。
 *
 * <p>携帯幅（{@code max-width} の {@code @media}）で {@code .modal-dialog} の余白、{@code .modal-content} の高さ上限・角丸を
 * 決める規則は、全画面の変種（{@code modal-fullscreen} / {@code modal-fullscreen-*-down}）を除外していること、
 * その除外は {@code :where()} の中に置き、通常のモーダルに対する詳細度（1class分）を変えないことを、CSSを静的に読んで確かめる。
 * 実際の描画（はみ出しが無いこと）は実ブラウザでの確認（deploy-runbook §1.7）に任せる。</p>
 */
class MobileModalFullscreenCssTest {

    private static final Path COMMON_CSS = Path.of("src/main/resources/static/css/common.css");
    private static final String FULLSCREEN_EXCLUSION = ":not([class*=\"modal-fullscreen\"])";
    private static final Pattern DIALOG_SUBJECT = Pattern.compile("^\\.modal-dialog(?![\\w-])");
    private static final Pattern CONTENT_SUBJECT = Pattern.compile("^\\.modal-content(?![\\w-])");
    private static final Pattern MARGIN = Pattern.compile("(^|[;{\\s])margin(-[a-z]+)?\\s*:");
    private static final Pattern HEIGHT_OR_RADIUS = Pattern.compile("(^|[;{\\s])(max-height|border-radius)\\s*:");

    private record Rule(String media, String selector, String body) { }

    @Test
    void 携帯幅のモーダル余白と高さ上限は全画面の変種を除外し通常のモーダルの詳細度を変えない() throws IOException {
        List<Rule> rules = rules(Files.readString(COMMON_CSS, StandardCharsets.UTF_8));
        List<String> violations = new ArrayList<>();
        int dialogRules = 0;
        int contentRules = 0;
        for (Rule rule : rules) {
            if (!rule.media().contains("max-width")) {
                continue;
            }
            for (String selector : rule.selector().split(",")) {
                String trimmed = selector.trim();
                String subject = subject(trimmed);
                boolean dialogMargin = DIALOG_SUBJECT.matcher(subject).find() && MARGIN.matcher(rule.body()).find();
                boolean contentLimit = CONTENT_SUBJECT.matcher(subject).find()
                        && HEIGHT_OR_RADIUS.matcher(rule.body()).find();
                if (!dialogMargin && !contentLimit) {
                    continue;
                }
                if (dialogMargin) {
                    dialogRules++;
                } else {
                    contentRules++;
                }
                if (!trimmed.contains(FULLSCREEN_EXCLUSION) || !trimmed.contains(":where(")) {
                    violations.add(rule.media() + " { " + trimmed + " }");
                }
            }
        }

        assertThat(dialogRules).as("携帯幅の .modal-dialog の余白規則（768px・576px）を読めていること")
                .isGreaterThanOrEqualTo(2);
        assertThat(contentRules).as("携帯幅の .modal-content の高さ上限・角丸の規則を読めていること")
                .isGreaterThanOrEqualTo(2);
        assertThat(violations)
                .as("携帯幅のモーダル規則は :where(...:not([class*=\"modal-fullscreen\"])...) で全画面の変種を除外すること")
                .isEmpty();
    }

    @Test
    void 二者確認のモーダルは全画面の変種を使っている() throws IOException {
        for (String template : List.of("lifecycle/detail.html", "my/lifecycle-detail.html")) {
            assertThat(Files.readString(Path.of("src/main/resources/templates").resolve(template), StandardCharsets.UTF_8))
                    .as(template).contains("modal-dialog modal-dialog-scrollable modal-fullscreen-sm-down");
        }
    }

    /** 選択子の最後の複合選択子（子孫・子の結合子より後ろ）。:where() / :not() の中の空白は含まない前提。 */
    private static String subject(String selector) {
        String[] parts = selector.split("\\s*>\\s*|\\s+");
        return parts[parts.length - 1];
    }

    private static List<Rule> rules(String css) {
        String text = css.replaceAll("(?s)/\\*.*?\\*/", "");
        List<Rule> rules = new ArrayList<>();
        parse(text, 0, text.length(), "", rules);
        return rules;
    }

    private static void parse(String css, int from, int to, String media, List<Rule> out) {
        int index = from;
        while (index < to) {
            int open = css.indexOf('{', index);
            if (open < 0 || open >= to) {
                return;
            }
            String prelude = css.substring(index, open).trim();
            int close = matching(css, open);
            if (prelude.startsWith("@media")) {
                parse(css, open + 1, close, prelude, out);
            } else if (!prelude.startsWith("@")) {
                out.add(new Rule(media, prelude, css.substring(open + 1, close)));
            }
            index = close + 1;
        }
    }

    private static int matching(String css, int open) {
        int depth = 0;
        for (int i = open; i < css.length(); i++) {
            char c = css.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("CSSの括弧が閉じていません: offset=" + open);
    }
}
