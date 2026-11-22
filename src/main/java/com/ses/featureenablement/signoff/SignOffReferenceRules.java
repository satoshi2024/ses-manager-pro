package com.ses.featureenablement.signoff;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 署名の参照（決定 ID・文書 ID・ticket 番号等）の共通の規則（feature-enablement-console design §6.3）。
 *
 * <p>placeholder の語の規則は {@code LifecycleDualConfirmationReleaseGate}（NF01 二者確認の起動時検査）から移した共通の部品で、
 * 起動時検査もこの部品を使う（同じ値を同じに判定する。W3-21a review P3-6: 完全一致だけでは {@code TODO-1}・{@code TBD2026}・
 * {@code xxxxx} が通った）。</p>
 */
public final class SignOffReferenceRules {

    /** 参照の形式（英数字で始まる 4〜128 文字。{@code ._:/#-} を許す）。 */
    public static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{3,127}");

    /** 一部にでも含めば参照とみなさない語（小文字で比較）。 */
    private static final List<String> PLACEHOLDER_FRAGMENTS = List.of("todo", "tbd", "xxx", "placeholder", "changeme",
            "change-me", "change_me", "dummy", "fixme", "sample", "example", "unsigned", "replace", "your-");

    /** この語で始まる値は参照とみなさない（小文字で比較）。 */
    private static final List<String> PLACEHOLDER_PREFIXES = List.of("test", "pending", "tmp", "temp", "none", "null",
            "n/a", "dev", "local");

    private static final Pattern HAS_LETTER = Pattern.compile(".*[A-Za-z].*");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*[0-9].*");

    private SignOffReferenceRules() {
    }

    /** placeholder の語を一部に含むか、placeholder の語で始まるか（null は placeholder とみなす）。 */
    public static boolean placeholder(String value) {
        if (value == null) {
            return true;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return PLACEHOLDER_FRAGMENTS.stream().anyMatch(lower::contains)
                || PLACEHOLDER_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    /** 共通の規則: 前後の空白が無く、{@link #REFERENCE} の形式で、placeholder でない。 */
    public static boolean generic(String reference) {
        return reference != null && reference.trim().equals(reference) && REFERENCE.matcher(reference).matches()
                && !placeholder(reference);
    }

    /** 共通の規則に加えて英字と数字の両方を含む（NF01 二者確認の Privacy 署名の参照。起動時検査と同じ）。 */
    public static boolean withLetterAndDigit(String reference) {
        return generic(reference) && HAS_LETTER.matcher(reference).matches() && HAS_DIGIT.matcher(reference).matches();
    }
}
