-- ============================================================
-- V201: NF07 F1（V190）の状態・区分の列に CHECK を足す（W3-14 review P3-8、RB-NF07-HOLD。b-15）。
--
-- 値の集合はコードの enum と同じにする（PrivacyStatusCheckConstraintTest が本 script と H2 変種
-- schema-privacy-status-check-h2.sql の値を enum と照合する。enum に値を足すときは新しい migration で CHECK も広げる）:
--   - t_privacy_request.status          … PrivacyCaseState の全値。DSAR が今書くのは部分集合だが、署名後の処分（B1）の状態も
--                                           同じ列に入るため enum 全体を許す
--   - t_privacy_request.channel         … PrivacyDsarVocabulary.Channel
--   - t_privacy_request.delivery_method … PrivacyDsarVocabulary.DeliveryMethod。交付の記録の前は NULL
--   - t_legal_hold.hold_kind            … 'UNKNOWN' だけ。hold の種類は D-05 の署名で決まり、application は今 'UNKNOWN' しか
--                                           書かない（PrivacyHoldProjectionStore.insertHold）。種類が決まったら、その値を書く
--                                           変更と同じ batch で新しい migration により値を足す
-- 表は utf8mb4_bin のため、大文字小文字の違う値も拒否する（UNKNOWN の fail-closed を綴り違いで迂回させない）。ただし
-- utf8mb4_bin は PAD SPACE の照合順序で、末尾の空白だけが違う値は等しいとみなされ通る（先頭の空白は拒否する）。application は
-- enum の名前をそのまま書き、読む側も厳密に照合する（PrivacyDsarVocabulary.parse）ため、末尾に空白のある値は解釈できない値になる。
--
-- CHECK を置かない列（理由）:
--   - m_pii_data_element.owner_state・data_class: 値の集合を決める enum がコードに無く、行は既定値 'UNKNOWN' のままでしか
--     書かれない（owner の確定と分類の writer は未実装）。値の集合を決める batch が、その writer と同じ変更で CHECK を足す。
--   - t_legal_hold.reason_code・t_privacy_action.blocked_reason・t_privacy_request_subject_link.resolution_code: 状態ではなく
--     理由・結果のコードで、行の種別ごとに語彙が違う（例: blocked_reason は検索の拒否理由・手動対応の結果・外れた承認の理由を持つ）。
--     列ごとの1つの集合で縛れないため、application の enum 照合（PrivacyDsarVocabulary.parse 等）に任せる。
--   - request.approved_by = package.approved_by: CHECK は他の表を参照できない（MySQL は CHECK に副問合せ・他表の列を許さない）。
--     既存の複合 FK（fk_privacy_request_approved_package）に approved_by を足す方法は、request を更新する時点で package の行に
--     承認者が書かれていることを要する（MySQL は FK を文ごとに即時検査し、遅延できない）。今の承認は request の行の CAS を先に
--     行い、それを二重承認の直列化点にしている（後の承認は 409。PrivacyRequestApprovalMySqlTest が実 MySQL で固定）。
--     書込み順と直列化点を変える変更になるため、本 migration では DB で保証しない。承認（PrivacyExportPackageService.approve）が
--     同じ transaction で両方に同じ承認者を書き、両方の更新を承認者≠作成者の条件付きにする。実 MySQL での一致は
--     PrivacyRequestApprovalMySqlTest（承認の後に request と package の承認者が同じ）が確かめる。
--
-- 本番は新規導入（表は空）。既存の行が集合の外の値を持つ DB では適用が失敗する（application が書く値は全て集合の内側）。
-- ============================================================

ALTER TABLE t_privacy_request
  ADD CONSTRAINT chk_privacy_request_status
    CHECK (status IN ('REQUESTED', 'IDENTITY_VERIFIED', 'SCOPED', 'PREVIEWED', 'APPROVAL_PENDING', 'APPROVED', 'CLAIMED',
                      'DISPATCH_AUTHORIZED', 'EXECUTING', 'CONFIRMED', 'BLOCKED', 'UNKNOWN', 'PARTIAL_FAILED', 'CANCELLED')),
  ADD CONSTRAINT chk_privacy_request_channel
    CHECK (channel IN ('EMAIL', 'POSTAL', 'WEB_FORM', 'PHONE', 'IN_PERSON', 'OTHER')),
  ADD CONSTRAINT chk_privacy_request_delivery_method
    CHECK (delivery_method IS NULL OR delivery_method IN ('EMAIL', 'POSTAL', 'IN_PERSON', 'SECURE_TRANSFER', 'OTHER'));

ALTER TABLE t_legal_hold
  ADD CONSTRAINT chk_legal_hold_kind CHECK (hold_kind IN ('UNKNOWN'));
