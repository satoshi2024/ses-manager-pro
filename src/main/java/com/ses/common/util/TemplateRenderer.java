package com.ses.common.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {{変数}} 形式のプレースホルダをマップの値で置換する簡易テンプレートレンダラ。
 */
public final class TemplateRenderer {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    private TemplateRenderer() {
    }

    /**
     * template中の {{key}} を params[key] で置換する。未定義キーは空文字にする。
     */
    public static String render(String template, Map<String, String> params) {
        if (template == null) {
            return "";
        }
        Map<String, String> safeParams = params != null ? params : Map.of();
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = safeParams.getOrDefault(key, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
