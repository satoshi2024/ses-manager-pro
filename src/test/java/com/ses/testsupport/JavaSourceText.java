package com.ses.testsupport;

/**
 * source の走査で使う test 専用の補助（呼出し元を固定する守衛 test 用）。テストclassではない（surefire対象外の命名）。
 *
 * <p>{@link #stripComments} は文字列・文字・text block の literal を保ったまま、行 comment と block comment を空白へ置き換える。
 * 正規表現で {@code /* ... *}{@code /} を消すと、文字列の中の {@code "/*"} から実 code まで消してしまうため（RB-NF08-LINK
 * review P3-3(c)）、literal を読み飛ばす小さな字句走査にしている。Unicode escape（{@code /} 等）は扱わない。
 */
public final class JavaSourceText {

    private JavaSourceText() {
    }

    /** comment を空白へ置き換えた source（literal の中身はそのまま）。 */
    public static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append(source, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(n, j + 1);
                out.append(source, i, j);
                i = j;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                end = end < 0 ? n : end;
                out.append(' ');
                i = end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                out.append(' ');
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
