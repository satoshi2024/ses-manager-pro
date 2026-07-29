# Implementation Plan — 雇用勤怠・休暇・時間外労働

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T067〜T073はL0〜L3の定向test・直接回帰、T074でL4全量を実行する。
> calculator/締め/共通transaction変更はL3、昇格条件該当時だけ中間L4とする。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §5「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
> **design.md §5.2の時間外計算の境界定義（起算・期間単位・休日労働の扱い）を0で埋め、
> 空欄のままF2を開始しない。** 法定計算をコード上のその場判断で決めない。
>
> **Migration**: 本specの予約番号は **V71**。order(V69)のmerge後、dispatch(V70)と並行可。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。

- [ ] 0. G6/36協定/就業規則確認
  - **Objective**: 雇用勤怠の正が本システムであることがsource matrixとして確定し、
    **design.md §5.2の空欄（各上限の起算・期間単位・休日労働を含むか）が全行埋まる**。
    勤務区分・丸め・カレンダー・休暇種別・協定期間も確定し、F2が判断を持ち込まずに実装できる状態にする。
  - **成果物**: source of truth、勤務区分、丸め、カレンダー、休暇、協定期間/上限、**§5.2の完成した表**。
  - **Demo**: 本システムを正とするsource matrixのHR確認。法人別36協定/就業規則と外部社労士確認はM/本番gate。
  - **実装ガイダンス**: production codeを変更しない。
    `t_work_record_daily`（客先請求工数）と雇用勤怠が**別sourceである**境界を明文化する。
    法人別36協定の実物が未入手でも、公式の一般定義をbaselineとして表を埋め、
    **未確認である旨を明記**する。確認済みと偽らない。
  - **テスト要件**: L0。§5.2の表に空欄がないこと、各行に確定者と根拠が付いていること、
    source matrixが既存work recordとの境界を含むこと、`git diff --check` exit 0。

- [ ] F1. calendar/attendance/month/leave/agreement DDL
  - **Objective**: 社員の日別出退勤・休憩・勤務区分を分単位で登録でき、法人/組織/個人別の勤務カレンダーを持てる。
    月次状態が入力中→提出済→承認済→締め済で進み、締め済みは管理承認なしに変更できない。
  - **実装ガイダンス**: **V71**/V1/H2(`sql/schema-attendance-h2.sql`)/MySQL smoke、
    **分の整数モデル**（浮動小数を使わない、design §1）、scope。
    `(source, source_external_id)`にUNIQUE。
    `scheduled_minutes IS NULL`（所定日でない）と`= 0`（所定日だが0分）を区別する（design §5.1）。
  - **テスト要件**: L1〜L3。期間/unique、締め済みの変更拒否、休暇との整合、
    `scheduled_minutes`のNULLと0の区別、外部sourceの重複登録拒否。
  - **Demo**: 社員の1週間分の勤怠を登録し月次集計が出ることを確認。
    締め済み月を更新しようとして拒否されることを確認。

- [ ] F2. 集計/時間外calculator
  - **Objective**: 月45h・年360h・特別条項720h・月100h未満・2〜6か月平均80h・45h超過月数が計算され、
    §5.2で確定した定義どおりの境界で警告が出る。跨夜・休日・休憩が正しく扱われる。
  - **実装ガイダンス**: official boundary fixtureをtest resource JSONへ保存（design §2）。
    **rolling平均は`n=2..6`のすべてを判定**する（design §5.2）。1つでも超過なら警告。
    休日労働を含む/含まないをruleごとに明示する。丸めなし、分単位。
    JST日界、跨夜は**始業日の勤務**として計上。
  - **テスト要件**: L1〜L3。**各上限の`limit-1/limit/limit+1`の3点fixture**、
    跨夜/休日/休憩、協定期間の途中変更で期間が分割されること、
    協定年度をまたぐrolling平均、月中入退職を按分しないこと。
  - **Demo**: fixture結果をHRへ提示。45h/360h/80h平均それぞれの境界値ちょうどで判定が変わることを確認。

- [ ] A1. 本人/管理画面と月次状態
  - **Objective**: 本人が自分の勤怠をcalendarで入力して提出でき、上長が差戻し・承認、HRが締めを行える。
    本人は自分の分だけ、上長は配下だけ、HRは法人分が見える。
  - **実装ガイダンス**: calendar、入力/提出/差戻し/承認/締め。
    **HRは法人scope、マネージャーは組織scope ∩ DataScope、本人は自己のみ**（design §5.3）。
    **営業には勤怠scopeを与えない**（design §5.3の明示的逸脱）。
  - **テスト要件**: L2〜L3。本人/上長/HRのscope分離、状態CAS、
    **営業が勤怠APIへアクセスして拒否されること**、mobile 390px。
  - **Demo**: 本人提出→上長差戻し→再提出。営業ログインで勤怠画面/APIへ到達できないことを確認。

- [ ] A2. 休暇/approval統合
  - **Objective**: 有給・半休・時間休・代休・欠勤・特別休暇を申請でき、上長/HRの承認後にcalendarへ反映される。
    客先報告が必要な休暇は営業へtask/通知が作られる。
  - **実装ガイダンス**: 申請、残数/外部参照、営業通知。approval specのengineを利用する。
    **休暇残数が外部正の場合は参照表示のみで、残数不足でも申請を拒否しない**（design §5.4、R2.2）。
    本システム正の場合のみ残数CASで不足を拒否する。
  - **テスト要件**: L2〜L3。半休/時間休の分計算、残数不足時の挙動（外部正/本システム正の両方）、
    期間重複の拒否、代理承認、営業への通知が休暇種別で分岐すること。
  - **Demo**: 休暇申請→承認→calendar反映。外部正モードで残数不足でも申請できることを確認。

- [ ] B1. freee/provider sync
  - **Objective**: 承認/締め済みの雇用勤怠をfreeeへ冪等送信またはCSV出力でき、
    外部データはread-onlyの照合に使われる。締め済み月が外部から上書きされない。
  - **実装ガイダンス**: 本システムを正とし、外部dataはread-only照合（G6決定）。
    cursor/冪等/error UI。OAuth/refreshはaccounting adapterと共通基盤。
    **外部が締め済み・承認済みを上書きしようとしたら拒否してfindingへ**（design §5.4）。
    黙って上書きも、黙って無視もしない。timezoneはtenant設定（design §3）。
  - **テスト要件**: L2〜L3。401 refresh 1回/429 backoff/timeout、重複送信で外部1件、
    部分失敗、**締め済み月への外部更新が拒否されfindingになること**、秘密の非ログ出力。
  - **Demo**: sandbox syncと再実行。締め済み月に外部更新を流して拒否されることを確認。

- [ ] B2. 客先工数差異/通知
  - **Objective**: 雇用勤怠合計と契約工数の差が月次で表示され、閾値超過を理由付きで確認できる。
    差異を確認しても請求金額は変わらない。
  - **実装ガイダンス**: 月次比較、理由確認、warning/escalation。
    **read-only DTOとし、`WorkRecordServiceImpl`の金額計算・請求ロジックへ接続しない**（design §5.4、R4.2）。
  - **テスト要件**: L2〜L3。**差異確認の前後で請求金額が不変であること**、
    scope、通知の冪等、閾値の境界。
  - **Demo**: 8h差異を確認して理由保存。理由保存の前後で請求金額が同じことをSQLで提示。

- [ ] M. 回帰/法務受入
  - **Objective**: 6か月rollingのfixtureが期待どおりで、月次が一気通貫で動く。
    既存の給与・work record・請求機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    給与・work record回帰、境界fixture全件、Node/JS syntax、desktop/390px browser Demo、`git diff --check`。
  - **Demo**: 6か月rolling fixtureと月次全通し。客先工数を編集しても雇用勤怠が変わらないことを提示。
  - **実装ガイダンス**: `design.md`§5決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
    法人別36協定/就業規則の確認と外部社労士Reviewは本taskのPASS条件ではなく、**本番releaseのgate**として別管理する。
