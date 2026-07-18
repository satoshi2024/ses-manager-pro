# Bug Hunt Round 2 — P1〜P7 新機能の追加調査（customer-feature-proposals）

対象: ブランチ `claude/customer-feature-proposals-review-xyo84v`（Q1〜Q5 対応＋故障修正 `66b2bc1` 適用後）
に対する第2次バグ調査（2026-07-18、新機能の内部実装を重点探索）。
**本ドキュメントは指摘の記録のみで、修正は未実施**。対応セッションは修正とテストを同一コミットに含め、
完了した節見出しへ `[対応済み]` を追記すること。

**最重要**: B1 により**現在テストが赤**（`mvn test`: 570件中 failures 1、本環境で再現済み）。
B1 を最初に修正すること。

---

## B1.【高・ビルド赤】故障修正コミットが i18n 4言語同期を破りテストが失敗している

- **場所**: `messages.properties`（`66b2bc1` で追加された `menu.workRecord` / `menu.invoice` /
  `menu.payroll` / `common.btn.edit` の4キー）
- **内容**: 4キーが **ja にのみ**追加され、en/zh_CN/ko に無い。`MessageBundleConsistencyTest` が
  `EN keys mismatch. Missing: [menu.payroll, common.btn.edit, menu.workRecord, menu.invoice]` で失敗
  （本環境で再現: 570 tests / **failures 1**）。「各修正で `mvn test` 全緑を確認」の運用規則違反でもある。
- **修正**: 4キーを en/zh_CN/ko へ追加（サイドバーのセクション見出し・編集ボタンの訳）。
- **テスト**: `MessageBundleConsistencyTest` グリーン復帰＝完了条件。

## B2.【中高・P1】差戻し→再提出の通知が永久デデュープされ承認者に届かない

- **場所**: `WorkRecordServiceImpl.submit`（dedupeKey `"timesheet-submitted-" + record.getId()`）
- **内容**: 通知の dedupe_key は UNIQUE＋`DuplicateKeyException` 握り潰しの**永久冪等**。
  submit のキーは work_record 固定のため、**差戻し後に要員が修正して再提出しても2回目以降の
  通知は挿入されず、承認者は再提出に気づけない**（承認フローが無音で停滞する）。
  `reject` 側は `System.currentTimeMillis()` 付きで毎回通知される——非対称。
- **修正**: submit のキーに再提出ごとに変わる要素を含める（例:
  `"timesheet-submitted-" + record.getId() + "-" + System.currentTimeMillis()`、reject と同型）。
  提出のたびに通知するのが業務的に正しい（同一提出の二重送信は UI 上起きにくく、
  起きても実害がない）。
- **テスト**: `WorkRecordServiceImplTest` — submit→reject→submit で publish が2回呼ばれ、
  dedupeKey が異なることを verify。

## B3.【中・P1】TIMESHEET_* 通知が menu_key NULL で全ロール可視（差戻しコメントの漏えい）

- **場所**: `NotificationServiceImpl.menuKeyForType`（`TIMESHEET_SUBMITTED/REJECTED` の case なし）
  ＋ `NotificationMapper`（`n.menu_key IS NULL` は**全員可視**）
- **内容**: submit/reject は 5-arg `publish` を使うため menuKey は `menuKeyForType` で解決されるが、
  TIMESHEET 系の case が無く NULL → 可視性フィルタを素通りして**全ロールに表示**される:
  - (a) 承認者向け `TIMESHEET_SUBMITTED` が全要員のベルにも出る（リンク先 `/work-record/list` は
    要員には 403——lifecycle S3 で潰したはずの「開けない通知」の再来）
  - (b) `TIMESHEET_REJECTED` は**差戻しコメント本文が message に含まれ、他の要員全員に見える**
    （人事評価に類する内容が書かれうるため情報漏えいとして扱う）
- **修正**: (a) SUBMITTED は 6-arg publish で `menuKey='work-record'`（承認側メニュー保持ロールのみ）。
  (b) REJECTED は `menuKey='my-timesheet'` としても**通知はロール単位のため全要員に見える**——
  コメント本文を message から外し「勤怠が差し戻されました（対象月）」のみにする
  （コメントは record 側で本人が参照できる。B4 参照）。通知の個人宛先化は将来課題として注記。
- **テスト**: menuKeyForType の2 case 追加検証＋REJECTED の message にコメントが含まれないこと。

## B4.【中低・P1】reject が要員の備考（remarks）を差戻しコメントで上書きする

- **場所**: `WorkRecordServiceImpl.reject`（`record.setRemarks(comment)`）
- **内容**: 実績の `remarks` は要員/管理部が入力した業務メモ。差戻しコメントで**無警告に上書き**され
  元の備考が失われる。さらに再提出後も差戻しコメントが備考として残り続ける。
- **修正**: `t_work_record` に `reject_comment` カラムを足すのが正道だが、マイグレーションを避ける
  最小案として「`remarks` を触らず、コメントは通知＋（B3 対応後は）マイ勤怠画面の差戻し帯へ
  API レスポンスで返す」方式でも可。どちらを採るか実装時に判断し本節へ記録。
- **テスト**: reject 後も既存 remarks が不変であること。

## B5.【低・P1】通知 message の JSON 組み立てでコメント未エスケープ

- **場所**: `WorkRecordServiceImpl.reject`（`"[\"…\", \"" + comment + "\"]"` の文字列連結）
- **内容**: コメントに `"` や `\` が含まれると i18n JSON 配列が壊れ、ベルに生文字列が表示される。
  既存の publish 呼び出しは数値・契約番号など安全な値のみだったが、**自由入力を埋め込むのは本件が初**。
- **修正**: B3/B4 でコメントを message から外せば同時に解消（外さない場合は Jackson で配列を
  シリアライズして組み立てる共通ヘルパを `NotificationService` に追加）。

## B6.【中低・P7】DataScope の設計逸脱2点（キャッシュなし・営業活動由来の顧客が不可視）

- **場所**: `DataScopeServiceImpl`
- **内容**:
  1. design 2章の「リクエストスコープキャッシュ」が未実装（クラスコメントで明言）。
     見積一覧は 1リクエストで customer/engineer 集合を各2回（一覧＋件数系で再計算）、
     `computeCustomerIds` は内部で `computeProposalIds`→`computeEngineerIds` を連鎖クエリする。
     数百件規模なら耐えるが、設計が求めた `@RequestScope` Bean 化が漏れている。
  2. `computeCustomerIds` が **「営業活動の担当が自分」由来の顧客を含まない**
     （requirements の担当定義 (c) からの逸脱）。契約も提案も無いが営業活動でフォロー中の顧客が、
     スコープ有効時に**担当営業本人から見えなくなる**（顧客一覧・営業活動画面から消える）。
- **修正**: 1. `@RequestScope` のキャッシュ Bean 化（design どおり）。
  2. `t_sales_activity` の担当（assignee カラムは実装時に確認）で顧客IDを補完する。
- **テスト**: 営業活動のみ紐づく顧客が本人に見える／他営業に見えないこと。

## B7.【低・P1・既知制限化でも可】日跨ぎ勤務（夜勤）が入力できない

- **場所**: `WorkRecordServiceImpl.computeWorkedHours`（start > end で負分数→エラー）
- **内容**: 22:00→翌5:00 のような夜勤は `dailyInvalidTime` で拒否される。SES では夜間保守
  シフトが現実にあるため、いずれ要望化する。
- **修正**（いずれか）: (a) end < start のとき翌日跨ぎとして +24h で計算（上限24hガードは既存）。
  (b) 対応しない場合、マイ勤怠画面に「日跨ぎは2行に分けて入力」の注記を出し、
  本節を既知制限として requirements に追記する。

## B8.【低・66b2bc1】PDF フォントに Windows 絶対パスがハードコード追加された

- **場所**: `InvoicePdfServiceImpl` / `QuotationPdfServiceImpl` / `TimesheetPdfServiceImpl`
  （`C:/Windows/Fonts/msgothic.ttc,0` 等のフォールバック追加）
- **内容**: フォールバック候補としては無害だが、(1) フォント解決は `PdfProperties` に寄せる既存設計から
  逸脱して3ファイルへ重複ハードコード、(2) Linux 本番では常に無意味な候補、(3) 修正者が Windows で
  Demo した痕跡であり **Linux 実行系での PDF 出力（日本語フォント解決）が未検証**の可能性を示す。
- **修正**: 候補リストを `PdfProperties`（application.yml 設定）へ移し3ファイルの重複を解消。
  Linux 環境では Noto Sans CJK 等のパスを設定で与える運用を README/設定コメントに明記。

---

## 今回のレビューで問題なしと確認した領域（記録のみ）

- Q1〜Q5 の修正内容（クエリレベルIN・Export集合再利用・見積の顧客∪要員スコープ・unsent 列・
  カンバン導線・リゾルバ warn）はすべて指摘どおり正しく実装されている。
- P1 の本人スコープ（`assertOwnedWorkRecord`→契約経由の所有検証）・日次⇔月次合計の連動・
  新規作成時の期間/状態ガード適用・提出済/確定中の編集拒否。
- P2 の消込・エイジング（未送付別掲後）・P4 の受注後編集ガード・P6 の resolveFrom 現在単価再計算。
- 故障修正の ProjectApiController customerId 絞り込みと見積画面の顧客→案件カスケード。

## 進め方

- 推奨順序: **B1（ビルド赤の解消・最優先）→ B2 → B3 → B4/B5（同時に片付く）→ B6 → B7/B8（任意）**。
- すべて本ブランチ上で修正し、各修正で `mvn test` 全緑を確認して本ドキュメントへ
  `[対応済み]` を追記すること。**「テストを回してからコミット」の運用規則を厳守**
  （B1 はこの規則が破られた実例として発生した）。
