# Design — AI推薦フィードバック・評価ループ

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V77）

- `m_ai_artifact_version(id, use_case, provider, model_name, prompt/rule_version, config_hash,
  status DRAFT/SHADOW/ACTIVE/RETIRED, activated_at)`。
- `t_ai_recommendation_run(id, trace_id, use_case, artifact_version_id, actor_user_id,
  input_hash, redacted_summary_json, latency_ms, token/cost metrics, status, error_code, created_at)`。
- `t_ai_recommendation_item(run_id, rank, target_type/id, score, explanation_json, selected_flag)`。
- `t_ai_feedback(item_id, decision, reason_code, comment_redacted, decided_by/at)`。
- `t_ai_outcome(item_id, outcome_type, source_type/id, occurred_at, value_json)`。
- `t_ai_evaluation(id, candidate_version, baseline_version, dataset_version, metrics_json, status, approved_by)`。

既存`t_ai_log`はlegacy raw logとして段階移行し、新規PII raw prompt保存を止める。

## 2. Gateway/trace

- `AiExecutionGateway`がmask→provider→recordを一元化。各controller/serviceが直接Geminiを呼ばない。
- trace IDを提案draft/提案へ保存し、後続state eventからoutcome handlerが冪等登録。
- model response explanationはJSON schema検証し、HTMLとしてrenderしない。

## 3. Version/evaluation

- active versionはuse case+tenant configで1つ。shadowは結果保存可だがユーザー表示/業務作成に使わない。
- anonymized fixed datasetはrepoにPIIなしfixture、実データ由来はaccess restricted storage。
- promotionはmetric threshold+管理者承認。自動promotion禁止。

## 4. Dashboard

- `/ai/evaluation`: version、funnel、reason、latency/cost、sample inspection（field mask）。
- low-volume segment非表示閾値。

## 5. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。
AI固有のPII規約は `CLAUDE.md`「AI機能開発時の注意事項（A8-01/A8-02関連）」も併せて正とする。

### 5.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| active version | `m_ai_artifact_version.status=ACTIVE` | `activated_at`＋遷移履歴 | runへ`artifact_version_id`を固定 | **run実行時点**のactive | use case未設定＝mock/ruleへfallback |
| run記録 | `t_ai_recommendation_run` | append-only | `input_hash`＋`redacted_summary_json` | run時点 | — |
| feedback | `t_ai_feedback` | item単位でappend | — | 登録時点 | **未判断**（却下ではない） |
| outcome | `t_ai_outcome` | append-only | — | `occurred_at` | 未発生（**失敗ではない**） |
| evaluation | `t_ai_evaluation` | candidate/baseline対で保存 | `dataset_version`で固定 | 評価実行時点 | — |

- **過去のrun記録はversion切替後も不変**（R5）。rollback後の新規runだけが旧versionを使う。
  過去recordのversion参照を書き換えない。S02の「過去実績を現在値で変えない」と同構造。
- `feedback IS NULL`（未判断）を却下として集計しない。採用率の分母から除外する。§1.1に該当。
- `outcome`未発生を失敗として集計しない。観測期間内に発生していないだけの可能性がある。
  評価metricは**観測期間を明示**した母集団で計算する。

### 5.2 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件。version promotion/rollback可 | 全件（field mask適用） | 評価完了、閾値未達 | 評価batch、outcome収集 |
| マネージャー | 自組織のrun/feedback。**cost/token metricsは不可視** | 同左 | — | — |
| 営業 | 自分が実行したrunとそのfeedbackのみ | — | — | — |
| HR | **不可視**（雇用判断への流用を構造的に防ぐ） | — | — | — |
| 要員 | 不可視 | — | — | — |
| portal user | 不可視 | — | — | — |
| scheduler principal | 全件 | — | 宛先は管理者 | outcome冪等登録、評価実行 |

- **low-volume segmentは非表示**（design §4）。閾値未満のsegmentは個人が特定される。
  skill/単価/勤務地のsegment表示に最低件数configを適用する（R3.3）。
- `sample inspection`はfield maskを適用する。生のprompt/PIIを画面へ出さない。

#### 禁止属性リスト（確定済み。実装中に判断しない）

**matching特徴量にもsegment軸にも使わない属性。** 厚生労働省「公正な採用選考の基本」の
考え方および均等法・障害者雇用促進法・労働施策総合推進法の趣旨に沿った開発baselineとする。
SESの要員推薦は雇用そのものではないが、就業機会の配分に影響するため同じ基準を適用する。

| 分類 | 禁止する属性 |
|---|---|
| 本人に責任のない事項 | 本籍、出生地、国籍、人種、民族、家族の職業/続柄/学歴/収入/資産、住宅状況、生活環境 |
| 思想・信条 | 宗教、支持政党、人生観、生活信条、尊敬する人物、社会運動歴、購読新聞/愛読書 |
| 属性差別につながる事項 | 性別、年齢（生年月日を含む）、障害の有無、健康状態/病歴、婚姻状況、妊娠/出産/育児の予定 |
| 本システム固有 | 顔写真（`t_engineer`の写真）、氏名から推定される属性 |

実装上の扱い:

- **allowlist方式で構造的に防ぐ。** 特徴量とsegment軸は「許可したfieldだけ」を列挙し、
  禁止リストは**leak検知のためのassertion**として使う。denylistだけに頼らない。
  新しいfieldを追加したとき、denylistは自動では追随しないため。
- 禁止属性は `ai.feature.denied-attributes`（`m_system_config`、カンマ区切り）で保持し、
  gatewayとsegment生成の両方が起動時に検証する。変更は`/system-config`から可能。
- **年齢は禁止だが「経験年数」は許可**する。経験年数は職務能力の指標であり、生年月日から
  逆算できない形（`t_engineer`の経験年数列）で持つ。年齢と経験年数を同時に特徴量へ入れない。
- 禁止属性が特徴量やsegmentへ現れたら**実行を止める**（fail-closed）。警告して続行しない。
- 変更手順: `/system-config`の値を変更 → 本表を更新 → `AiFeatureAllowlistTest`の期待値を更新 → L2。

### 5.3 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| version DRAFT | →SHADOW / →RETIRED | 状態CAS | — | DRAFTへ |
| SHADOW | →ACTIVE（評価合格＋管理者承認）/ →RETIRED | **状態CAS＋`UNIQUE(use_case, tenant) WHERE status=ACTIVE`** | 同時promotion | SHADOWへ |
| ACTIVE | →RETIRED（rollback時に前versionをACTIVEへ） | 同上 | 同時rollback | **即時に前version/mock/ruleへ**（R3.4） |
| RETIRED | →SHADOW（再評価） | 状態CAS | — | — |
| run pending | →succeeded / →failed | 状態CAS | — | — |
| evaluation 実行中 | →合格 / →不合格 | 状態CAS | — | — |

- **active versionはuse case × tenantで1つ**（design §3）。部分UNIQUE制約で保証する。
  アプリ側の「既存ACTIVEをRETIREDにしてから新規をACTIVE」は競合で2件ACTIVEになりうる。
  同一transaction＋CASで行う。
- **shadowは業務作成に使わない**（design §3）。結果は保存するが、
  ユーザー表示・提案作成・契約作成の経路へ接続しない。testで経路の不存在を固定する。
- **自動promotion禁止**（R3.2、design §3）。metric閾値合格は必要条件であり十分条件ではない。
  管理者承認を必須とする。
- **outcome登録の冪等**: `UNIQUE(item_id, outcome_type, source_type, source_id)`。
  提案・契約のstate eventが再送されても二重登録しない。
- **AIは業務状態を自動変更しない**（前提節、roadmap Wave 4の制約）。
  feedback/outcomeの登録以外に、提案・契約・メール送信・人事判断を変更する経路を作らない。

### 5.4 PII境界（本specの中核リスク）

| 論点 | 決定 |
|---|---|
| 外部送信 | `AiExecutionGateway`経由のみ。controller/serviceが直接providerを呼ばない（design §2） |
| 送信field | **allowlist**。氏名・連絡先・住所・口座・自由記述PIIをmask（R4.1） |
| 保存 | raw promptを既定保存しない。`redacted_summary_json`＋`input_hash`のみ（R1.2） |
| legacy | 既存`t_ai_log`のraw logは段階移行。**新規のraw prompt保存を止める**（design §1） |
| 取込原文 | **untrusted data**として分離。命令として解釈しない。tool/action権限を与えない（R4.3） |
| response | JSON schema検証。**HTMLとしてrenderしない**（design §2） |
| 保存期間 | provider別に設定し、超過分は論理削除・purge（`app.resume.retention-days`と同方式） |

- **PII canary test**を必須にする（R5）: canary文字列を含むデータでrunし、
  provider request・ログ・DB summaryのいずれにも出ないことをassertする。
- **prompt injection fixture**を持つ（design §5）。取込原文に命令文を仕込んだfixtureで、
  gatewayが命令として実行しないことを確認する。
- `AiTextService`のBean競合回避（CLAUDE.md）: 実装クラスへ
  `@ConditionalOnExpression`でproviderフォールバックを設定し、どのproviderでも
  全AI系Beanが一意に解決されることを担保する。

## 6. テスト

mask canary、gateway強制、trace/outcome冪等、version switch/shadow/rollback、metric calculation、
少数非表示、provider failure、prompt injection fixture、tenant/scope。

