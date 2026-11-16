package com.ses.privacy.production.dsar;

import com.ses.privacy.foundation.PrivacyCaseState;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DSAR（受付〜SCOPED は NF07-A2a、検索〜交付記録は NF07-A2b）の値の語彙。DB の CHECK（V190）と画面の選択肢はこの一覧に従う。
 *
 * <p>参照値（ticket・証跡）は英数字と一部の記号だけを受け付け、空白・{@code @} を含む自由文（氏名・連絡先の転記）を拒否する。
 * 参照値は外部の記録を指す ID であり、本文そのものは保存しない。
 */
public final class PrivacyDsarVocabulary {

    /** 外部 ticket・証跡の参照（V190 の列長 128 以内）。 */
    static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:#/-]{0,127}");
    /** 判定コード（V190 の列長 50 以内）。 */
    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,49}");
    /**
     * 参照に転記された書類の番号・電話番号に見える値（A2a review P3-1）。参照の形式は D-06 の SOP で決まるまで広く受け付けるため、
     * 誤検知の少ない明らかな形だけを拒否する: 数字だけで10桁以上（マイナンバー・運転免許証番号・電話番号・カード番号）、
     * 4桁区切りの12桁（マイナンバー・免許証の表記）、0 で始まる区切り付きの電話番号、英字2文字＋数字7桁（旅券番号）。
     */
    private static final List<Pattern> DOCUMENT_NUMBER_LIKE = List.of(
            Pattern.compile("[0-9]{10,}"),
            Pattern.compile("[0-9]{4}-[0-9]{4}-[0-9]{4}"),
            Pattern.compile("0[0-9]{1,4}-[0-9]{1,4}-[0-9]{3,4}"),
            Pattern.compile("[A-Za-z]{2}[0-9]{7}"));

    /**
     * 終了（BLOCKED / CANCELLED）を記録できる状態。foundation の遷移表（{@code PrivacyCaseStateMachine}）で BLOCKED・CANCELLED へ
     * 進める状態のうち、DSAR が使う範囲（A2a の受付〜SCOPED と、A2b の草案・承認待ち・承認済み）。BLOCKED / CANCELLED は終了状態で、
     * 再開は新しい case（{@code reopen_of_request_id}）で行う。終了した case の package は削除の scheduler が PURGED にする。
     */
    static final Set<PrivacyCaseState> CLOSABLE_STATES = EnumSet.of(PrivacyCaseState.REQUESTED,
            PrivacyCaseState.IDENTITY_VERIFIED, PrivacyCaseState.SCOPED, PrivacyCaseState.PREVIEWED,
            PrivacyCaseState.APPROVAL_PENDING, PrivacyCaseState.APPROVED);

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
        /**
         * 本人確認の後に D-06 の決定（確認基準）が差し替わり、適用した基準と食い違う（A2a delta review D-4。再申立として受け付け直す）。
         */
        DECISION_CHANGED(PrivacyCaseState.BLOCKED),
        /** 第三者情報を除けないため開示資料を作れない（NF07-A2b）。 */
        REDACTION_NOT_POSSIBLE(PrivacyCaseState.BLOCKED),
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

    /**
     * 画面に出す次の操作。PREVIEW 以降は NF07-A2b（検索と草案 package、承認者による草案の確認、承認、専用ダウンロード、
     * 交付の記録、開示以外の請求の手動対応の完了）。
     */
    public enum NextAction {
        VERIFY_IDENTITY, ADD_CANDIDATE, RESOLVE_CANDIDATE, SCOPE, PREVIEW, REVIEW_DRAFT, APPROVE, DOWNLOAD,
        RECORD_DELIVERY, RECORD_MANUAL_RESOLUTION, BLOCK, CANCEL
    }

    /**
     * 開示以外の請求（訂正・削除・利用停止・異議）の手動対応の結果（NF07-A2b review P2-2。SCOPED → CONFIRMED の
     * {@code MANUAL_ACTION} 行の {@code blocked_reason}、VARCHAR(50)）。system は処分・訂正を実行せず、人が system の外または
     * 業務画面で行った対応の記録だけを残す。
     */
    public enum ManualResolution {
        /** 訂正した。 */
        CORRECTED(RequestType.CORRECTION),
        /** 訂正の必要が無かった（記録が正しい）。 */
        CORRECTION_NOT_NEEDED(RequestType.CORRECTION),
        /** 削除した。 */
        DELETED(RequestType.DELETION),
        /** 法令上の保存義務があるため削除しなかった。 */
        DELETION_REFUSED_RETENTION(RequestType.DELETION),
        /** 利用を停止した。 */
        RESTRICTED(RequestType.RESTRICTION),
        /** 利用停止に応じなかった（理由は証跡で残す）。 */
        RESTRICTION_REFUSED(RequestType.RESTRICTION),
        /** 異議を認めた。 */
        OBJECTION_UPHELD(RequestType.OBJECTION),
        /** 異議を認めなかった（理由は証跡で残す）。 */
        OBJECTION_REJECTED(RequestType.OBJECTION),
        /** その他の対応（証跡の参照で内容を残す）。 */
        OTHER_RESOLVED(RequestType.CORRECTION, RequestType.DELETION, RequestType.RESTRICTION, RequestType.OBJECTION);

        private final Set<RequestType> requestTypes;

        ManualResolution(RequestType... requestTypes) {
            this.requestTypes = Set.of(requestTypes);
        }

        /** この結果を記録できる請求の種別（開示 ACCESS は含まない）。 */
        public boolean appliesTo(String requestType) {
            return parse(RequestType.class, requestType).filter(requestTypes::contains).isPresent();
        }
    }

    /**
     * 交付の方法（system の外で交付した方法の記録。{@code t_privacy_request.delivery_method} VARCHAR(20)）。
     * system から外部へ送信する経路は持たない（I-4）。
     */
    public enum DeliveryMethod {
        EMAIL, POSTAL, IN_PERSON, SECURE_TRANSFER, OTHER
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

    /** 参照が書類の番号・電話番号に見えるか（{@link #DOCUMENT_NUMBER_LIKE}）。 */
    static boolean looksLikeDocumentNumber(String value) {
        return value != null && DOCUMENT_NUMBER_LIKE.stream().anyMatch(pattern -> pattern.matcher(value).matches());
    }

    /** 保存済みの状態値。解釈できない値は空（呼出し側は遷移を拒否する）。 */
    static Optional<PrivacyCaseState> state(String stored) {
        return parse(PrivacyCaseState.class, stored);
    }

    /**
     * 状態ごとに許す次の操作。草案 package（PREVIEW）は開示請求（ACCESS）だけで作る（§3.5）。訂正・削除・利用停止・異議は
     * SCOPED から手動対応の完了（RECORD_MANUAL_RESOLUTION、SCOPED → CONFIRMED）を記録する（review P2-2）。package の有無・作成者・
     * 期限による絞込み（承認者は作成者以外、期限切れなら作り直し）は {@code PrivacyExportPackageService#section} が行う。
     */
    static List<NextAction> nextActions(PrivacyCaseState state, String requestType) {
        if (state == null) {
            return List.of();
        }
        boolean access = RequestType.ACCESS.name().equals(requestType);
        return switch (state) {
            case REQUESTED -> List.of(NextAction.VERIFY_IDENTITY, NextAction.BLOCK, NextAction.CANCEL);
            case IDENTITY_VERIFIED -> List.of(NextAction.ADD_CANDIDATE, NextAction.RESOLVE_CANDIDATE,
                    NextAction.SCOPE, NextAction.BLOCK, NextAction.CANCEL);
            case SCOPED -> access ? List.of(NextAction.PREVIEW, NextAction.BLOCK, NextAction.CANCEL)
                    : List.of(NextAction.RECORD_MANUAL_RESOLUTION, NextAction.BLOCK, NextAction.CANCEL);
            case PREVIEWED, APPROVAL_PENDING -> List.of(NextAction.REVIEW_DRAFT, NextAction.APPROVE, NextAction.BLOCK,
                    NextAction.CANCEL);
            case APPROVED -> List.of(NextAction.DOWNLOAD, NextAction.RECORD_DELIVERY, NextAction.BLOCK,
                    NextAction.CANCEL);
            default -> List.of();
        };
    }
}
