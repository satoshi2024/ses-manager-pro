# Implementation Plan — JP PINTデジタルインボイス

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T102〜T107はL0〜L3の定向test・直接回帰、T108でL4全量を実行する。
> canonical model/schema変更はL3、provider sandbox全体受入はMで行う。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md`（特に§7 外部連携）を実装前に読む。
> HTTP/job/error/idempotencyの基盤は accounting spec を再利用する。
> 時間/金額/scope/状態の判断は `design.md` §5「決定表」を正とする。
>
> **Migration**: 本specの予約番号は **V87**。accounting(V86)のmerge後に着手する。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。

- [ ] 0. G5/provider/spec version spike
  - **Objective**: providerの契約状況・API・webhook・validator・test participantと、
    使用するJP PINT specification versionが確定する。
    以降の実装が「どのversionのどのprofileで送るか」を推測せずに済む状態にする。
  - **成果物**: provider契約/API/webhook/validator/test participant/spec version/料金/SLA。
  - **Demo**: 契約済みならprovider sandbox送受信、未契約なら公式fixture/mockの証跡とB1/B2/MをPASSにしないblocker記録。
  - **実装ガイダンス**: production codeを変更しない。
    **実装開始時にデジタル庁の最新版JP PINT versionを再確認**する（前提節）。
    **無検証の自動upgradeを禁止**し、使用versionを送信runへ保存する方針を明記する。
    PDF請求を廃止せず、顧客ごとのdelivery preferenceを持つ前提を確認する。
  - **テスト要件**: L0。spec versionと確認日が記録されていること、
    未契約の場合にB1/B2/MのPASSを止めるblockerが明記されていること、`git diff --check` exit 0。

- [ ] F1. participant/digital invoice/event DDL
  - **Objective**: 法人/顧客のPeppol participant IDを検証状態付きで管理でき、
    未検証の宛先へは送信できない。送受信のstatusとeventが記録され、
    同じproviderイベントが二重に処理されない。
  - **実装ガイダンス**: **V87**/V1/H2(`sql/schema-jp-pint-h2.sql`)/MySQL smoke、state/idempotency。
    `provider_event_id`にUNIQUE。`(invoice_id, direction, specification_version)`にUNIQUE（design §5.4）。
    **`verified_at IS NULL`の宛先へ送信しない**（design §5.1）。
    `t_digital_invoice.invoice_id IS NULL`は**受信invoice**を表す業務値。`direction`と併せて判定する。
  - **テスト要件**: L1〜L3。participant unique、status遷移、
    **event順序（古いeventで終端statusを巻き戻さないこと）**、
    `verified_at IS NULL`宛先への送信拒否、受信/送信の区別。
  - **Demo**: 未検証participantへの送信が拒否されることを確認。
    古いeventを後から流して`delivered`が巻き戻らないことを確認。

- [ ] F2. CanonicalInvoice/renderer/validator
  - **Objective**: 既存請求からJP PINT XMLが生成され、schema/business ruleのvalidatorに通る。
    金額が既存invoiceと1円も食い違わず、合計が合わないXMLは送信前に拒否される。
    不正なXMLでXXEが発火しない。
  - **実装ガイダンス**: version adapter、XML security、validation report archive。
    **既存`Invoice`/`InvoiceItem`/税snapshotが唯一の正。JP PINT側で再計算して上書きしない**（design §5.2、R2.4）。
    検算`line合計 + 税 + rounding = total`が合わなければ**送信拒否**。丸めて辻褄を合わせない。
    XML libraryは**XXE無効・external entity禁止・DTD禁止**（design §5.4）。
  - **テスト要件**: L1〜L3。official fixture/golden XML照合、rounding、
    **検算NG時の送信拒否**、**XXE fixtureで外部entityが解決されないこと**、spec version切替。
  - **Demo**: 既存invoiceをvalidatorへ通す。合計が合わないinvoiceで送信が拒否されることを確認。

- [ ] B1. provider送信/status/webhook
  - **Objective**: 請求がprovider経由で送信され、同じinvoiceを再送してもmessageが1件しかできない。
    webhookの署名が検証され、偽造・順序逆転・重複が安全に処理される。
  - **実装ガイダンス**: accounting jobの基盤を再利用、participant verify、署名、fallback。
    **署名検証はraw bodyに対して行う**（design §5.4）。parse後のオブジェクトで検証しない。
    署名不正は`signature_valid=false`で記録し**状態遷移させない**（fail-closed）。
    古いeventで終端statusを巻き戻さない。
  - **テスト要件**: L2〜L3。retry、**同一invoiceの再送でmessage 1件**、
    **偽造署名の拒否**、out-of-order event、重複webhook、PDF fallback。
  - **Demo**: sandbox送信→delivered。偽造署名のwebhookを送って状態が変わらないことを確認。

- [ ] A1. 設定/送信/状態UI
  - **Objective**: 顧客ごとにPDF/email/Peppolの送付方法を設定でき、
    送信前にvalidation結果が見え、送信後の状態とXML/receiptへ辿れる。
    participant未検証の顧客は送信対象に選べない。
  - **実装ガイダンス**: 顧客preference、validation、status、XML/receipt link。
    送信状態の母集団は**元invoiceのscope**に従う（design §5.3）。digital invoice側で別ACLを作らない。
    営業には送信済/未送信の別のみを見せ、XML本文は見せない。
  - **テスト要件**: L2〜L3。permission、**participant未検証顧客の送信不可**、
    field mask（営業からXML本文が見えないこと）、mobile 390px。
  - **Demo**: PDF顧客とPeppol顧客を別送信。participant未検証の顧客がPeppol送信対象に出ないことを確認。

- [ ] B2. 受信review
  - **Objective**: 受信したinvoiceの原本XML/PDFがarchiveへ保存され、
    BP/注文/契約の候補へ照合されたうえでreview queueに入る。
    人が確定するまで仕入登録や支払確定が行われない。重複受信が検知される。
  - **実装ガイダンス**: secure parse/archive/match/review→purchase候補。
    **受信invoiceを自動で支払確定しない**（design §5.4、R5）。必ずreviewを経由する。
    重複検知は`message_id`/supplier invoice number/payload hashの3系統（R3.4）。
    受信XMLは信頼できない入力として扱う。
  - **テスト要件**: L2〜L3。duplicate検知（3系統それぞれ）、不正XMLの拒否、
    照合ロジック、**review確定前に仕入/支払が作られないこと**。
  - **Demo**: 受信invoiceをBP支払候補へ。review未確定の状態で支払が作られないことを確認。

- [ ] M. provider受入/回帰
  - **Objective**: providerの公式conformance testに通り、送受信のend-to-endと障害復旧が確認できる。
    既存のinvoice/PDF送付機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、provider official conformance、
    既存invoice/PDF/accounting回帰、Node/JS syntax、desktop/390px browser Demo、`git diff --check`。
  - **Demo**: end-to-end送受信と障害復旧。PDF顧客の請求が従来どおり送れることを提示。
  - **実装ガイダンス**: `design.md`§5決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
    provider契約/sandbox未取得の場合、B1/B2/Mを**PASSにしない**。本番releaseのgateとして別管理する。
