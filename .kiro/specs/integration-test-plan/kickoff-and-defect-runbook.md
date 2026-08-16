# 結合テスト キックオフ・欠陥対応 Runbook

本書は、`schedule-and-resources.md` の Wave と Defect gate を実行現場で回すための操作手順書である。全フェーズの開工チェック（対話形式）、欠陥の対応 SOP、発見者と対応者の役割分担を定義する。計画書の数式・gate は本書の正本であり、本書は現場の「誰が・いつ・何を言うか」を定める。

## 1. 役割（RACI の原則）

| 役割 | 発見時 | Triage | 修正 | 回帰 | クローズ | Waiver |
|---|---|---|---|---|---|---|
| 発見者（QA engineer / SDET / 誰でも） | **記録・証跡・再現情報の保存** | 参加（説明） | — | — | — | — |
| QA lead | 停止判定 | 司会・severity 決定 | — | 回帰範囲の指示 | 確認・承認 | 承認（Product と） |
| 開発者（モジュール owner） | — | 原因見立て | **修正・コミット** | ユニット/モジュール回帰 | — | — |
| Product / 業務責任者 | — | 金額・法令 oracle | — | — | 受入 | 承認 |
| DBA / Infra | — | 環境起因の切り分け | 環境修復 | — | — | — |

**基本原則: 発見者 ≠ 修正者。** 誰が欠陥を測ったかにかかわらず、対応の入口は defect 台帳への登録と Triage である（重複・誤報告・環境起因の誤認・root cause 共有を防ぐ）。例外は §4 の直通ルールのみ。

---

## 2. フェーズ別キックオフ（開工対話）

各フェーズの開始前に、QA lead が下記の質問を参加者へ投げ、**証跡（ファイル・画面・数値）で回答**を得る。口頭 OK は受け付けない。1 件でも NG があればフェーズは開始せず、原因を環境修正票または開発チケットとして発行してから再開する。

### 2.0 W0 Readiness（開発側の準備完了確認）

- 「S10〜S17 の中央 execution ledger の現行判定を読み上げてください。PASS / IN PROGRESS / NOT READY は？」
- 「P0/P1 の open defect は 0 ですか？0 でないものの一覧と期限は？」
- 「対象モジュールの M-PASS 証跡（実装 commit、独立 review、MySQL smoke、skip 0、module smoke）は揃っていますか？」

**Exit**: モジュールごとの M-PASS evidence が揃う。

### 2.1 W1 Freeze / Pilot（全体開工）

- 「Candidate build の commit SHA・branch・dirty 有無・build artifact checksum を出してください」
- 「route / API / template / menu-action / state / adapter inventory の生成スクリプトを実行し、SHA-256 と件数を出してください。前回との diff は？」
- 「`verify-like-ci` を実行し、skip 0 と Docker・Node の実実行を確認してください」
- 「`E2E-BASE-300` snapshot の作成と、297 active / 3 disabled の seed validator 結果を出してください」
- 「evidence パイプラインと UI harness（Playwright、browser matrix、network shaping）の rehearsal 結果を出してください」
- 「open defect 一覧を severity 順に読み上げてください。P0/P1 は？」
- 「代表 10 ケースの pilot を実施し、実測 h/ID と初期単価の差分（±15%）を報告してください」

**Exit**: 分母・SHA・単価・forecast の承認。日付はここまで決めない。

### 2.2 W2 ITa（モジュール単位）

- 「このモジュールの spec ledger は PASS ですか？未 PASS なら BLOCKED(M-PASS) として ID を分離します」
- 「route inventory と設計契約は一致していますか？不一致は BLOCKED_SPEC_MISMATCH です」
- 「当該モジュールの module test・H2 スキーマ・JS syntax は skip 0 で合格していますか」
- 「正常・主要拒否・rollback・permission/scope の module smoke は合格していますか」

**Exit**: モジュール inventory の設計/実行カバレッジ・合格率 100%。

### 2.3 W3 ITb（連携族ごと）

- 「連携元・連携先の両モジュールの W2 合格は確認できましたか？」
- 「`interface-contract.json`（route / method+path / status / 更新表 / transaction 境界）は frozen ですか？」
- 「両端の mock（CloudSign・メール・AI・portal）は契約 version・固定応答・失敗応答・reset API を持っていますか？」

**Exit**: 対象連携族 ID 全 PASS、current/full の status 分離。

### 2.4 W4 E2E（シナリオごと）

- 「参加する全モジュールと対象 ITb の合格は確認できましたか？」
- 「シナリオの fixture（natural key + RUN_ID）と oracle（金額・状態・外部応答）の manifest を出してください」
- 「case DB の clone / 破棄、mock・mailbox・storage・cache の reset rehearsal は合格していますか」

**Exit**: 対象 E2E ID 全 PASS、future は BLOCKED(M-PASS) で報告。

### 2.5 W4b UI 実操作（ブラウザ実操作レイヤー）

- 「対象モジュールの W2 合格と、W4 の E2E 正常系で導線が確定していますか」
- 「UI harness（browser matrix・trace/video/screenshot/console 収集・network shaping）の rehearsal は合格していますか」
- 「`I` inventory（ページ×操作プリミティブ）の mapping と browser engine 割当を出してください」

**Exit**: UI-00 全 route 100%、全 UI ID PASS、console/pageerror/白画面 0。

### 2.6 W5 Security

- 「frozen route/action と 25 営業の排他的 owner fixture の manifest を出してください」
- 「5 ロール + 未認証の allow/deny cell 一覧（機械生成）はありますか」
- 「CSRF・IDOR・session・MFA・disabled account・file ACL・XSS の検査対象一覧は揃っていますか」

**Exit**: security gate 合格、P0/P1 0。

### 2.7 W5b モンキー

- 「当該モジュールの W2 合格は確認できましたか？（全掃引は W5 後）」
- 「モンキー harness（seed 固定・時間予算・invariant suite・証跡出力）の rehearsal は合格していますか」
- 「乱択除外リスト（admin 自身・baseline 参照データ・他ケース共用 fixture）は manifest にありますか」

**Exit**: 予算消化（skip 0）、検出 P0/P1 0、invariant 違反 0。

### 2.8 W6 性能

- 「機能 smoke は合格していますか？性能環境（vCPU/RAM/network、Tomcat/Hikari/MySQL 設定、load agent 数）の manifest は？」
- 「7 profile の負荷形状（ramp-up / arrival rate / think time / 操作 mix / 期間）は承認済みですか」
- 「telemetry（p50/p95/p99、pool/queue、JVM/GC、DB lock/slow query）の取得確認は済んでいますか」

**Exit**: profile 別基準合格、DB 整合、capacity report 合格。

### 2.9 W7 Defect / Regression（修正 build ごと）

- 「修正 commit の一覧と、各 defect の再現 ID を出してください」
- 「§3.6 の必須回帰 6 項目（再現・モジュール回帰・ITb 連携族・E2E・性能・UI/モンキー seed）の担当と予定を確認します」
- 「buffer 残工数と実測 burn-down rate を報告してください」

**Exit**: reopen 0、flake 未解決 0。

### 2.10 W8 Report / Sign-off

- 「evidence index・coverage・defect・残 risk の reconciliation 結果を出してください」
- 「P2/P3 の waiver 一覧と承認者（Product + QA lead）を確認します」
- 「QA lead・開発責任者・Product の最終サインオフを取ります」

**Exit**: sign-off 完了、残存 case DB / mock / file 0。

---

## 3. 欠陥対応 SOP（発見 → クローズ）

### 3.1 発見時の即時アクション（30 秒以内）

1. 証跡をその場で保存: screenshot / video / trace.zip / console ログ / network HAR / correlation ID 付き server log / **DB before-after**。
2. モンキー発見の場合は **seed + step 番号**を記録（これが再現の鍵）。
3. case DB が汚染された可能性がある場合は当該 clone を凍結し、次のケースへ進まない。

### 3.2 記録（defect 台帳の必須項目）

ID / severity（未定は `TBD`）/ モジュール / 関連 case ID / build SHA / RUN_ID / 再現手順または seed+step / 期待 vs 実際 / 証跡リンク / 発見者 / 発見日時 / status（OPEN → IN_PROGRESS → VERIFIED → CLOSED）。

### 3.3 Triage（毎日。QA lead 司会、開発 owner・Product・DBA 参加）

| Severity | 判定基準 | 対応 |
|---|---|---|
| P0 | データ/scope 越境、認証 bypass、データ消失/破損、二重請求/支払、貸借不一致、migration 不能、secret 漏洩、主要環境停止 | 即時全体停止・1h 以内 triage・修正と全回帰まで再開不可 |
| P1 | 主要 E2E 不通、誤金額/税/締め、rollback 不成立、権限誤許可、外部重複、主要帳票不正 | 当該モジュール/下流停止・当日 triage・Exit 0 件 |
| P2 | workaround がある機能欠損、非主要境界誤り、性能 gate 未達 | security/金額/法令は Exit 0、その他は waiver 必須 |
| P3 | 表示崩れ、軽微な文言 | 件数と waiver を報告 |

判定は 1 人で決めない。金額・法令・security が絡む場合は Product/業務責任者の oracle を必ず確認する。

### 3.4 担当の決定（誰が対応するか）

- 開発者（モジュール owner）が修正。QA は修正しない。
- 環境起因（DBA/Infra 判定）は品質 defect と分離し、近 20 instance の 5% 超で発生したら環境修復を優先。
- 「発見者が自分の担当モジュールの defect を見つけた」場合でも、**修正者は triage でアサインされた開発者**であり、発見者の自己修正は行わない（証跡の独立性を保つ）。

### 3.5 修正

開発者は defect 記録の seed/case をローカルで再現 → 修正 → コミットメッセージに defect ID を記載。

### 3.6 クローズ前の必須回帰（6 項目）

1. defect 再現 case の PASS
2. 変更モジュールの正常・拒否・rollback・security 回帰
3. 同一 ITb 連携族 3 ID + 上流・下流の連携族
4. 関連 E2E の正常/拒否/回復 3 ID
5. SQL / cache / thread / 外部 retry に影響する場合は該当性能 profile
6. 影響画面の UI 実操作 ID 再実行、モンキー発見 defect は同一 seed のリプレイ

### 3.7 クローズ判定

- 偶然の 1 回 PASS では閉じない。root cause・修正 commit・**3 回連続の再現なし**・回帰 6 項目の証跡が揃って初めて CLOSED。
- flaky（環境依存の揺らぎ）は root cause が確定するまで close しない。

### 3.8 Waiver

P2/P3 の残存は Product + QA lead の署名、scope / 期限 / 残 risk / 証跡リンクを明示。P0/P1 と security・金額・法令の P2 は waiver 不可。

---

## 4. 「誰が測った Bug を直接対応してよいか」のルール

**デフォルト: 全件 Triage 経由（発見者直結は行わない）。** 理由:

1. 同一 root cause の重複報告を 1 件に束ねるため。
2. 誤報告・仕様誤解・環境起因を Triage で切り分けてから開発へ渡すため。
3. 影響範囲（どのモジュール・ITb 族・E2E が止まるか）は QA lead が判断するため。

**例外（直通ルール）:**

- **P0**: 発見後 1 時間以内に QA lead が開発責任者へ直接エスカレーションし、Triage を待たずに復旧対応を開始させる。台帳登録は並行して行う。
- **P1 で owner が自明**（発見者が同一モジュールの担当開発を特定でき、severity が疑いなく P1）: 発見者は「直通アサイン」として開発者へ連絡してよい。ただし台帳への登録・triage への持ち込みは必須で、開発者は自分の修正が直通アサインであることを台帳に記す。
- 直通アサインされた defect も、クローズ判定（§3.6・3.7）は必ず QA lead を経由する。

**推奨運用**: チームが小規模で発見者 = 開発者も多い場合でも、Triage だけは必ず開催する（日次 15 分）。「テストを測った人がそのまま直す」を常態化すると、severity の客観性・回帰範囲・証跡の独立性が崩れるため、禁止する。
