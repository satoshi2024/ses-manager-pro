package com.ses.service.security.epoch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * NF01 writer閉包の静的guard（U3・U4a・U15）が共有する、{@code src/main/java} のソース読取helper。
 *
 * <p>コメントは除き、文字列literalは残す（呼出しの第1引数のkeyや注釈SQLを読むため）。型名による変数の特定は
 * フィールド・引数・局所変数の宣言 {@code Type name} の形だけを見る（完全な構文解析はしない）。
 * 取りこぼしが許可にならないよう、各guardは「読めた件数が期待以上であること」も確認する。</p>
 */
final class WriterGuardSources {

    static final Path JAVA_ROOT = Path.of("src/main/java");

    private static final Pattern STRING_CONSTANT = Pattern.compile(
            "static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");

    private WriterGuardSources() {
    }

    /** {@code src/main/java} 配下の全Javaソース（相対path → コメントを除いた本文）。 */
    static Map<String, String> mainSources() {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(JAVA_ROOT)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".java")).toList()) {
                String relative = JAVA_ROOT.relativize(path).toString().replace('\\', '/');
                sources.put(relative, stripComments(Files.readString(path, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sources;
    }

    /** 1ファイルを読み、コメントを除いて返す。 */
    static String mainSource(String relative) {
        try {
            return stripComments(Files.readString(JAVA_ROOT.resolve(relative), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 行コメント・ブロックコメントを空白へ置き換える（文字列literal・text block・文字literalの中は触らない）。 */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? length : end;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
                out.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? length : end + 3;
                out.append(source, i, stop);
                i = stop;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < length && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                int stop = Math.min(length, j + 1);
                out.append(source, i, stop);
                i = stop;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 同じファイルの {@code static final String NAME = "literal";} の一覧。 */
    static Map<String, String> stringConstants(String source) {
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher matcher = STRING_CONSTANT.matcher(source);
        while (matcher.find()) {
            constants.put(matcher.group(1), matcher.group(2));
        }
        return constants;
    }

    /** 指定した型（単純名または完全修飾名）で宣言された変数名（フィールド・引数・局所変数）。 */
    static List<String> variablesOfType(String source, String simpleTypeName) {
        Pattern declaration = Pattern.compile("(?<![\\w.])(?:[\\w]+\\.)*" + Pattern.quote(simpleTypeName)
                + "\\s+(\\w+)\\s*(?=[;,)=])");
        List<String> names = new ArrayList<>();
        Matcher matcher = declaration.matcher(source);
        while (matcher.find()) {
            if (!names.contains(matcher.group(1))) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * 呼出しの第1引数（式）を文字列keyへ解決する。文字列literal、同じファイルの文字列定数、
     * {@code AuthorizationConfigKeys.X} を解決でき、それ以外は {@code null}（解決不能）を返す。
     */
    static String resolveKey(String expression, Map<String, String> constants) {
        String trimmed = expression.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        if (constants.containsKey(trimmed)) {
            return constants.get(trimmed);
        }
        String prefix = AuthorizationConfigKeys.class.getSimpleName() + ".";
        if (trimmed.startsWith(prefix) || trimmed.startsWith(AuthorizationConfigKeys.class.getName() + ".")) {
            String field = trimmed.substring(trimmed.lastIndexOf('.') + 1);
            try {
                Object value = AuthorizationConfigKeys.class.getField(field).get(null);
                return value instanceof String text ? text : null;
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
        return null;
    }

    /** 呼出しの開き括弧の直後から対応する閉じ括弧までの引数文字列（文字列literal内の括弧は数えない）。 */
    static String callArguments(String source, int afterOpenParen) {
        int depth = 1;
        boolean inString = false;
        for (int i = afterOpenParen; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' && source.charAt(i - 1) != '\\') {
                inString = !inString;
            } else if (!inString && (c == '(' || c == '{' || c == '[')) {
                depth++;
            } else if (!inString && (c == ')' || c == '}' || c == ']') && --depth == 0) {
                return source.substring(afterOpenParen, i);
            }
        }
        return source.substring(afterOpenParen);
    }

    /**
     * 括弧・文字列literalの外側にある区切り文字で分ける（引数の分割、{@code +} 連結・三項演算子の分解に使う）。
     * {@code ::}（メソッド参照）は {@code :} の区切りとみなさない。
     */
    static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString && (c == '(' || c == '{' || c == '[')) {
                depth++;
            } else if (!inString && (c == ')' || c == '}' || c == ']')) {
                depth--;
            } else if (!inString && depth == 0 && c == separator
                    && !(separator == ':' && ((i + 1 < text.length() && text.charAt(i + 1) == ':')
                    || (i > 0 && text.charAt(i - 1) == ':')))) {
                parts.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        parts.add(text.substring(start).trim());
        return parts;
    }

    /** 1行に整えて短縮した表示（失敗時のmessage用）。 */
    static String abbreviate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 140 ? flat : flat.substring(0, 140) + "...";
    }
}
