# 第6回監査 最適化・残バグ（O-01〜O-06）— 対応方法と期待効果

- 監査日: 2026-07-21
- 対象: `main@dd24526`（作業ツリーの X-01〜04 / M-01〜02 修正を含む）
- 対象範囲: これまで未審査の領域 — セキュリティ・ファイル処理、性能/最適化（N+1・全件ロード・クライアント負荷）、スケジューラ、AIルールマッチング、CSV/連携
- 方針: **新規のバグと最適化余地のみ**。過去監査（LOGIC/UI/R/N/X/M/B 系）で既出の指摘は除外。B系（別AIが対応中）とも重複させない
- 本書は**指摘のみ**（修正はしていない）。各項目は「対応方法」と「期待効果」に絞って記載

## 優先度

- **P2**: 既定設定で機能が誤動作する／実運用で問題が顕在化する。
- **P3**: 性能・保守性・堅牢性の改善。計画的に是正。

## 監査で「問題なし」を確認した領域（重複調査の防止）

精読の結果、以下は安全と確認した: ファイルアップロード（`FileStorageServiceImpl`：UUID名・拡張子/Content-Type検証・パストラバーサル二重チェック、`FileKind` は SVG/HTML を許可せず配信も安全な拡張子のみ）、CSVエクスポートの数式インジェクション（`CsvUtils.sanitizeForSpreadsheet` で CWE-1236 対策済み）、アカウントロック（`AccountLockServiceImpl`：条件付きDB更新で lost update 防止・ロック期限処理あり）、**孤児ファイル清理**（`FileCleanupServiceImpl`：契約書PDFは `uploads/contracts/{id}/` サブディレクトリ保存で、`Files.list` の非再帰＋`isRegularFile` フィルタにより走査対象外＝誤削除しない）、`RuleMatchingServiceImpl` のスキル突合（スキル・タグを一括ロードしてin-memory照合＝N+1なし）。

---

## O-01 【P2】AIルールマッチングの単価採点が単位不一致で常に0点になり、スコア・順位が歪む

- 対象:
  - `src/main/java/com/ses/service/ai/impl/RuleMatchingServiceImpl.java:82-83`（案件単価を `÷10000` で万円化）・`:87`（要員 `expectedUnitPrice` を**変換せず**そのまま渡す）
  - `:144-145,149`（`findMatchingEngineers` 側も同一パターン）
  - `src/main/java/com/ses/service/ai/MatchScoreCalculator.java:24-25,84-94`（`priceMin/priceMax/expectedPrice` を**万円前提**で受け、ペナルティは `diff.intValue() * 2` の小整数スケール）
- 現象・影響（**既定 `ai.provider=rule`（`application.yml:141`）で有効**。`RuleMatchingServiceImpl` が本番Beanのため要員詳細「AIマッチング実行」・Bench一覧「AIマッチング」から実際に走る）:
  - 単価は V27（money-flow-consistency）で全カラム**円**保存に確定済み（`t_engineer.expected_unit_price` / `t_project.unit_price_min|max` とも円）。
  - `RuleMatchingServiceImpl` は**案件単価だけ `÷10000` して万円化**し、要員の希望単価は円のまま `MatchScoreCalculator` へ渡す。calculator は両者を万円と仮定して比較するため、例: 案件下限=60(万円)・希望単価=650000(円未変換) では常に上限超過側へ落ち、`penalty = (650000 - 80) * 2` で `priceScore = max(0, 20 - 巨大値) = 0`。
  - 結果、**20点満点の単価適合度が事実上すべて0点**になり、総合スコア（100点）と上位10件の順位が単価を無視した歪んだ結果になる。マッチング機能の中核である優先順位付けが信頼できない。
- 対応方法:
  1. 最小修正: `RuleMatchingServiceImpl` の2箇所（`:87`・`:149`）で、要員希望単価も案件と同じく万円へ変換して渡す（`engineer.getExpectedUnitPrice().divide(new BigDecimal("10000"), 0, HALF_UP)`。null ガード必須）。
  2. 恒久的には calculator を「円で受けて内部でスケールする」設計へ改め、`MatchScoreCalculator` の Javadoc（`:24-25` の「万円」）と実データ（円）を一致させる。ペナルティ係数 `* 2` も円基準に見直す（現状の小整数前提は万円専用）。
- 期待効果: 希望単価が案件レンジ内なら満点、レンジ外は緩やかに減点され、単価適合が総合スコアへ正しく寄与する。マッチング上位が単価も加味した妥当な順位になる。
- 追加すべきテスト: 希望単価がレンジ内/下限割れ/上限超過の各ケースで `priceScore` が 20/部分/減点になることを検証（円入力で。現状は常に0または10になる）。

## O-02 【P3】要員CSV取込がENUM値を検証せず、失敗時に生の例外メッセージを利用者へ露出する

- 対象: `src/main/java/com/ses/service/csv/impl/EngineerCsvServiceImpl.java:101-121`（`toEngineer`：`status`/`gender`/`employmentType` を検証なしで設定）・`:94-96`（`catch (Exception ex) → result.addError(lineNo, "取込に失敗しました: " + ex.getMessage())`）
- 現象・影響:
  - `Engineer` エンティティの Bean Validation は `fullName` 必須と `experienceYears >= 0` のみ（`entity/Engineer.java`）。`status`（DB ENUM `稼動中/退場予定/Bench/提案中`）・`gender`・`employmentType` には検証注釈が無く、CSVの任意文字列がそのまま `save` へ渡る。
  - MySQL 8 strict モードでは不正 ENUM 値で INSERT が失敗し、その **SQLException メッセージ（列名・制約・SQL断片を含みうる）が `ex.getMessage()` 経由で取込結果に表示**される（情報漏えい＋利用者に不可解なエラー）。非strict環境では空値に丸められ静かにデータ品質が劣化する。
- 対応方法:
  1. `toEngineer` で ENUM 列を許可値集合（`EnumMappings` 由来）で事前検証し、不正なら行単位で `error.csv.invalidEnumValue` 等のキーメッセージを付す。
  2. `catch (Exception ex)` の分岐は `ex.getMessage()` を利用者へ返さず、汎用の取込失敗キー（ログには詳細を残す）に置換する。数値形式エラーも既存の日本語直書きをキー化する。
- 期待効果: 不正な区分値が取込前に行単位で弾かれ、利用者にはロケール対応の分かりやすいメッセージのみが返る。DB例外メッセージが外部へ漏れない。

## O-03 【P3】契約単価同期が全契約を単一トランザクション＋行ロックで処理し、起動毎にも走る

- 対象: `src/main/java/com/ses/service/billing/ContractPriceSyncService.java:32-78`
- 現象・影響:
  - `syncCurrentPrices` は `@Transactional` かつループ内で `contractMapper.selectByIdForUpdate`（`:53`）を使い、**改定履歴を持つ全契約の行ロックを1トランザクション内で保持したまま**同期する。契約数が増えるとロック保持時間が長くなり、深夜バッチ時刻に走る他の更新と競合・デッドロックの余地が生じる。
  - `@Scheduled(日次)` に加え `@EventListener(ApplicationReadyEvent.class)` が付いており、**アプリ起動のたびに全同期が走る**（冪等ではあるが毎回の全走査は無駄）。
- 対応方法:
  1. 契約単位のトランザクションへ分割する（ID一覧を先に取得し、1契約ずつ短いトランザクションで `selectByIdForUpdate → updatePriceOnly`）。1件の失敗が全体を巻き戻さず、ロック保持も短くなる。
  2. 起動時実行（`ApplicationReadyEvent`）の要否を再検討する。必要なら非同期化・件数制限や、日次スケジュールのみへ寄せる。
- 期待効果: 契約件数が増えてもロック競合が起きにくく、1件の異常が全同期を止めない。起動時間・起動時負荷への影響が減る。

## O-04 【P3】ダッシュボード・営業成績が毎回「全契約（+全要員）」をメモリロードしてJava側集計する

- 対象:
  - `src/main/java/com/ses/service/impl/DashboardServiceImpl.java:77`（`contractMapper.selectList(new QueryWrapper<>())`＝全契約）・`:171`（全要員）
  - `src/main/java/com/ses/service/impl/SalesPerformanceServiceImpl.java:66`（全契約）
- 現象・影響: ダッシュボード表示・営業成績表示のたびに、契約テーブル（および要員テーブル）を**全件メモリに載せて**月次集計・稼働率・成績をJavaで計算する。契約が数千件規模になると1リクエストごとのメモリ・GC負荷とレスポンス時間が線形に悪化する。共通口径サービスへ渡す設計自体は妥当だが、母集団の絞り込みがSQL側に無い。
- 対応方法: 少なくとも対象期間・ステータスで母集団を絞る（例: 集計対象月に重なりうる `status IN ('稼動中','終了','解約')` かつ `start_date <= 期間末` の契約のみ取得）。恒久的には月次売上・粗利をSQL集計（GROUP BY）へ寄せるか、確定済み月のKPIをスナップショット保存して再計算を避ける。
- 期待効果: 契約件数の増加に対してダッシュボード/営業成績の応答時間とメモリ使用量が概ね一定に近づく。

## O-05 【P3】要員詳細等が起動のたびに駅名JSON（約666KB）を全展開し、巨大なdatalistを構築する

- 対象: `src/main/resources/static/js/modules/engineer.js:88-120`（`loadAllStations`：`/data/station_names.json` を取得し `<datalist>` へ全option展開＋`window.stationIndex` 構築）、データ実体 `src/main/resources/static/data/station_names.json`（約666KB）
- 現象・影響: 要員一覧を開くたびに 666KB の駅名JSONをダウンロードし、数千件の `<option>` を持つ datalist と駅名→都道府県インデックスをクライアントで毎回構築する。低速回線・低スペック端末（特にモバイル）で初期描画が重くなる。一覧表示だけの利用でも必ず発生する。
- 対応方法:
  1. 駅名補完は**新規/編集モーダルを開いた時点で遅延ロード**する（一覧表示だけでは読み込まない）。取得結果はメモリ/`sessionStorage` にキャッシュして再取得を避ける。
  2. 恒久的にはサーバサイドの駅名サジェストAPI（`/api/stations?keyword=` の前方一致上位N件）へ切り替え、巨大JSONの全展開自体をやめる。
- 期待効果: 要員一覧の初期表示から駅名JSONの読み込みが外れ、初回描画が軽くなる。モーダル利用時のみ必要分を取得する。

## O-06 【P3】一括督促が `@Transactional` 無しで `FOR UPDATE` を使い、行ロックが直列化に効かない

- 対象: `src/main/java/com/ses/service/impl/InvoiceServiceImpl.java:547-559`（`sendReminders`：メソッドに `@Transactional` が無い状態で行内 `selectOne(... .last("FOR UPDATE"))` を実行）
- 現象・影響: `sendReminders` はクラス/メソッドに `@Transactional` が付いておらず、autocommit 下では `FOR UPDATE` の行ロックが SELECT 文の完了と同時に解放される。つまり**意図した「請求書ごとの直列化」が成立しない**（同一請求書への同時督促を防げない）。加えて請求書ごとに `selectOne`＋`customerMapper.selectById`＋`sumPaid`（さらに内部クエリ）で N+1 になる（件数上限のあるユーザー操作のため影響は限定的）。
- 対応方法:
  1. ロックで直列化する意図があるなら、行単位の短いトランザクション（`REQUIRES_NEW`）で `FOR UPDATE`→送信記録を囲む。または重複送信の抑止は既存の配信記録（`t_mail_delivery`）のユニーク制約／冪等キーで担保し、`FOR UPDATE` を外す。
  2. N+1 は対象請求書・顧客・入金合計をまとめて取得してMap参照へ変更する。
- 期待効果: 同一請求書への同時督促が確実に一方のみになる（または冪等で二重送信されない）。督促対象が多くてもクエリ数が抑えられる。

---

## 補足（さらに低優先・任意）

- `FileApiController.download`（`:42-53`）は認証済みなら**任意の storedName を取得できる**（データスコープ検査なし）。storedName はランダムUUIDで推測困難なため実害は低いが、要員写真URL等は詳細APIで露出するため、スコープ権限運用時は所有者チェックの追加を検討。
- `ContractDocumentServiceImpl`（`impl` 全体）が1メソッド1行の高密度スタイルで、PDF化が `html.replaceAll("<[^>]*>"," ")` の素朴なタグ除去に留まる。機能バグではないが、可読性・PDF体裁の観点で整形余地がある。
- `messages*.properties` に未使用・重複キーがないか、および JS/テンプレートが参照する全キーの定義有無を突合する lint をCIへ入れると、M-07（`invoice.btn.paymentHistory` 未定義）系の再発を機械的に防げる。
