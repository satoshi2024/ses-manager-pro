# NF-08 外部送信承認ゲート（W2: external-send gate）

## 0. 状態

| 項目 | 状態 |
|---|---|
| 利用者決定 | B-NF08-1: 首期は実 Gemini を接続する。B-NF08-2: 本番 external-send の硬拒否を「署名済み決定IDに束縛した承認設定」へ置き換え、外部送信ごとに監査を残す |
| 本書の範囲 | 承認設定ゲート、送信前後の監査、費用上限の計数と拒否、mock/fake/loopback での対抗テスト |
| 署名 | **未完**（DPA、DG-08、NF07 policy、model/用途/費用の承認）。したがって本番は既定で拒否のまま |
| 既定値 | `ai.external-send-enabled=false`、`ai.external-send-approval.*` 全て未設定、`ai.provider=mock`、`ai.management-copilot-enabled=false` |
| 実provider呼出し・実secret | 本変更では一切追加しない。テストは mock / fake adapter / test用 loopback transport のみ |
| 利用者経路での実送信 | **まだ発生しない**。B2c-0 の local-only summary attempt 境界（`CopilotSummaryPreflightCandidate.permitsLocalSummaryAttempt`）と予算0円の呼出しを維持する。解放は B2c-1/2（A-NF08-2、DDL・Plan Review 要）完了後 |

## 1. ゲート設計

### 1.1 判定式

外部送信（`ai.provider` が mock/rule 以外）は、次の全てを満たす場合だけ許可する。

```text
external-send-enabled
 ∧ provider が登録済みかつ非local
 ∧ 運用者確認チェックリスト（ai.production-gates.* 10項目）が全て true
 ∧ 承認設定（ai.external-send-approval.*）が完全かつ現行 ai.* 設定と一致
 ∧ 承認の有効期間内（JST業務日で 署名日 ≦ 当日 ＜ 失効日）
 ∧ 用途が承認用途に含まれる（現時点で受理するのは MANAGEMENT_COPILOT のみ）
 ∧ 宛先host・model・provider が承認値と一致
 ∧ 費用上限（1回・日次・月次）以内
 ∧ 送信前監査の永続化に成功
```

一つでも欠ければ送信しない（fail-closed）。承認設定がない場合は従来の固定 deny-all policy（`ProductionDenyAllCopilotCostPolicy`、理由コード `COPILOT_COST_UNKNOWN`）がそのまま使われる。

### 1.2 起動時 fail-fast と呼出し時拒否の分担

| 検査 | 時点 | 理由 |
|---|---|---|
| 承認設定の完全性・形式・相互一致（決定ID群、日付、provider/model/宛先、費用上限の大小関係、単価） | **prod 起動時に fail-fast**（`AiProductionSafetyValidator`） | 設定誤りを最初の利用者requestまで潜伏させず、deploy時点で検出する。誤設定のまま起動して実行時に黙って拒否し続ける状態を作らない |
| チェックリスト10項目と承認設定の両立 | prod 起動時に fail-fast | 補助項目だけ true／承認設定だけ有り、の片側成立を起動時に止める |
| 失効日・費用累計・request ごとの用途/宛先/model/byte数 | **呼出し時に拒否**（gateway・adapter） | 起動後に時間経過や利用量で変化するため起動時には決められない |
| 起動後の設定差替え（決定IDの変更等） | 呼出し時に拒否（`AI_EXTERNAL_SEND_DECISION_CHANGED`） | 承認は起動時に固定し、実行中の差替えを新しい承認として扱わない（再起動が必要） |

prod 以外のprofileでは起動時検査は動かないが、gateway が呼出しごとに同じ評価を再実行するため、承認設定がなければ送信されない。

### 1.3 多層の束縛点

| 層 | class | 内容 |
|---|---|---|
| 純粋評価 | `service/ai/AiExternalSendApproval` | 承認設定を固定コードで評価し、承認済みsnapshotを返す（I/Oなし、値をメッセージへ出さない） |
| 総合判定 | `service/ai/AiProductionApprovalGate#externalSendStatus` / `#assertProviderAllowed(provider, purpose)` | flag・provider・チェックリスト・承認設定・用途のAND |
| 起動時 | `config/AiProductionSafetyValidator` | prod で external-send=true の場合、総合判定が不成立なら起動失敗 |
| 費用 | `service/ai/copilot/summary/ApprovedExternalSendCostPolicy` | prompt byte数から上限見積りを出し、prompt上限・1回上限・request予算を検査 |
| 累計 | `service/ai/AiExternalSendBudgetLedger` | JST日次/月次の予約・精算・取消（プロセス内） |
| 監査 | `service/ai/AiExternalSendAuditor` | 既存 `t_audit_log` へ送信前・送信後・拒否を記録 |
| gateway | `service/ai/impl/AiExecutionGatewayImpl` | 起動時に承認を固定し、呼出し時に再束縛・予約・必須監査を行ってから provider を呼ぶ |
| adapter | `service/ai/impl/GeminiTextServiceImpl#allowsApprovedDispatch` | 承認policyの証拠（policy ID、決定IDに束縛した proof identity、config hash、1回上限）を送信直前に再確認。artifact の config hash も決定IDへ束縛する |
| transport | `CopilotBoundedHttpTransport`（既存） | 宛先host完全一致・DNS snapshot・応答body上限は従来通り |

## 2. 承認設定の項目一覧（`ai.external-send-approval.*`）

値は署名済み決定書からの転記であり、秘密値は置かない。API key は従来通り secret manager から `ai.api-key` へ注入する（本書の範囲外）。

| property | 環境変数（application.yml） | 内容 | 検査 |
|---|---|---|---|
| `decision-id` | `AI_EXTERNAL_SEND_DECISION_ID` | model・用途・宛先・費用上限を束ねる署名済み決定ID | 必須。`[A-Za-z0-9][A-Za-z0-9._:-]{3,63}`。TBD/TODO/PENDING/UNSIGNED/DRAFT 等の未署名表記を拒否 |
| `dg08-decision-id` | `AI_EXTERNAL_SEND_DG08_DECISION_ID` | DG-08 決定ID | 同上 |
| `nf07-policy-id` | `AI_EXTERNAL_SEND_NF07_POLICY_ID` | NF07 privacy policy 承認ID | 同上 |
| `dpa-reference` | `AI_EXTERNAL_SEND_DPA_REFERENCE` | provider DPA 締結の参照ID | 同上。4つのIDは大文字小文字を無視して全て異なること |
| `signed-on` | `AI_EXTERNAL_SEND_SIGNED_ON` | 署名日（yyyy-MM-dd） | 必須。未来日は拒否 |
| `expires-on` | `AI_EXTERNAL_SEND_EXPIRES_ON` | 失効日（yyyy-MM-dd） | 必須。署名日より後、署名日から366日以内。JST当日が失効日以降なら拒否 |
| `provider` | `AI_EXTERNAL_SEND_PROVIDER` | 承認済みprovider | 必須。現時点で `gemini` のみ。`ai.provider` と一致 |
| `model` | `AI_EXTERNAL_SEND_MODEL` | 承認済みmodel ID | 必須。`[A-Za-z0-9._-]{1,64}`。`ai.model` と完全一致（adapter既定modelへの暗黙依存を禁止） |
| `purposes` | `AI_EXTERNAL_SEND_PURPOSES` | 承認済み用途（カンマ区切り） | 必須。`MANAGEMENT_COPILOT` 以外を含めると拒否 |
| `destination-host` | `AI_EXTERNAL_SEND_DESTINATION_HOST` | 承認済み送信先host | 必須。provider の allow-list（`generativelanguage.googleapis.com`）に含まれ、`ai.api-url` の宛先と一致 |
| `cost-limit.per-request-yen` | `AI_EXTERNAL_SEND_PER_REQUEST_YEN` | 1回の上限見積りの上限（円） | 必須、1以上 |
| `cost-limit.daily-yen` | `AI_EXTERNAL_SEND_DAILY_YEN` | JST暦日の累計上限（円） | 必須、1回上限以上 |
| `cost-limit.monthly-yen` | `AI_EXTERNAL_SEND_MONTHLY_YEN` | JST暦月の累計上限（円） | 必須、日次上限以上 |
| `cost-limit.input-yen-per-thousand-tokens` | `AI_EXTERNAL_SEND_INPUT_YEN_PER_THOUSAND_TOKENS` | 入力1,000 tokenあたり単価（円換算、為替・税の安全余裕込み） | 必須、0より大きく小数6桁以内。0円（free tier等）は承認しない |
| `cost-limit.output-yen-per-thousand-tokens` | `AI_EXTERNAL_SEND_OUTPUT_YEN_PER_THOUSAND_TOKENS` | 出力（thinking含む）1,000 tokenあたり単価 | 同上 |
| `cost-limit.max-request-bytes` | `AI_EXTERNAL_SEND_MAX_REQUEST_BYTES` | 1回の prompt 最大byte数（UTF-8） | 必須、1〜65536（bounded transport の送信上限） |
| `cost-limit.max-thinking-tokens` | `AI_EXTERNAL_SEND_MAX_THINKING_TOKENS` | thinking token 上限 | 0以上。既定0（thinkingを使わないmodel） |

関連する既存項目:

| property | 必要な値 |
|---|---|
| `ai.external-send-enabled`（`AI_EXTERNAL_SEND_ENABLED`） | true（既定 false） |
| `ai.provider` | `gemini` |
| `ai.model` | 承認 `model` と同じ値 |
| `ai.api-url` | 未設定、または `https://generativelanguage.googleapis.com`（path・query・userinfo・443以外のportは不可） |
| `ai.max-tokens` | 1以上（出力上限見積りに使う） |
| `ai.production-gates.*` | 10項目全て true（§3） |
| `ai.copilot.transport.hosts.*` | 承認済みhostの期限付き numeric-address snapshot（既存。運用者が別途配布） |

## 3. 運用者確認チェックリスト（10項目）との関係

`ai.production-gates.*` の10項目は「設定すれば承認」という単独の承認ではなくなり、**運用者が対応する証跡を確認したことを示す補助項目**に降格した。外部送信には必要条件（AND）として残るが、十分条件ではない。正本は承認設定（決定ID群）である。management copilot を local provider（mock/rule）で有効化する場合の前提条件としては従来通り使う。

| チェックリスト項目 | 対応する承認設定・証跡 |
|---|---|
| `owner-approved` | `decision-id`（Production Owner の署名） |
| `approved-catalog` | `purposes`（＋ approved catalog / `<APPROVED_SCOPE>` 決定） |
| `allowed-roles` | DG-08 の role 決定（`dg08-decision-id`）と `m_menu`/permission 設定 |
| `provider-contract` | `dpa-reference` |
| `nf07-approved` | `nf07-policy-id` |
| `dg08-approved` | `dg08-decision-id` |
| `existing-ai-production-gate` | `GATE-S17-G10-PROD` の判定記録 |
| `retention-approved` | `ai.retention.raw-prompt-days=0`（コードで強制）と NF07 の保持期間決定 |
| `cost-limit-approved` | `cost-limit.*`（Finance 承認） |
| `human-escalation` | DG-08 の human escalation 決定と運用連絡先 |

片側だけ成立（チェックリストだけ true、または承認設定だけ完全）の構成は prod 起動時に失敗する。

## 4. 外部送信ごとの監査

既存の `t_audit_log`（`AuditLogService#recordDomainEventRequired`）を使い、DDL は追加しない。

| application_code | 時点 | 必須/任意 |
|---|---|---|
| `AI_EXTERNAL_SEND_ATTEMPT` | provider I/O 直前（費用予約の後） | **必須**。永続化できなければ送信せず予約を戻す（`AI_EXTERNAL_SEND_AUDIT_UNAVAILABLE`） |
| `AI_EXTERNAL_SEND_RESULT` | 応答受領後・失敗後 | 成功時は**必須**（永続化できなければ応答を破棄）。失敗時は記録を試み、失敗しても送信前監査が残る |
| `AI_EXTERNAL_SEND_DENIED` | 承認済み構成での送信前拒否（費用上限、決定ID差替え、失効等） | best effort |

| 列 | 記録内容 |
|---|---|
| `username` / `actor_type` / `confirmation_source` / `human_user_id` | 呼出主体（`ExecutionActorContext`。人間は HUMAN/MANUAL_API、scheduler は SYSTEM/SCHEDULER_POLL） |
| `method` / `uri` | `AI_SEND` / `ai-external-send://<宛先host>/<用途>` |
| `reference_type` | `AI_EXTERNAL_SEND_DECISION` |
| `before_state` | `decision=<決定ID>;purpose=<用途>;provider=<provider>;model=<model>` |
| `after_state`（ATTEMPT） | `bytes=<prompt byte数>;upperYen=<上限見積り>;dayYen=<予約後日次累計>;monthYen=<予約後月次累計>` |
| `after_state`（RESULT/DENIED） | `outcome=<NOT_STARTED/MAY_HAVE_BEEN_SENT/RESPONSE_RECEIVED>;reason=<理由コード>;bytes=;inTok=;outTok=;thinkTok=;totTok=;yen=;yenState=<ACTUAL/UPPER_BOUND/NOT_SENT/ACTUAL_OVER_BOUND>;ms=` |
| `status` / `success_flag` / `error_code` / `error_category` | 結果、失敗時は理由コードと `AI_EXTERNAL_SEND` |
| `correlation_id` / `idempotency_key` | trace ID / 送信attempt ID（ATTEMPT と RESULT を対応付ける） |

記録しないもの: prompt 原文、allow-list の値、PII、untrusted text、provider 応答本文、API key・header。全ての値は識別子用の固定文字集合（`[A-Za-z0-9._:/-]`）へ正規化し、列長（`before_state`/`after_state` 255文字）以内に切り詰める。

## 5. 費用上限

- 上限見積り（円、切上げ）＝（prompt byte数 ＋ 256）× 入力単価 ÷ 1000 ＋（`ai.max-tokens` ＋ `max-thinking-tokens`）× 出力単価 ÷ 1000。「1 token ≥ 1 UTF-8 byte」を前提にした保守的な見積りであり、providerの課金単位との整合は料金単価の承認時に確認する。
- 拒否順序: prompt上限超過（`AI_EXTERNAL_SEND_REQUEST_TOO_LARGE`）→ 1回上限超過（`AI_EXTERNAL_SEND_REQUEST_COST_LIMIT`）→ request予算超過（`COPILOT_COST_BUDGET_EXCEEDED`）→ 日次/月次累計超過（`AI_EXTERNAL_SEND_DAILY_COST_LIMIT` / `AI_EXTERNAL_SEND_MONTHLY_COST_LIMIT`）。
- 累計: 送信直前に上限見積りを予約し、応答の usage から実費（cache も入力単価、thinking は出力単価で保守的に加算）へ精算する。usage 不明・送信の可能性がある失敗は上限見積りを費用として維持し、未送信が確定した失敗だけ予約を戻す。
- **制約（永続化が必要）**: 累計は単一プロセス内のメモリ計数であり、再起動で0に戻り、複数instance間で共有しない。上限を厳密に保証するには専用 ledger table（DDL）による永続計数が必要で、A-NF08-2（B2c-1/2 の durable claim / usage authority）と合わせて設計する。それまでは provider 側の請求上限・予算 alert を必ず併用し、単一instance運用とする。

## 6. 上線前に必要な署名・決定（未完）

| # | 署名・決定 | 責任主体 | 反映先 |
|---|---|---|---|
| S-1 | provider DPA（処理目的、学習利用 opt-out、ログ保持、越境・リージョン、再委託、削除、事故通知）の締結 | Privacy / Legal | `dpa-reference`、`provider-contract` |
| S-2 | DG-08 決定（provider、catalog owner、role、retention、cost、human escalation） | Production Owner / Security / Privacy | `dg08-decision-id`、`dg08-approved`、`allowed-roles`、`human-escalation` |
| S-3 | NF07 policy（PII inventory、allow-list、保持・purge、DSAR 取扱い） | Privacy Owner | `nf07-policy-id`、`nf07-approved`、`retention-approved` |
| S-4 | model・用途・宛先・費用上限を束ねる外部送信決定（決定ID・署名日・失効日） | Production Owner | `decision-id`、`signed-on`、`expires-on`、`provider`、`model`、`purposes`、`destination-host`、`owner-approved`、`approved-catalog` |
| S-5 | 料金単価（円換算・安全余裕）と1回/日次/月次上限、超過時の停止担当 | Finance / Owner | `cost-limit.*`、`cost-limit-approved` |
| S-6 | `GATE-S17-G10-PROD` 判定 | 既存AI production gate 責任者 | `existing-ai-production-gate` |
| S-7 | model リスク評価、secret vault の credential 発行・rotation・revoke 手順 | Security / CISO | `ai.api-key`（secret manager） |
| S-8 | DNS snapshot（numeric address）の配布と有効期限 | Security / SRE | `ai.copilot.transport.hosts.*` |
| S-9 | staging での外部送信観測（合成データのみ）と結果記録 | Operations | 有効化手順 §7 の前提 |
| S-10 | B2c-1/2（A-NF08-2）の Plan Review・実装・独立 Review | 開発 / Reviewer | 利用者経路での実送信解放の前提 |

署名が揃うまで、本番の `ai.external-send-enabled` は false のままとする。

## 7. 有効化・停止手順

### 7.1 有効化（全署名完了後）

1. §6 の S-1〜S-9 の記録（決定ID・署名日・失効日）を揃え、決定書と設定値の転記を二者で照合する。
2. secret manager で API key を発行し `ai.api-key` へ注入する（値を設定ファイル・ログ・チケットへ書かない）。
3. `ai.copilot.transport.hosts.*` の DNS snapshot を配布する。
4. `ai.external-send-approval.*` の全項目と `ai.provider=gemini`、`ai.model=<承認model>` を設定する。
5. `ai.production-gates.*` の10項目を、§3 の対応証跡を確認した上で true にする。
6. `ai.external-send-enabled=true` にして再起動する。承認設定・チェックリストのどれかが欠ければ起動時に失敗し、不足コード（例: `approval.decisionId.missing`、`checklist.DG08`）が表示される。値そのものは表示されない。
7. config hash が決定IDに束縛されるため、Copilot artifact（`t_ai_artifact_version`）の provider/model/prompt version/config hash を新しい値で登録し直す。
8. 起動後、`t_audit_log` で `application_code LIKE 'AI_EXTERNAL_SEND_%'` を確認し、ATTEMPT と RESULT が `idempotency_key` で対になっていることを観測する。
9. 利用者経路での実送信は B2c-1/2 完了まで発生しない（§0）。解放は別の Plan Review / 実装 / Review を経る。

### 7.2 停止（緊急時を含む）

1. `ai.external-send-enabled=false` にして再起動する（最優先。承認設定は残してよい）。
2. 必要に応じて `ai.management-copilot-enabled=false`、`ai.provider=mock` に戻す。
3. credential を revoke する（provider 側と secret manager の両方）。
4. 失効日を待たずに承認を取り消す場合は `decision-id` を外す（起動時に失敗するため、手順1と併せて行う）。
5. `t_audit_log` の `AI_EXTERNAL_SEND_RESULT` で `outcome=MAY_HAVE_BEEN_SENT` の件数・費用を provider 側の請求と照合する。

## 8. 対抗テスト（A-NF08-1、mock/fake/loopback のみ）

| class | 観点 |
|---|---|
| `AiExternalSendApprovalTest` | 承認設定の各項目欠落・未署名表記・不一致・失効・上限大小関係、違反コードに値を含めない |
| `AiProductionApprovalGateTest` | チェックリストだけでは不可、承認設定＋チェックリストで Copilot 用途のみ可、用途不明・legacy 用途・別provider・失効の拒否 |
| `AiProductionSafetyValidatorTest` | prod 起動時の fail-fast（承認設定欠落、チェックリスト不一致、local provider）、既定値での起動 |
| `AiExternalSendBudgetLedgerTest` | JST日次/月次上限、日付・月替わり、二重精算防止、実費精算、見積り超過 |
| `ApprovedExternalSendCostPolicyTest` | 上限見積りの算出、prompt上限・1回上限・予算0円の拒否、実費算出、B2c-0 境界（承認証拠で利用者経路の summary attempt を開始できない） |
| `AiExternalSendAuditorTest` | 監査列の固定書式、自由文・PII らしき値の正規化、列長、監査先なしの失敗、拒否監査の best effort |
| `CopilotExternalSendGateAdversarialTest` | prompt injection（TASK marker・HTML）、最小field、NEVER_SEND/非allow-list の除外、PII canary、承認なし/チェックリスト欠落/flag OFF の固定 deny-all、決定ID差替え、失効、費用上限、送信前監査失敗、送信後失敗の費用維持、未送信失敗の予約取消、legacy/取込用途の拒否、読取専用 preflight の無副作用、log へ prompt・応答本文を渡さない |
| `GeminiCopilotInvocationBoundaryTest` | 本番adapterの決定ID束縛（偽造証拠の拒否）、承認証拠でも test用 loopback transport は公開hostへ送らない、未承認adapterの固定 deny-all と config hash の区別 |

DNS/TLS/応答body上限・429/timeout は既存の `CopilotBoundedHttpTransportTest`、`CopilotBoundedHttpTransportTlsTest`、`CopilotBoundedJsonParserTest`、`CopilotGeminiResponseParserTest` が担う。実 provider・実 TLS issuer・実 credential での確認は §6 S-9 の staging 観測で行う。

## 9. 未解決事項

1. 費用累計の永続化（DDL）。§5 の制約を解消するには A-NF08-2 と合わせた専用 table が必要。
2. B2c-1/2（durable claim、dispatch/usage authority、fresh current-source drift 検査、commit 後 permit、公開 transport 迂回封鎖）。完了まで利用者経路の実送信は解放しない。
3. 理由コード（`AI_EXTERNAL_SEND_*`）の画面表示用 message key は未追加（既存 `COPILOT_*` と同様に code のまま表示される）。
4. 上限見積りの「1 token ≥ 1 byte」前提と thinking token の課金扱いは、採用 model の公式料金表で S-5 時に再確認する。
