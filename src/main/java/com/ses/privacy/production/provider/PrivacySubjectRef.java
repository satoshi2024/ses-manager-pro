package com.ses.privacy.production.provider;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 種別付きの subject 参照（例: {@code ENGINEER:123}）。ID は正の整数だけを受け付ける。
 *
 * @param type 本人の種別
 * @param id   種別ごとの主キー
 */
public record PrivacySubjectRef(PrivacySubjectType type, long id) {

    private static final Pattern ASCII_ID = Pattern.compile("[1-9][0-9]{0,17}");

    public PrivacySubjectRef {
        Objects.requireNonNull(type, "subjectの種別が必要です");
        if (id <= 0) {
            throw new IllegalArgumentException("subjectのIDは正の整数です");
        }
    }

    public static PrivacySubjectRef of(PrivacySubjectType type, long id) {
        return new PrivacySubjectRef(type, id);
    }

    /** {@code <種別>:<ID>} 形式の参照文字列。 */
    public String ref() {
        return type.name() + ":" + id;
    }

    /** 参照文字列を厳密に解釈する。種別の綴り違い・ID の欠落や不正は空を返す。 */
    public static Optional<PrivacySubjectRef> parse(String ref) {
        if (ref == null) {
            return Optional.empty();
        }
        int separator = ref.indexOf(':');
        if (separator <= 0 || separator != ref.lastIndexOf(':') || separator == ref.length() - 1) {
            return Optional.empty();
        }
        String typePart = ref.substring(0, separator);
        String idPart = ref.substring(separator + 1);
        // ASCII の数字だけ（全角・他の文字体系の数字は受け付けない）。先頭0なし、18桁まで。
        if (!ASCII_ID.matcher(idPart).matches()) {
            return Optional.empty();
        }
        for (PrivacySubjectType type : PrivacySubjectType.values()) {
            if (type.name().equals(typePart)) {
                return Optional.of(new PrivacySubjectRef(type, Long.parseLong(idPart)));
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return ref();
    }
}
