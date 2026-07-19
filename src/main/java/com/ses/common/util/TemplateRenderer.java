package com.ses.common.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * テンプレートのプレースホルダをマップの値で置換する簡易レンダラ。
 *
 * 記法は後方互換のため2種を許容する（R3R-19）:
 *   - {@code {{key}}}（二重波括弧）… 未定義キーは空文字にする（従来挙動）。
 *   - {@code {key}}（一重波括弧）… パラメータに解決できた場合のみ置換し、
 *     解決できなければ原文（{key}）を残す（HTML/CSS中の無関係な波括弧を壊さないため）。
 *
 * キー解決は完全一致に加え、snake_case ↔ camelCase の相互変換も試みる。
 * 例: テンプレートが {@code {customer_name}} でも params が {@code customerName} なら解決する。
 */
public final class TemplateRenderer {

    // {{key}} を優先し、無ければ {key} を拾う。
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}|\\{(\\w+)\\}");

    private TemplateRenderer() {
    }

    public static String render(String template, Map<String, String> params) {
        if (template == null) {
            return "";
        }
        Map<String, String> safeParams = params != null ? params : Map.of();
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            boolean doubleBrace = matcher.group(1) != null;
            String key = doubleBrace ? matcher.group(1) : matcher.group(2);
            String resolved = resolve(safeParams, key);
            String replacement;
            if (resolved != null) {
                replacement = resolved;
            } else if (doubleBrace) {
                // 従来挙動: 二重波括弧の未定義キーは空文字。
                replacement = "";
            } else {
                // 一重波括弧の未解決は原文を残す（無関係な {word} を壊さない）。
                replacement = matcher.group();
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 完全一致 → snake→camel → camel→snake の順で解決する。見つからなければ null。 */
    private static String resolve(Map<String, String> params, String key) {
        if (params.containsKey(key)) {
            return params.get(key);
        }
        String camel = snakeToCamel(key);
        if (!camel.equals(key) && params.containsKey(camel)) {
            return params.get(camel);
        }
        String snake = camelToSnake(key);
        if (!snake.equals(key) && params.containsKey(snake)) {
            return params.get(snake);
        }
        return null;
    }

    private static String snakeToCamel(String s) {
        if (s.indexOf('_') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        boolean upper = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String camelToSnake(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
