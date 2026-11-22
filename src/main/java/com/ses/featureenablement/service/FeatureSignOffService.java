package com.ses.featureenablement.service;

import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureSignOff;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 1件の署名の登録・確認・却下・取下げ・取消し・差替え・延長・取得（feature-enablement-console design §6。組は
 * {@code FeatureSignOffBundleService}）。差替えは同じ種別の新しい署名の確認で行う。
 */
public interface FeatureSignOffService {

    /** 申請の種類（延長）。 */
    String REQUEST_TYPE_EXTENSION = "SIGN_OFF_EXTENSION";
    /** 延長の申請の対象の key の接頭辞（{@code signoff:<id>}）。 */
    String EXTENSION_TARGET_PREFIX = "signoff:";
    /** 氏名の長さの上限（{@code signer_name}）。 */
    int SIGNER_NAME_MAX = 100;

    /** 登録の入力（文書は別の引数）。 */
    record Registration(String kindCode, String reference, String signerRole, String signerName, LocalDate signedOn,
                        LocalDate expiresOn, Map<String, String> structuredValues) {

        public Registration {
            structuredValues = structuredValues == null ? Map.of() : Map.copyOf(structuredValues);
        }
    }

    /** 一覧の1行（氏名と元の file 名を含めない。design §6.6）。 */
    record Summary(long id, String kindCode, String kindClass, String reference, String signerRole, LocalDate signedOn,
                   LocalDate expiresOn, String status, int version, Long registeredBy, LocalDateTime registeredAt,
                   Long confirmedBy, LocalDateTime confirmedAt, String endReason, String contentSha256Prefix) {
    }

    /** 文書の取得の結果（hash を照合した bytes）。 */
    record Document(String fileName, byte[] content, String contentSha256) {
    }

    /** 署名を登録する（{@code REGISTERED}）。文書は scan が {@code CLEAN} でなければ保存せず拒否する。 */
    FeatureSignOff register(Registration registration, byte[] document, String originalName,
                            FeatureEnablementActor actor);

    /**
     * 登録者以外が確認する（{@code CONFIRMED}）。同じ種別の確認済みの署名があれば先に {@code SUPERSEDED} にして差し替え、それを要する
     * 開いている開放単位を評価し直す（成り立たなければ {@code AUTO_CLOSED(SIGN_OFF_REPLACED_INVALID)}）。
     */
    FeatureSignOff confirm(long id, int expectedVersion, FeatureEnablementActor actor);

    /** 未確認の署名を登録者以外が却下する（理由の code が必須）。 */
    void reject(long id, int expectedVersion, String reasonCode, FeatureEnablementActor actor);

    /** 未確認の署名を登録者が取り下げる。 */
    void withdraw(long id, int expectedVersion, FeatureEnablementActor actor);

    /** 確認済みの署名を取り消す（理由の code が必須。それを要する開いている開放単位を {@code AUTO_CLOSED(SIGN_OFF_REVOKED)}）。 */
    void revoke(long id, int expectedVersion, String reasonCode, FeatureEnablementActor actor);

    /** 証拠の類の確認済みの署名の延長を申請する（申請者以外の管理者の承認で同じ行の失効日を延ばす）。 */
    FeatureEnablementRequest requestExtension(long id, LocalDate newExpiresOn, String reason, FeatureEnablementActor actor);

    /** 一覧（氏名を含めない）。 */
    List<Summary> list(FeatureEnablementActor actor);

    /** 詳細（氏名を含む。管理者だけ）。 */
    FeatureSignOff detail(long id, FeatureEnablementActor actor);

    /** 文書を専用の経路で取得する（大きさと SHA-256 を照合し、取得を監査する）。 */
    Document document(long id, FeatureEnablementActor actor);
}
