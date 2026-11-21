package com.ses.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 実MySQL testのcontainer imageが {@link MySqlTestImages} 以外から決まっていないかを、Java sourceの構造で検査する
 * （{@code MySqlTestImagesTest} から使う）。
 *
 * <p>行単位の正規表現ではなく、commentを除き、文字列literal・text blockを分離してから次を検出する。
 * <ul>
 *   <li>{@code new …MySQLContainer(…)}（改行・raw type・{@code <?>}・FQNを含む）の第1実引数が、
 *       {@code MySqlTestImages.image()}／{@code freshInstallImage()}、または同じfile内でそのどちらかだけを代入した変数でない</li>
 *   <li>無引数constructor（Testcontainers 1.20.6 では {@code mysql:5.7.34} になる）</li>
 *   <li>{@code mysql:}／{@code mysql@}（{@code docker.io/}・{@code library/} 付きを含む）で始まる文字列literal、
 *       同じ形で始まる行を持つtext block</li>
 *   <li>{@code "mysql"} との文字列連結、{@code String.format}／{@code formatted} に渡す {@code "mysql…"}</li>
 *   <li>{@code DockerImageName.parse/of("mysql…")}、{@code asCompatibleSubstituteFor}、
 *       {@code MySQLContainer.NAME/IMAGE/DEFAULT_TAG}</li>
 * </ul>
 * source上の検査なので、上記以外の経路（例えば別のsystem propertyを読んで組み立てる）で決めたimageは検出できない。
 */
final class MySqlImageSourceScanner {

    private static final String IMAGE_HEAD = "(?:docker\\.io/)?(?:library/)?mysql";
    /** MySQL imageの参照（tag・digest付き）で始まる文字列。 */
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("^" + IMAGE_HEAD + "[:@]");
    /** image名だけの文字列（連結の材料）。 */
    private static final Pattern IMAGE_NAME_ONLY = Pattern.compile("^" + IMAGE_HEAD + "$");
    /** image名で始まる文字列（DockerImageName・formatの引数）。 */
    private static final Pattern IMAGE_NAME_PREFIX = Pattern.compile("^" + IMAGE_HEAD + "(?:[:@%/]|$)");
    private static final Pattern CONSTRUCTOR = Pattern.compile(
            "\\bnew\\s+(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*MySQLContainer\\s*(?:<[^<>()]*(?:<[^<>()]*>[^<>()]*)*>)?\\s*\\(");
    private static final Pattern ALLOWED_ARGUMENT = Pattern.compile(
            "(?:com\\.ses\\.test\\.)?MySqlTestImages\\.(?:image|freshInstallImage)\\(\\)");
    private static final String ALLOWED_ASSIGNMENT =
            "\\s*(?:com\\.ses\\.test\\.)?MySqlTestImages\\s*\\.\\s*(?:image|freshInstallImage)\\s*\\(\\s*\\)";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern DOCKER_IMAGE_NAME = Pattern.compile("\\bDockerImageName\\s*\\.\\s*(?:parse|of)\\s*\\(");
    private static final Pattern COMPATIBLE_SUBSTITUTE = Pattern.compile("\\basCompatibleSubstituteFor\\b");
    private static final Pattern CONTAINER_CONSTANT =
            Pattern.compile("\\bMySQLContainer\\s*\\.\\s*(?:NAME|IMAGE|DEFAULT_TAG)\\b");
    private static final Pattern FORMAT_CALL = Pattern.compile("\\bString\\s*\\.\\s*format\\s*\\(|\\.\\s*formatted\\s*\\(");

    private MySqlImageSourceScanner() {
    }

    /** 文字列literal。{@code start} は開き引用符、{@code end} は閉じ引用符の直後の位置。 */
    record Literal(int start, int end, String content, boolean textBlock) {
    }

    /**
     * commentを空白にし、文字列literal・text block・char literalの中身を空白にしたcode（元と同じ長さ・同じ改行位置）と、
     * 文字列literalの一覧。
     */
    record Lexed(String code, List<Literal> literals) {
    }

    /** sourceの違反を「行番号: 理由」で返す。 */
    static List<String> violations(String source) {
        Lexed lexed = lex(source);
        String code = lexed.code();
        List<String> found = new ArrayList<>();
        Map<Integer, Literal> literalAt = new HashMap<>();
        for (Literal literal : lexed.literals()) {
            literalAt.put(literal.start(), literal);
        }

        Matcher constructor = CONSTRUCTOR.matcher(code);
        while (constructor.find()) {
            String argument = firstArgument(code, constructor.end()).replaceAll("\\s+", "");
            if (argument.isEmpty()) {
                found.add(at(code, constructor.start(), "無引数のMySQLContainer constructor（Testcontainers 1.20.6 の既定imageは 5.7.34）"));
            } else if (!ALLOWED_ARGUMENT.matcher(argument).matches() && !assignedOnlyFromImages(code, argument)) {
                found.add(at(code, constructor.start(), "MySQLContainer の引数が MySqlTestImages 経由ではない: " + argument));
            }
        }

        for (Literal literal : lexed.literals()) {
            if (literal.textBlock()) {
                for (String line : literal.content().split("\n", -1)) {
                    if (IMAGE_REFERENCE.matcher(line.strip()).find()) {
                        found.add(at(code, literal.start(), "text block に MySQL image がある: " + line.strip()));
                        break;
                    }
                }
            } else if (IMAGE_REFERENCE.matcher(literal.content()).find()) {
                found.add(at(code, literal.start(), "MySQL image の文字列literal: " + literal.content()));
            } else if (IMAGE_NAME_ONLY.matcher(literal.content()).matches()
                    && (nextNonSpace(code, literal.end()) == '+' || previousNonSpace(code, literal.start()) == '+')) {
                found.add(at(code, literal.start(), "MySQL image名への文字列連結: " + literal.content()));
            }
        }

        Matcher dockerImageName = DOCKER_IMAGE_NAME.matcher(code);
        while (dockerImageName.find()) {
            Literal first = literalAt.get(skipSpaces(code, dockerImageName.end()));
            if (first != null && IMAGE_NAME_PREFIX.matcher(first.content()).find()) {
                found.add(at(code, dockerImageName.start(), "DockerImageName で MySQL image を組み立てている: " + first.content()));
            }
        }

        Matcher substitute = COMPATIBLE_SUBSTITUTE.matcher(code);
        while (substitute.find()) {
            found.add(at(code, substitute.start(), "asCompatibleSubstituteFor で mysql 以外の名前のimageを使っている"));
        }

        Matcher constant = CONTAINER_CONSTANT.matcher(code);
        while (constant.find()) {
            found.add(at(code, constant.start(), "MySQLContainer の image定数を使っている: " + constant.group()));
        }

        Matcher format = FORMAT_CALL.matcher(code);
        while (format.find()) {
            int open = format.end() - 1;
            int close = closingParenthesis(code, open);
            int receiverEnd = format.group().startsWith(".") ? trimEnd(code, format.start()) : -1;
            for (Literal literal : lexed.literals()) {
                boolean argument = literal.start() > open && literal.start() < close;
                boolean receiver = literal.end() == receiverEnd;
                if ((argument || receiver) && IMAGE_NAME_PREFIX.matcher(literal.content()).find()) {
                    found.add(at(code, format.start(), "format で MySQL image を組み立てている: " + literal.content()));
                    break;
                }
            }
        }
        return found;
    }

    /** MySQLContainer constructor 呼出しの件数（走査が空振りしていないことの確認用）。 */
    static int constructorCount(String source) {
        Matcher constructor = CONSTRUCTOR.matcher(lex(source).code());
        int count = 0;
        while (constructor.find()) {
            count++;
        }
        return count;
    }

    static Lexed lex(String source) {
        char[] code = source.toCharArray();
        List<Literal> literals = new ArrayList<>();
        int length = source.length();
        int i = 0;
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int newline = source.indexOf('\n', i);
                int stop = newline < 0 ? length : newline;
                blank(code, i, stop);
                i = stop;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int close = source.indexOf("*/", i + 2);
                int stop = close < 0 ? length : close + 2;
                blank(code, i, stop);
                i = stop;
            } else if (source.startsWith("\"\"\"", i)) {
                int k = i + 3;
                while (k < length && !source.startsWith("\"\"\"", k)) {
                    k += source.charAt(k) == '\\' ? 2 : 1;
                }
                int contentEnd = Math.min(k, length);
                int stop = Math.min(k + 3, length);
                literals.add(new Literal(i, stop, source.substring(i + 3, contentEnd), true));
                blank(code, i + 3, contentEnd);
                i = stop;
            } else if (c == '"' || c == '\'') {
                int k = i + 1;
                while (k < length && source.charAt(k) != c && source.charAt(k) != '\n') {
                    k += source.charAt(k) == '\\' ? 2 : 1;
                }
                int contentEnd = Math.min(k, length);
                int stop = Math.min(k + 1, length);
                if (c == '"') {
                    literals.add(new Literal(i, stop, source.substring(i + 1, contentEnd), false));
                }
                blank(code, i + 1, contentEnd);
                i = stop;
            } else {
                i++;
            }
        }
        return new Lexed(new String(code), literals);
    }

    /** {@code identifier} が同じfile内で MySqlTestImages の呼出しだけを代入されているか。 */
    private static boolean assignedOnlyFromImages(String code, String identifier) {
        if (!IDENTIFIER.matcher(identifier).matches()) {
            return false;
        }
        Matcher assignment = Pattern.compile("(?<![\\w$.])" + Pattern.quote(identifier) + "\\s*=(?!=)").matcher(code);
        Pattern allowed = Pattern.compile(ALLOWED_ASSIGNMENT);
        int assignments = 0;
        while (assignment.find()) {
            assignments++;
            if (!allowed.matcher(code).region(assignment.end(), code.length()).lookingAt()) {
                return false;
            }
        }
        return assignments > 0;
    }

    private static String firstArgument(String code, int from) {
        int depth = 0;
        for (int k = from; k < code.length(); k++) {
            char ch = code.charAt(k);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                if (depth == 0) {
                    return code.substring(from, k);
                }
                depth--;
            } else if (ch == ',' && depth == 0) {
                return code.substring(from, k);
            }
        }
        return code.substring(from);
    }

    private static int closingParenthesis(String code, int open) {
        int depth = 0;
        for (int k = open; k < code.length(); k++) {
            char ch = code.charAt(k);
            if (ch == '(') {
                depth++;
            } else if (ch == ')' && --depth == 0) {
                return k;
            }
        }
        return code.length();
    }

    private static void blank(char[] code, int from, int to) {
        for (int k = from; k < to && k < code.length; k++) {
            if (code[k] != '\n') {
                code[k] = ' ';
            }
        }
    }

    private static int skipSpaces(String code, int from) {
        int k = from;
        while (k < code.length() && Character.isWhitespace(code.charAt(k))) {
            k++;
        }
        return k;
    }

    private static int trimEnd(String code, int end) {
        int k = end;
        while (k > 0 && Character.isWhitespace(code.charAt(k - 1))) {
            k--;
        }
        return k;
    }

    private static char nextNonSpace(String code, int from) {
        int k = skipSpaces(code, from);
        return k < code.length() ? code.charAt(k) : '\0';
    }

    private static char previousNonSpace(String code, int before) {
        int k = trimEnd(code, before);
        return k > 0 ? code.charAt(k - 1) : '\0';
    }

    private static String at(String code, int position, String reason) {
        int line = 1;
        for (int k = 0; k < position; k++) {
            if (code.charAt(k) == '\n') {
                line++;
            }
        }
        return line + ": " + reason;
    }
}
