# Design — AI推薦フィードバック・評価ループ

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V80）

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

## 5. テスト

mask canary、gateway強制、trace/outcome冪等、version switch/shadow/rollback、metric calculation、
少数非表示、provider failure、prompt injection fixture、tenant/scope。

