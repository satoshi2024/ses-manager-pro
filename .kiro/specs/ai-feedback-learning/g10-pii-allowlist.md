# G10 / PII allowlist / metric — T109 成果物

> 本ファイルは `ai-feedback-learning` の T109（0. G10/use case/PII/metric確定）の正本である。
> T110以降は、ここに無いfieldを推測で送信・特徴量化・segment化しない。
> 禁止属性リスト自体は `design.md` §5.2 が正であり、本taskでは決め直さない。
> 機械可読の交差検査用契約は同ディレクトリの `g10-allowlist.json`。両方を同一内容として維持する。

## 1. G10 決定

| 項目 | 値 |
|---|---|
| ID | G10 |
| blocking | no |
| 決定 | **mock/rule を既定維持。実データを外部AIへ送らない。** Gemini等の実providerは opt-in かつ DPA・region・学習利用禁止・allowlist が発注者承認されるまで production で有効化しない |
| 決定日 | 2026-08-20 |
| 決定者 | S17主実装（推奨既定の採用記録。発注者の明示委任に基づく。G8/G9と同型） |
| 根拠 | `decision-log.md` の推奨既定、`gate-0-readiness-report.md` 「mock/ruleと実送信禁止を採用する場合、現時点の追加回答は不要」、現行 `application.yml` は `ai.provider: mock` |
| 本番release gate | `GATE-S17-G10-PROD`（security / HR / product owner の実署名、DPA、region、学習opt-out） |
| 開発中の外部送信 | **禁止**。`ai.provider=gemini` を設定しても、実データpayloadを外部へ出してはならない。T111はgatewayで fail-closed する |

誤決定時の返工: canonical input、mask、監査、評価データ、provider adapter、model/prompt版管理を再設計する。

## 2. Use case

評価ループ（推薦→採否→成果）の対象は次の3つ。AIは業務状態を自動変更しない。

| use_case | 現行入口 | 評価対象 | 外部送信（G10確定後） |
|---|---|---|---|
| `MATCH_ENGINEER_TO_PROJECTS` | `POST /api/ai/match/engineer-to-projects` | する | mock/ruleのみ。実providerはgate後 |
| `MATCH_PROJECT_TO_ENGINEERS` | `GET /api/ai/matching/project/{id}`（内部要員+BP在庫） | する | 同上 |
| `PROPOSAL_DRAFT` | `POST /api/ai/proposal-draft` | する（人手修正差分の母集団） | 同上 |

取込・対話は評価ループに入れないが、gateway/PII境界の対象である。

| use_case | 現行入口 | 原文の扱い | 評価ループ |
|---|---|---|---|
| `INGEST_RESUME` | Resume parse | **untrusted data**。命令として実行しない。tool/action権限なし | 対象外 |
| `INGEST_PROJECT` | Project parse | 同上 | 対象外 |
| `INGEST_BP_AVAILABILITY` | BP availability parse | 同上 | 対象外 |
| `CHAT` | `POST /api/ai/chat` | ユーザー文は untrusted。コンテキストは本allowlistのみ | 対象外 |

## 3. Outcome source（先行条件の確認）

CRM / 提案 / staffing は merge済みで、成果eventの母集団は次で足りる。新しい業務状態機械は作らない。

| outcome_type | 源 | 判定 |
|---|---|---|
| `PROPOSAL_CREATED` | `t_proposal.id` | 推薦itemから提案が作成された |
| `INTERVIEW` | `t_proposal.status` ∈ {一次面接, 二次面接, 結果待ち} | 面談が発生 |
| `WIN` | `t_proposal.status=成約` または `t_opportunity.stage=受注` | 成約。功績断定はしない。同一traceに両源があっても **EXISTS（1回）** であり件数加算しない |
| `LOSS` | `t_proposal.status=見送り` または `t_opportunity.stage=失注` | 失注。`lost_reason` はredactしてcategoryのみ |
| `CONTRACT_CONTINUED` | `t_contract.renewal_decision=CONTINUE` | 契約継続 |
| `EARLY_EXIT` | `t_contract.status=解約` **かつ** `occurred_at < original_end_date` | 予定終了日より前の打ち切りだけ。`original_end_date` は解約CAS前に snapshot した当初 `end_date`。満了解約および `occurred_at = original_end_date`（当日解約）は EARLY_EXIT にしない。outcome未発生は失敗ではない |
| `POSITION_LINKED` | `t_proposal.position_id` / `t_contract.position_id` | staffing枠への紐付け |

冪等キーは `UNIQUE(item_id, outcome_type, source_type, source_id)`（design §5.3）。

## 4. 送信field allowlist

allowlist方式。ここに無いfieldは送信しない、特徴量にしない。
`send=yes` でも matching特徴量にしない行は「send-only」。
segment軸は `segment=yes` の行だけ（R3.3: skill / 単価 / 勤務地）。

### 4.1 Matching特徴量（score / rank / 許可segment）

| field | send | segment | 根拠 |
|---|---|---|---|
| `engineer.experienceYears` | yes | no | 職務能力。生年月日からの逆算禁止 |
| `engineer.expectedUnitPrice` | yes | yes | 希望単価(円) |
| `engineer.availableDate` | yes | no | 現行 `MatchScoreCalculator` 入力 |
| `engineer.status` | yes | no | 稼動状態。個人属性ではない |
| `engineer.employmentType` | yes | no | 内部/BPの運用区分 |
| `engineer.prefecture` | yes | yes | 最寄り駅の都道府県=勤務地。本籍ではない |
| `engineer.nearestStation` | yes | no | 通勤地。番地住所ではない |
| `engineer.railwayCompany` | yes | no | 通勤路線。sparseなのでsegmentにしない |
| `engineerSkill.skillId` | yes | yes | 保有スキル |
| `engineerSkill.skillName` | yes | yes | スキル名 |
| `engineerSkill.proficiency` | yes | no | 初級/中級/上級 |
| `engineerSkill.experienceYears` | yes | no | スキル経験年数 |
| `career.techStack` | yes | no | 技術名のみ。案件説明文は送らない |
| `career.role` | yes | no | 役割名。固有名詞はmask |
| `career.clientIndustry` | yes | no | 業種カテゴリ。顧客名は送らない |
| `career.periodMonths` | yes | no | 従事月数。暦日は送らず年齢推定を防ぐ |
| `project.unitPriceMin` | yes | yes | 案件単価下限(円) |
| `project.unitPriceMax` | yes | yes | 案件単価上限(円) |
| `project.workLocation` | yes | yes | 勤務地segment。粒度は都道府県/市区町村まで（§6） |
| `project.remoteType` | yes | yes | リモート区分 |
| `project.startDate` | yes | no | 開始予定日 |
| `project.endDate` | yes | no | 終了予定日 |
| `project.requiredCount` | yes | no | 募集人数 |
| `projectSkill.skillId` | yes | yes | 案件スキル |
| `projectSkill.skillName` | yes | yes | 案件スキル名 |
| `projectSkill.isMust` | yes | no | 必須/尚可 |
| `bp.experienceYears` | yes | no | BP在庫の経験年数 |
| `bp.unitPrice` | yes | yes | BP単価(円) |
| `bp.availableFrom` | yes | no | BP稼動可能日 |
| `bp.skillNames` | yes | yes | BPスキル名 |

現行ルール採点（`MatchScoreCalculator`）が使うのはスキル集合・単価・稼働日だけである。上表の追加fieldは説明生成とsegmentに限り、**年齢・性別・国籍と同時に入れない**。

### 4.2 Send-only（特徴量にもsegmentにも使わない）

| field | send | 根拠 |
|---|---|---|
| `engineer.initialName` | yes | 氏名のマスク。`fullName` 禁止 |
| `engineer.japaneseLevel` | yes | 日本語能力は職務スキルであり国籍ではない。代理変数化を避けるため特徴量/segment禁止 |
| `bp.initialName` | yes | BPはイニシャルのみ |
| `bp.bpCompany` | yes | 会社名。個人PIIではない |
| `project.projectName` | yes | 案件名。詳細文は送らない |
| `ruleScore.total` | yes | ルール点の再現 |
| `ruleScore.mustCoverage` | yes | 必須充足率 |
| `ruleScore.priceScore` | yes | 単価点 |
| `ruleScore.dateScore` | yes | 稼働日点 |

内部ID（`engineer.id` / `project.id` / `bp.id`）は run 記録と `input_hash` にだけ残し、**外部provider payloadへ出さない**。

### 4.3 送信しない（never send）

| field | 理由 |
|---|---|
| `engineer.fullName` / `fullNameKana` | 氏名PII。氏名からの属性推定も禁止 |
| `engineer.gender` | §5.2 属性差別 |
| `engineer.birthDate` | 年齢禁止 |
| `engineer.nationality` | 本人に責任のない事項 |
| `engineer.phone` | 連絡先 |
| `engineer.photoUrl` | 顔写真 |
| `engineer.resumeSummary` | 自由記述PII |
| `engineer.remarks` | 自由記述PII |
| `career.description` | 自由記述PII |
| `career.projectName` | 顧客・案件の再識別 |
| `career.periodFrom` / `periodTo` | 暦日から年齢推定 |
| `project.description` | 自由記述。現行Gemini matchingが送っている **既存leak** |
| `project.remarks` | 自由記述 |
| `bp.remarks` | 自由記述 |
| 顧客担当者・email・電話 | 連絡先 |
| 口座 | 口座PII |
| 住所番地 | 住所 |
| raw prompt | R1.2。`redacted_summary_json` + `input_hash` のみ |
| 取込原文をsystem命令として | R4.3 |

`CHAT` の `fullName` fallback と `resumeSummary` / `description` 投入は **既存leak**。T111でallowlist外を落とす。

## 5. 禁止属性との非交差（L0）

`design.md` §5.2 の禁止分類と allowlist の交差は **0件**。検証は `AiG10AllowlistDocumentTest`。

| 禁止分類 | 代表 | allowlist上の扱い |
|---|---|---|
| 本人に責任のない事項 | 本籍・出生地・国籍・人種・民族・家族・住宅 | fieldなし。`nationality` は never send |
| 思想・信条 | 宗教・政党・人生観等 | 本システムに列なし。追加しない |
| 属性差別 | 性別・年齢/生年月日・障害・健康・婚姻・妊娠 | `gender`/`birthDate` never send。経験年数は許可、年齢と併用しない |
| 本システム固有 | 顔写真、氏名から推定される属性 | `photoUrl`/`fullName` never send。`initialName` のみ |

`japaneseLevel` を国籍の代理に使わない（特徴量・segment禁止）。

## 6. Mask規則

1. 送信前に gateway が allowlist 外を削除する。残った値に対し次を適用する。
2. 氏名: `fullName` を捨て `initialName` だけ。未設定なら当該人物ブロック全体を送らない（`fullName` へfallbackしない）。
3. 連絡先・住所・口座: 削除。部分マスクもしない（復元可能なため）。
4. 自由記述: 削除。取込原文は別チャネルの untrusted data として渡し、system/developer命令と連結しない。
5. 固有名詞が混入しうる `career.role`: 英数・役職語以外を `***` にする（T111実装）。
6. `project.workLocation`（grain=`prefecture-municipality`）: **都道府県および市区町村トークンまで**残す。番地・丁目・番・号・建物名・部屋番号は落とす。正規化できない値（トークン分割不能、数字始まりの番地だけ、など）は **送らず segment にも使わない**。`engineer.prefecture` は都道府県列なので追加正規化しない。
7. HTMLは送らない・renderしない。応答はJSON schema検証。
8. ログ・DB summary・画面sample inspectionは同じmaskを通す。
9. PII canary: `SES-PII-CANARY-T109-7f2e9c1a` を氏名/電話/自由記述に埋め、provider request・ログ・`redacted_summary_json` に出ないこと（T111/T115）。番地 canary（例: `丸の内1-1-1`）は `workLocation` 正規化後の payload に出ないこと。

## 7. Provider別 DPA / region / 保存期間 / opt-out

| provider | DPA | region | raw prompt | redacted run | legacy `t_ai_log` | 学習利用 | production送信 |
|---|---|---|---|---|---|---|---|
| `mock` | 不要（egressなし） | 自プロセス | **0日（保存しない）** | 730日 | 30日（`app.resume.retention-days`） | なし | 評価用のみ。外部なし |
| `rule` | 不要（LLMなし） | 自プロセス | 0日 | 730日 | 30日 | なし | 同上 |
| `gemini` | **未締結** | 現行URLは `generativelanguage.googleapis.com`（越境未確認） | 0日 | 730日 | 30日 | 本番前に **training opt-out必須** | **禁止**（`GATE-S17-G10-PROD`まで） |

- 新規raw prompt保存を止める（design §1）。既存 `t_ai_log.response_text` はlegacy。超過分は論理削除・purge。
- redacted記録は評価に必要なため730日。中身はallowlist済みsummaryとhashのみ。
- 実データ由来のoffline datasetはrepoに置かない。repo fixtureはPIIなし。
- tenant opt-out: `ai.external-send.enabled` 既定 `false`（T111でconfig化）。falseの間はmock/rule以外を起動しても外部送信しない。

## 8. 成功metric

観測期間を明示する。既定オンライン窓は **90日**。`feedback IS NULL`（未判断）と `保留` は採用率の分母から除外する。outcome未発生は失敗ではない。

| metric | 定義 | 分母 | 可視 |
|---|---|---|---|
| 採用率 | `decision=採用` | `採用+却下`（NULL/保留除外） | 管理者・マネージャー（自組織）・営業（自分のrun） |
| 面談率 | `INTERVIEW` あり | 採用 | 同上 |
| 成約率 | `WIN` あり | 採用 | 同上 |
| precision@5 / @10 | rank≤k かつ決定済みのうち採用 | 決定済みitem | 同上 |
| 理由分布 | `reason_code` 件数 | 決定済み | 同上。最低件数未満のsegmentは非表示 |
| latency p50/p95 | 成功runの `latency_ms` | 成功run | 同上 |
| token / cost | 成功runのtokenと円 | 成功run | **管理者のみ** |

offline評価（T113）:

- 固定匿名datasetで candidate 対 baseline を比較する。
- 自動promotion禁止。metric合格は必要条件、管理者承認が十分条件。
- 開発baseline閾値: 採用率・precision@5 が baselineから **5ポイント超の悪化で拒否**。latency p95 が baselineの **2倍超で拒否**。禁止属性leakは **0件必須**。
- rollback後、過去runの `artifact_version_id` は書き換えない。

low-volume: `ai.evaluation.min-segment-count` 既定 **5**。未満のsegmentは非表示。

## 9. Feedback理由category（閉集合）

`OTHER` の comment はredactする。外見・年齢・性別・国籍・健康を理由コードにしない。

`SKILL_MISMATCH` / `PRICE_MISMATCH` / `AVAILABILITY` / `LOCATION` / `CUSTOMER_REQUEST` / `ALREADY_ASSIGNED` / `OTHER_REDACTED`

## 10. 現行コードのgap（T111で閉じる。本taskでは本番変更しない）

| 箇所 | 問題 |
|---|---|
| `GeminiMatchingServiceImpl.buildPromptForProjectMatch` | `project.description` を送信 |
| `AiRestController.chat` | `fullName` fallback、`resumeSummary`、`description`、ユーザー文を命令として連結 |
| `GeminiTextServiceImpl` | prompt全文を外部APIへ |
| `t_ai_log` | `request_params` / `response_text` にrawを保存しうる |
| Bean | controller/serviceが `AiTextService` を直接呼ぶ。gateway未導入 |

## 11. Demo / 承認

| 種別 | 状態 | 内容 |
|---|---|---|
| 開発baseline | **記録済** | mock/rule既定、allowlist、mask、保存期間、metric。security/HR/POがレビュー可能な正本が本ファイル |
| 本番 `GATE-S17-G10-PROD` | **未達** | 実provider DPA、region確定、学習opt-out、security/HR/product ownerの署名。未達のまま実データ外部送信とM本番PASSをしない |

## 12. Flyway確認（T110時点）

| location | 実在 | 本spec |
|---|---|---|
| `db/migration` | latest整数 **V108**（`V108__ai_feedback_learning.sql`）。欠番埋めなし | 本spec正式migration |
| `db/migration-dev` | `V100__seed_r3_scale_300.sql` | commonへ再利用しない |
| `db/migration-prod` | `R__update_admin_password_bcrypt.sql` のみ | 番号なし |
| 永久欠番 | V59, V72, V82, V99（common） | 埋めない |
