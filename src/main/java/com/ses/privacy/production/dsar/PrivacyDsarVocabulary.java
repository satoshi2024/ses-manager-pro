package com.ses.privacy.production.dsar;

import com.ses.privacy.foundation.PrivacyCaseState;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DSAR 受付〜SCOPED（NF07-A2a）の値の語彙。DB の CHECK（V190）と画面の選択肢はこの一覧に従う。
 *
 * <p>参照値（ticket・証跡）は英数字と一部の記号だけを受け付け、空白・{@code @} を含む自由文（氏名・連絡先の転記）を拒否する。
 * 参照値は外部の記録を指す ID であり、本文そのものは保存しない。
 */
public final class PrivacyDsarVocabulary {

    /** 外部 ticket・証跡の参照（V190 の列長 128 以内）。 */
    static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:#/-]{0,127}");
    /** 判定コード（V190 の列長 50 以内）。 */
    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,49}");

    /** A2a が扱う状態。BLOCKED / CANCELLED は終了状態で、再開は新しい case（{@code reopen_of_request_id}）で行う。 */
    static final Set<PrivacyCaseState> CLOSABLE_STATES = EnumSet.of(PrivacyCaseState.REQUESTED,
            PrivacyCaseState.IDENTITY_VERIFIED, PrivacyCaseState.SCOPED);

    /** 再申立の元にできる終了状態。 */
    static final Set<PrivacyCaseState> TERMINAL_STATES = EnumSet.of(PrivacyCaseState.CONFIRMED,
            PrivacyCaseState.BLOCKED, PrivacyCaseState.CANCELLED, PrivacyCaseState.UNKNOWN,
            PrivacyCaseState.PARTIAL_FAILED);

    /** 期限を追う（通知の対象にする）状態。終了状態は含めない。 */
    static final List<String> DEADLINE_TRACKED_STATES = List.of(PrivacyCaseState.REQUESTED.name(),
            PrivacyCaseState.IDENTITY_VERIFIED.name(), PrivacyCaseState.SCOPED.name(),
            PrivacyCaseState.PREVIEWED.name(), PrivacyCaseState.APPROVAL_PENDING.name(),
            PrivacyCaseState.APPROVED.name());

    private PrivacyDsarVocabulary() {
    }

    /** 請求の種別（V190 の {@code chk_privacy_request_type}）。 */
    public enum RequestType {
        ACCESS, CORRECTION, DELETION, RESTRICTION, OBJECTION
    }

    /** 受付経路。 */
    public enum Channel {
        EMAIL, POSTAL, WEB_FORM, PHONE, IN_PERSON, OTHER
    }

    /** 名寄せの link の状態（V190 の {@code chk_privacy_subject_link_state}）。 */
    public enum LinkState {
        CANDIDATE, SELECTED, REJECTED
    }

    /** 本人確認の状態（V190 の {@code chk_privacy_request_identity}）。A2a は VERIFIED だけを書く。 */
    public enum IdentityState {
        UNVERIFIED, AMBIGUOUS, VERIFIED
    }

    /** 終了理由と、その理由で入る終了状態。 */
    public enum CloseReason {
        /** 本人を特定できない（該当する記録が無い）。 */
        SUBJECT_NOT_FOUND(PrivacyCaseState.BLOCKED),
        /** 候補を1件に絞れない。 */
        MULTIPLE_CANDIDATES(PrivacyCaseState.BLOCKED),
        /** 本人確認ができない。 */
        IDENTITY_UNVERIFIED(PrivacyCaseState.BLOCKED),
        /** 対象外の請求。 */
        OUT_OF_SCOPE(PrivacyCaseState.BLOCKED),
        /** 依頼者の取下げ。 */
        WITHDRAWN(PrivacyCaseState.CANCELLED),
        /** 重複した受付。 */
        DUPLICATE(PrivacyCaseState.CANCELLED),
        /** その他（証跡の参照で理由を残す）。 */
        OTHER_BLOCKED(PrivacyCaseState.BLOCKED),
        /** その他の取下げ。 */
        OTHER_CANCELLED(PrivacyCaseState.CANCELLED);

        private final PrivacyCaseState target;

        CloseReason(PrivacyCaseState target) {
            this.target = target;
        }

        public PrivacyCaseState target() {
            return target;
        }
    }

    /** 画面に出す次の操作。 */
    public enum NextAction {
        VERIFY_IDENTITY, ADD_CANDIDATE, RESOLVE_CANDIDATE, SCOPE, BLOCK, CANCEL
    }

    /** 列挙値を大文字小文字を区別して厳密に解釈する（綴り違い・前後空白は空）。 */
    static <E extends Enum<E>> Optional<E> parse(Class<E> type, String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(value)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }

    /** 保存済みの状態値。解釈できない値は空（呼出し側は遷移を拒否する）。 */
    static Optional<PrivacyCaseState> state(String stored) {
        return parse(PrivacyCaseState.class, stored);
    }

    /** 状態ごとに許す次の操作（A2a の範囲。A2b は SCOPED 以降を足す）。 */
    static List<NextAction> nextActions(PrivacyCaseState state) {
        if (state == null) {
            return List.of();
        }
        return switch (state) {
            case REQUESTED -> List.of(NextAction.VERIFY_IDENTITY, NextAction.BLOCK, NextAction.CANCEL);
            case IDENTITY_VERIFIED -> List.of(NextAction.ADD_CANDIDATE, NextAction.RESOLVE_CANDIDATE,
                    NextAction.SCOPE, NextAction.BLOCK, NextAction.CANCEL);
            case SCOPED -> List.of(NextAction.BLOCK, NextAction.CANCEL);
            default -> List.of();
        };
    }
}
