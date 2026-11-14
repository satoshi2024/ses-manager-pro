# NF-08 外部送信承認ゲート（W2: external-send gate、W3-03: P2是正）

## 0. 状態

| 項目 | 状態 |
|---|---|
| 利用者決定 | B-NF08-1: 第1期は実 Gemini を接続する。B-NF08-2: 本番 external-send の固定拒否を「署名済み決定IDに束縛した承認設定」へ置き換え、外部送信ごとに監査を残す。Q-11: 費用上限は永続ledger（DDL、B2c-1と同じmigration）で保証する |
| 本書の範囲 | 承認設定ゲート、承認内容digest、送信前後の監査（REQUIRES_NEW）、承認の有効期間監視、最終gate（最小field・PII）、費用上限の計数と拒否、mock/fake/loopback での敵対的テスト |
| W3-03で是正したレビュー指摘 | P2-1（承認内容digestへの束縛と監査項目の補完）、P2-2（ATTEMPT監査のREQUIRES_NEW化）、P2-3（失効で起動を止めない）、P2-5（最終gateの最小field・PII検査）、理由コードのmessage key（4言語）。P2-4（永続ledger）は設計のみ（`.kiro/roadmap/2026-10-18-snf-nf08-c2-b2-technical-plan.md`「永続費用ledger」節、DDLはB2c-1） |
| 署名 | **未完**（DPA、DG-08、NF07 policy、model/用途/data category/費用の承認と承認digest）。したがって本番は既定で拒否のまま |
| 既定値 | `ai.external-send-enabled=false`、`ai.external-send-approval.*` 全て未設定、`ai.provider=mock`、`ai.management-copilot-enabled=false` |
| 実provider呼出し・実secret | 一切追加しない。テストは mock / fake adapter / test用 loopback transport のみ |
| 利用者経路での実送信 | **まだ発生しない**。B2c-0 の local-only summary attempt 境界（`CopilotSummaryPreflightCandidate.permitsLocalSummaryAttempt`）と予算0円の呼出しを維持する。解放は B2c-1/2（A-NF08-2、DDL・Plan Review 要）完了後 |

## 1. ゲート設計

### 1.1 判定式

外部送信（`ai.provider` が mock/rule 以外）は、次の全てを満たす場合だけ許可する。

```text
external-send-enabled
 ∧ provider が登録済みかつ非local
 ∧ 運用者確認チェックリスト（ai.production-gates.* 10項目）が全て true
 ∧ 承認設定（ai.external-send-approval.*）が完全かつ現行 ai.* 設定と一致
 ∧ 承認内容digest（approval-digest）が現行設定から再計算した値と一致し、起動時に固定した値とも一致
 ∧ 承認の有効期間内（JST業務日で 署名日 ≦ 当日 ＜ 失効日）
 ∧ 用途が承認用途に含まれる（現時点で受理するのは MANAGEMENT_COPILOT のみ）
 ∧ 宛先host・model・provider が承認値と一致
 ∧ 送信fieldが承認data categoryの範囲内、かつ送信field値とuntrusted textに未maskのPIIが無い（最終gate）
 ∧ 費用上限（1回・日次・月次）以内
 ∧ 送信前監査の永続化（外側transactionから独立したcommit）に成功
```

一つでも欠ければ送信しない（fail-closed）。承認設定がない（または起動時に不完全・失効・digest不一致だった）場合は従来の固定 deny-all policy（`ProductionDenyAllCopilotCostPolicy`、理由コード `COPILOT_COST_UNKNOWN`）がそのまま使われる。

### 1.2 起動時 fail-fast と呼出し時拒否の分担

| 検査 | 時点 | 理由 |
|---|---|---|
| 承認設定の完全性・形式・相互一致（決定ID群、日付の前後関係と366日上限、provider/model/宛先、data category、費用上限の大小関係、単価、承認digestの一致） | **prod 起動時に fail-fast**（`AiProductionSafetyValidator`） | 設定誤りを最初の利用者requestまで潜伏させず、deploy時点で検出する |
| チェックリスト10項目と承認設定の両立 | prod 起動時に fail-fast | 補助項目だけ true／承認設定だけ有り、の片側成立を起動時に止める |
| **失効日の経過・署名日前**（時間の経過だけで生じる不足） | **起動は継続し、外部送信だけ停止**（WARN log・監視metric） | 承認の失効で、AIと無関係な機能まで再起動のたびに使えなくなる事態を避ける（P2-3）。外部送信はgatewayが起動時に承認を固定しないため固定 deny-all になる |
| 失効日・費用累計・承認digest・request ごとの用途/宛先/model/byte数/field/PII | **呼出し時に拒否**（gateway・adapter） | 起動後に時間経過・利用量・設定差替えで変化するため |
| 起動後の設定差替え（決定ID・承認内容の変更） | 呼出し時に拒否（digestが決定書と不一致なら `AI_EXTERNAL_SEND_APPROVAL_DIGEST_MISMATCH`、再署名済みの別承認なら `AI_EXTERNAL_SEND_DECISION_CHANGED`） | 承認は起動時に固定し、実行中の差替えを新しい承認として扱わない（再起動が必要） |

失効・署名日前と構造的な不足が同時にある場合は、構造的な不足を優先して起動失敗とする（失効日を書き換えた設定はdigest不一致になるため、時間要因だけとはみなされない）。prod 以外のprofileでは起動時検査は動かないが、gateway が呼出しごとに同じ評価を再実行するため、承認設定がなければ送信されない。

### 1.3 多層の束縛点

| 層 | class | 内容 |
|---|---|---|
| 純粋評価 | `service/ai/AiExternalSendApproval` | 承認設定を固定コードで評価し、承認内容digestを再計算して決定書の値と照合し、承認済みsnapshot（digest込み）を返す（I/Oなし、値をメッセージへ出さない） |
| 総合判定 | `service/ai/AiProductionApprovalGate#externalSendStatus` / `#assertProviderAllowed(provider, purpose)` | flag・provider・チェックリスト・承認設定・用途のAND。不足のうち時間要因だけを `onlyTemporalViolations()`、起動を止める不足を `startupBlockingGates()` で区別する |
| 起動時 | `config/AiProductionSafetyValidator` | prod で external-send=true の場合、構造的な不足があれば起動失敗、時間要因だけなら起動継続＋WARN |
| 有効期間監視 | `service/ai/AiExternalSendApprovalMonitor` | 失効前警告・失効・署名日前・不完全を WARN log と Prometheus metric で知らせる（§7.3） |
| 最終gate | `service/ai/impl/AiExecutionGatewayImpl#assertExternalSendPayload` + `service/ai/AiPiiMasker` | 送信fieldを承認data categoryに限定し、送信field値とuntrusted textの未mask PIIを拒否する（§4） |
| 費用 | `service/ai/copilot/summary/ApprovedExternalSendCostPolicy` | prompt byte数から上限見積りを出し、prompt上限・1回上限・request予算を検査。proof identityは決定IDと承認digestに束縛 |
| 累計 | `service/ai/AiExternalSendBudgetLedger` | JST日次/月次の予約・精算・取消（プロセス内。時計の逆行では累計を戻さない） |
| 監査 | `service/ai/AiExternalSendAuditor` + `AuditLogServiceImpl#recordDomainEventRequiredInNewTransaction` | 既存 `t_audit_log` へ送信前・送信後・拒否をREQUIRES_NEWで記録（§5） |
| gateway | `service/ai/impl/AiExecutionGatewayImpl` | 起動時に承認を固定し、呼出し時に再束縛・最終gate・予約・必須監査を行ってから provider を呼ぶ |
| adapter | `service/ai/impl/GeminiTextServiceImpl#allowsApprovedDispatch` | 承認policyの証拠（policy ID、決定IDと承認digestに束縛した proof identity、config hash、1回上限）を送信直前に再確認。artifact の config hash も決定IDと承認digestへ束縛する |
| transport | `CopilotBoundedHttpTransport`（既存） | 宛先host完全一致・DNS snapshot・応答body上限は従来通り |

## 2. 承認設定の項目一覧（`ai.external-send-approval.*`）

値は署名済み決定書からの転記であり、秘密値は置かない。API key は従来通り secret manager から `ai.api-key` へ注入する（本書の範囲外）。

| property | 環境変数（application.yml） | 内容 | 検査 |
|---|---|---|---|
| `decision-id` | `AI_EXTERNAL_SEND_DECISION_ID` | model・用途・data category・宛先・費用上限を束ねる署名済み決定ID | 必須。`[A-Za-z0-9][A-Za-z0-9._:-]{3,63}`。TBD/TODO/PENDING/UNSIGNED/DRAFT 等の未署名表記を拒否 |
| `dg08-decision-id` | `AI_EXTERNAL_SEND_DG08_DECISION_ID` | DG-08 決定ID | 同上 |
| `nf07-policy-id` | `AI_EXTERNAL_SEND_NF07_POLICY_ID` | NF07 privacy policy 承認ID | 同上 |
| `dpa-reference` | `AI_EXTERNAL_SEND_DPA_REFERENCE` | provider DPA 締結の参照ID | 同上。4つのIDは大文字小文字を無視して全て異なること |
| `signed-on` | `AI_EXTERNAL_SEND_SIGNED_ON` | 署名日（yyyy-MM-dd） | 必須。未来日なら外部送信を停止（起動は継続） |
| `expires-on` | `AI_EXTERNAL_SEND_EXPIRES_ON` | 失効日（yyyy-MM-dd） | 必須。署名日より後、署名日から366日以内。JST当日が失効日以降なら外部送信を停止（起動は継続） |
| `provider` | `AI_EXTERNAL_SEND_PROVIDER` | 承認済みprovider | 必須。現時点で `gemini` のみ。`ai.provider` と一致 |
| `model` | `AI_EXTERNAL_SEND_MODEL` | 承認済みmodel ID | 必須。`[A-Za-z0-9._-]{1,64}`。`ai.model` と完全一致（adapter既定modelへの暗黙依存を禁止） |
| `purposes` | `AI_EXTERNAL_SEND_PURPOSES` | 承認済み用途（カンマ区切り） | 必須。ASCII `[A-Za-z_]` のみ（同形の非ASCII文字は拒否）。`MANAGEMENT_COPILOT` 以外を含めると拒否 |
| `data-categories` | `AI_EXTERNAL_SEND_DATA_CATEGORIES` | 送信してよいfield id（カンマ区切り） | 必須。`catalog.queryId`・`catalog.catalogVersion`・`summary.claimKeys` の部分集合だけを受理する（`bp.bpCompany`・`engineer.initialName`・`project.projectName` 等のlegacy allow-list項目は承認できない） |
| `destination-host` | `AI_EXTERNAL_SEND_DESTINATION_HOST` | 承認済み送信先host | 必須。provider の allow-list（`generativelanguage.googleapis.com`）に含まれ、`ai.api-url` の宛先と一致 |
| `cost-limit.per-request-yen` | `AI_EXTERNAL_SEND_PER_REQUEST_YEN` | 1回の上限見積りの上限（円） | 必須、1以上 |
| `cost-limit.daily-yen` | `AI_EXTERNAL_SEND_DAILY_YEN` | JST暦日の累計上限（円） | 必須、1回上限以上 |
| `cost-limit.monthly-yen` | `AI_EXTERNAL_SEND_MONTHLY_YEN` | JST暦月の累計上限（円） | 必須、日次上限以上 |
| `cost-limit.input-yen-per-thousand-tokens` | `AI_EXTERNAL_SEND_INPUT_YEN_PER_THOUSAND_TOKENS` | 入力1,000 tokenあたり単価（円換算、為替・税の安全余裕込み） | 必須、0より大きく小数6桁以内。0円（free tier等）は承認しない |
| `cost-limit.output-yen-per-thousand-tokens` | `AI_EXTERNAL_SEND_OUTPUT_YEN_PER_THOUSAND_TOKENS` | 出力（thinking含む）1,000 tokenあたり単価 | 同上 |
| `cost-limit.max-request-bytes` | `AI_EXTERNAL_SEND_MAX_REQUEST_BYTES` | 1回の prompt 最大byte数（UTF-8） | 必須、1〜65536（bounded transport の送信上限） |
| `cost-limit.max-thinking-tokens` | `AI_EXTERNAL_SEND_MAX_THINKING_TOKENS` | thinking token 上限 | 0以上。既定0（thinkingを使わないmodel） |
| `approval-digest` | `AI_EXTERNAL_SEND_APPROVAL_DIGEST` | 決定書に記載された承認内容のSHA-256（小文字hex 64桁。大文字も可） | 必須。§2.1 の正規形から再計算した値と一致しなければ承認しない |

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
| `ai.external-send-monitor.expiry-warning-days`（`AI_EXTERNAL_SEND_EXPIRY_WARNING_DAYS`） | 失効の何日前から警告するか（既定30、1〜366に丸める。承認内容ではないためdigestに含めない） |

### 2.1 承認内容digestの正規形（P2-1）

同じ決定IDのまま上限・用途・data category・期限等を書き換えた設定を検出するため、署名者は承認内容から次の正規形を作り、そのSHA-256を決定書へ記載する。運用者はその値を `approval-digest` へ転記する。アプリは現行設定から同じ正規形を再計算し、一致しなければ承認しない（prod起動時はfail-fast、実行中は `AI_EXTERNAL_SEND_APPROVAL_DIGEST_MISMATCH`）。

正規形はUTF-8で、1行目が版（`nf08-external-send-approval-v1`）、以降が下表の固定順の `key=value`。**全ての行をLFで終える（最終行も）**。

| 順 | key | 値の正規化 |
|---|---|---|
| 1 | `decision-id` | 前後空白を除いた値（大文字小文字は保持） |
| 2 | `dg08-decision-id` | 同上 |
| 3 | `nf07-policy-id` | 同上 |
| 4 | `dpa-reference` | 同上 |
| 5 | `signed-on` | `yyyy-MM-dd` |
| 6 | `expires-on` | `yyyy-MM-dd` |
| 7 | `provider` | 小文字 |
| 8 | `model` | 前後空白を除いた値 |
| 9 | `purposes` | 大文字、昇順、カンマ区切り（空白なし） |
| 10 | `data-categories` | 昇順（文字コード順）、カンマ区切り（空白なし） |
| 11 | `destination-host` | 小文字 |
| 12〜14 | `cost-limit.per-request-yen` / `cost-limit.daily-yen` / `cost-limit.monthly-yen` | 10進整数 |
| 15〜16 | `cost-limit.input-yen-per-thousand-tokens` / `cost-limit.output-yen-per-thousand-tokens` | 末尾の0を除いた10進表記（`1.50`→`1.5`、`1.000`→`1`、`100`→`100`） |
| 17〜18 | `cost-limit.max-request-bytes` / `cost-limit.max-thinking-tokens` | 10進整数 |

算出例（合成値。`AiExternalSendApprovalTest` の固定値と同じ）:

```sh
printf 'nf08-external-send-approval-v1\ndecision-id=NF08-EXT-2026-0001\ndg08-decision-id=DG08-2026-0042\nnf07-policy-id=NF07-POL-2026-0007\ndpa-reference=DPA-GCP-2026-0003\nsigned-on=2026-11-12\nexpires-on=2027-02-11\nprovider=gemini\nmodel=gemini-synthetic-model\npurposes=MANAGEMENT_COPILOT\ndata-categories=catalog.catalogVersion,catalog.queryId,summary.claimKeys\ndestination-host=generativelanguage.googleapis.com\ncost-limit.per-request-yen=50\ncost-limit.daily-yen=100\ncost-limit.monthly-yen=1000\ncost-limit.input-yen-per-thousand-tokens=1\ncost-limit.output-yen-per-thousand-tokens=4\ncost-limit.max-request-bytes=8192\ncost-limit.max-thinking-tokens=0\n' | sha256sum
# a467bc9955f3827dfd7a8465a560562c8221e792bbee578c1aa4cccd519a6e6c
```

digestは次の3か所に束縛する。

| 束縛先 | 内容 |
|---|---|
| 監査 `before_state` | `decision=<決定ID>;digest=<64桁>;purpose=;model=`（監査行から決定書を特定できる） |
| proof identity | `approved-external-send:<決定ID>:<digest先頭32桁>`（adapterは同じ値でなければ送信しない。run metadataの識別子上限128文字に収める） |
| Copilot artifact の config hash | cost-policy marker を `approved-external-send-v1:<決定ID>:<digest>` とする（承認内容を変えたらartifactの再登録が必要） |

## 3. 運用者確認チェックリスト（10項目）との関係

`ai.production-gates.*` の10項目は「設定すれば承認」という単独の承認ではなく、**運用者が対応する証跡を確認したことを示す補助項目**である。外部送信には必要条件（AND）として残るが、十分条件ではない。正本は承認設定（決定ID群と承認digest）である。management copilot を local provider（mock/rule）で有効化する場合の前提条件としては従来通り使う。

| チェックリスト項目 | 対応する承認設定・証跡 |
|---|---|
| `owner-approved` | `decision-id`・`approval-digest`（Production Owner の署名） |
| `approved-catalog` | `purposes`・`data-categories`（＋ approved catalog / `<APPROVED_SCOPE>` 決定） |
| `allowed-roles` | DG-08 の role 決定（`dg08-decision-id`）と `m_menu`/permission 設定 |
| `provider-contract` | `dpa-reference` |
| `nf07-approved` | `nf07-policy-id`（data category の PII 判断を含む） |
| `dg08-approved` | `dg08-decision-id` |
| `existing-ai-production-gate` | `GATE-S17-G10-PROD` の判定記録 |
| `retention-approved` | `ai.retention.raw-prompt-days=0`（コードで強制）と NF07 の保持期間決定 |
| `cost-limit-approved` | `cost-limit.*`（Finance 承認） |
| `human-escalation` | DG-08 の human escalation 決定と運用連絡先 |

片側だけ成立（チェックリストだけ true、または承認設定だけ完全）の構成は prod 起動時に失敗する。

## 4. 最終gate（最小field・PII、P2-5）

`AiExecutionGatewayImpl#assertExternalSendPayload` は、承認済み外部送信の経路で、費用の予約・監査・provider呼出しより前に次を強制する。呼出元（`CopilotSummaryService` 等）が最小fieldだけを渡す約束に依存しない。読取専用 preflight も同じ検査を通す（拒否監査は書かない）。

| 検査 | 内容 | 拒否コード |
|---|---|---|
| 最小field | `AiPiiMasker.mask` 後の送信fieldが承認 `data-categories`（⊆ `AiPiiMasker.COPILOT_SEND_FIELDS`）に収まること。`bp.bpCompany`・`engineer.initialName`・`project.projectName` 等、legacy matching 用 allow-list では送れる項目もここで拒否する | `AI_EXTERNAL_SEND_FIELD_NOT_APPROVED`（400） |
| 未mask PII | 送信field値と sanitize 済み untrusted text を `AiPiiMasker.findUnmaskedPii` で検査する。NFKC正規化後に、mail・電話（全角・各種ハイフン含む）・〒付き郵便番号・敬称付きの人名（様/さん/氏/殿/くん/ちゃん、Mr./Ms. 等）・法人格付きの会社名（株式会社・(株)・Co., Ltd. 等）、および送信契約外として外したfield値（氏名・会社名・電話等）の再出現（空白・大文字小文字を無視）を検出する | `AI_EXTERNAL_SEND_UNMASKED_PII`（400） |

方針:

- 自動でmaskせず拒否する（自由文の自動maskは取りこぼしを検証できないため）。伏せ字（`***`、イニシャル）は検出しない。
- 検出は誤検出を拒否側に倒す（例: 「お客様」「皆様」「皆さん」「氏名」は人名とみなさないが、それ以外の敬称付き語は拒否）。現行の利用者経路（`CopilotSummaryService`）は untrusted text を渡さないため、誤検出による利用者影響はない。
- log と監査には検出種別のコード（`EMAIL`・`PHONE`・`POSTAL_CODE`・`PERSON_HONORIFIC`・`CORPORATE_NAME`・`WITHHELD_FIELD_VALUE`）と理由コードだけを出し、値は出さない。
- PII canary（`AiGatewayRequest.CANARY`）の拒否は従来通り全用途で先に行う（監査なし）。

## 5. 外部送信ごとの監査（P2-1・P2-2）

既存の `t_audit_log` を使い、DDL は追加しない。

| application_code | 時点 | 必須/任意 |
|---|---|---|
| `AI_EXTERNAL_SEND_ATTEMPT` | provider I/O 直前（費用予約の後） | **必須**。永続化できなければ送信せず予約を戻す（`AI_EXTERNAL_SEND_AUDIT_UNAVAILABLE`、NOT_STARTED） |
| `AI_EXTERNAL_SEND_RESULT` | 応答受領後・失敗後 | 成功時は**必須**（永続化できなければ応答を破棄し、費用は実費で精算済み）。失敗時は記録を試み、失敗しても送信前監査が残る |
| `AI_EXTERNAL_SEND_DENIED` | 承認済み構成での送信前拒否（費用上限、digest不一致、決定差替え、失効、最終gate 等） | best effort |

| 列 | 記録内容 |
|---|---|
| `username` / `actor_type` / `confirmation_source` / `human_user_id` | 呼出主体（`ExecutionActorContext`。人間は HUMAN/MANUAL_API、scheduler は SYSTEM/SCHEDULER_POLL） |
| `method` | `AI_SEND` |
| `uri` | `ai-external-send://<宛先host>/<用途>?provider=<provider>&tenant=<tenant>&le=<法人ID>&query=<queryId>&artifact=<artifact version ID>&cfg=<config hash>`（各識別子は64文字以内に切り詰め、全体500文字以内） |
| `reference_type` | `AI_EXTERNAL_SEND_DECISION` |
| `before_state` | `decision=<決定ID>;digest=<承認digest 64桁>;purpose=<用途>;model=<model>`（243文字以内） |
| `after_state`（ATTEMPT） | `bytes=<prompt byte数>;upperYen=<上限見積り>;dayYen=<予約後日次累計>;monthYen=<予約後月次累計>` |
| `after_state`（RESULT/DENIED） | `outcome=<NOT_STARTED/MAY_HAVE_BEEN_SENT/RESPONSE_RECEIVED>;reason=<理由コード>;bytes=;inTok=;outTok=;thinkTok=;totTok=;yen=;yenState=<ACTUAL/UPPER_BOUND/NOT_SENT/ACTUAL_OVER_BOUND>;ms=` |
| `status` / `success_flag` / `error_code` / `error_category` | 結果、失敗時は理由コードと `AI_EXTERNAL_SEND` |
| `correlation_id` / `idempotency_key` | trace ID / 送信attempt ID（ATTEMPT と RESULT を対応付ける） |

記録しないもの: prompt 原文、allow-list の値、PII、untrusted text、provider 応答本文、API key・header。識別子は固定文字集合（`[A-Za-z0-9._:/-]`）へ正規化し、列長以内に切り詰める（数字・`-`・`:` は残るため、PIIを識別子として渡さないことは呼出側の責務。現行は設定値・UUID・固定コード・tenant/法人/queryIdだけを渡す）。

### 5.1 永続性（REQUIRES_NEW）と書込み失敗時の方針

- 監査は `AuditLogServiceImpl#recordDomainEventRequiredInNewTransaction`（`@Transactional(propagation = REQUIRES_NEW)`、新規method）で別transactionとしてcommitする。外側transaction（例: B2c-1 の durable claim）が後でrollbackしても、送信した事実の記録は残る。既存の `recordDomainEventRequired`（呼出側transactionへ参加）の意味は変えていない（D1 bootstrap 等の必須監査が依存）。
- 独立transactionは Spring proxy 経由でだけ成立する。新methodは、自分のtransaction内（transaction名がこのmethod）で呼ばれていなければ記録せず失敗する（proxyを通らない自己呼出し・手動構築で外側transactionへ参加してしまう経路を拒否）。
- `AiExternalSendAuditor` は、監査serviceが proxy の `AuditLogServiceImpl` ならREQUIRES_NEWの経路を使う。それ以外（mock・手動構築）は、外側transactionが無い（insertがauto-commitで即時確定する）場合に限り既存methodで記録し、外側transactionがある場合は記録せず失敗させる。

| 失敗 | 扱い |
|---|---|
| ATTEMPTを独立commitできない（DB障害、insert件数≠1、独立transactionを確保できない、REQUIRES_NEW用の2本目のconnectionをpoolから取得できず `connection-timeout` まで待った、等） | **送信しない**。費用予約を戻し `AI_EXTERNAL_SEND_AUDIT_UNAVAILABLE`（NOT_STARTED）。WARN logは attempt ID と例外の型だけ |
| 成功応答後のRESULTを書けない | 応答を破棄して `AI_EXTERNAL_SEND_AUDIT_UNAVAILABLE`（RESPONSE_RECEIVED）。費用は実費（不明なら上限見積り）で精算済み。ATTEMPTは残っている |
| 失敗後のRESULTを書けない | 元の失敗をそのまま返す。WARN log（attempt ID・理由コード・例外の型）。ATTEMPTは残っているため、運用では ATTEMPT はあるが RESULT の無い attempt を「送信の可能性あり」として provider 請求と照合する |
| DENIEDを書けない | 拒否結果は変えない。WARN log（理由コード・例外の型） |

運用上の注意: REQUIRES_NEW は外側transactionのconnectionを保持したまま2本目を取得するため、外部送信を外側transaction内から呼ぶ場合は `spring.datasource.hikari.maximum-pool-size` に余裕が必要である（枯渇時は上表のとおり送信しない側に倒れる）。現行の呼出経路（`CopilotQueryService` / `CopilotSummaryService`）はtransaction外である。

## 6. 費用上限

- 上限見積り（円、切上げ）＝（prompt byte数 ＋ 256）× 入力単価 ÷ 1000 ＋（`ai.max-tokens` ＋ `max-thinking-tokens`）× 出力単価 ÷ 1000。「1 token ≥ 1 UTF-8 byte」を前提にした保守的な見積りであり、providerの課金単位との整合は料金単価の承認時に確認する。
- 拒否順序: prompt上限超過（`AI_EXTERNAL_SEND_REQUEST_TOO_LARGE`）→ 1回上限超過（`AI_EXTERNAL_SEND_REQUEST_COST_LIMIT`）→ request予算超過（`COPILOT_COST_BUDGET_EXCEEDED`）→ 日次/月次累計超過（`AI_EXTERNAL_SEND_DAILY_COST_LIMIT` / `AI_EXTERNAL_SEND_MONTHLY_COST_LIMIT`）。
- 累計: 送信直前に上限見積りを予約し、応答の usage から実費（cache も入力単価、thinking は出力単価で保守的に加算）へ精算する。usage 不明・送信の可能性がある失敗は上限見積りを費用として維持し、未送信が確定した失敗だけ予約を戻す。時計が戻っても記録済みの日付・月の累計を0へ戻さない。
- **制約（永続化が必要）**: 累計は単一プロセス内のメモリ計数であり、再起動で0に戻り、複数instance間で共有しない。利用者決定Q-11により、専用の永続費用ledger table（DDL、B2c-1 と同じmigration、版番号は未割当で暫定V191）で上限を保証する。設計は技術計画「永続費用ledger」節。それまでは単一instance運用とし、provider側の**硬い上限（GCP API quota：1日のrequest数・1分のtoken数）**を併用する。GCPの予算alertは通知だけで送信を止めないため、上限の代わりにならない。

## 7. 有効化・停止・有効期間の運用

### 7.1 有効化（全署名完了後）

1. §8 の S-1〜S-9 の記録（決定ID・署名日・失効日・承認digest）を揃え、決定書と設定値の転記を二者で照合する。承認digestは §2.1 の正規形を決定書の値から二者がそれぞれ作り、同じ値になることを確認する。
2. secret manager で API key を発行し `ai.api-key` へ注入する（値を設定ファイル・ログ・チケットへ書かない）。
3. `ai.copilot.transport.hosts.*` の DNS snapshot を配布する。
4. `ai.external-send-approval.*` の全項目（`data-categories`・`approval-digest` を含む）と `ai.provider=gemini`、`ai.model=<承認model>` を設定する。
5. `ai.production-gates.*` の10項目を、§3 の対応証跡を確認した上で true にする。
6. `ai.external-send-enabled=true` にして再起動する。承認設定・チェックリスト・digestのどれかが欠ければ起動時に失敗し、不足コード（例: `approval.decisionId.missing`、`approval.approvalDigest.mismatch`、`checklist.DG08`）が表示される。値そのものは表示されない。
7. config hash が決定IDと承認digestに束縛されるため、Copilot artifact（`t_ai_artifact_version`）の provider/model/prompt version/config hash を新しい値で登録し直す。
8. 起動後、`t_audit_log` で `application_code LIKE 'AI_EXTERNAL_SEND_%'` を確認し、ATTEMPT と RESULT が `idempotency_key` で対になっていること、`before_state` の digest が決定書と一致することを観測する。
9. 利用者経路での実送信は B2c-1/2 完了まで発生しない（§0）。解放は別の Plan Review / 実装 / Review を経る。

### 7.2 停止（緊急時を含む）

1. `ai.external-send-enabled=false` にして再起動する（最優先。承認設定は残してよい）。
2. 必要に応じて `ai.management-copilot-enabled=false`、`ai.provider=mock` に戻す。
3. credential を revoke する（provider 側と secret manager の両方）。
4. 失効日を待たずに承認を取り消す場合は `decision-id` を外す（起動時に失敗するため、手順1と併せて行う）。
5. `t_audit_log` の `AI_EXTERNAL_SEND_RESULT` で `outcome=MAY_HAVE_BEEN_SENT` の件数・費用、および RESULT の無い ATTEMPT を provider 側の請求と照合する。

### 7.3 有効期間の監視と失効時の動作（P2-3）

| 状態（`AiExternalSendApprovalMonitor.State`） | 条件 | 外部送信 | 通知 |
|---|---|---|---|
| `DISABLED` | `external-send-enabled=false` | なし | なし |
| `VALID` | 承認済みで、失効まで警告日数より多い | 可 | 起動時にINFO |
| `EXPIRING` | 承認済みだが、失効まで `expiry-warning-days` 以内 | 可 | WARN（JST日付ごとに1回） |
| `EXPIRED` | 失効日以降 | **停止**（起動は継続） | WARN（JST日付ごとに1回） |
| `NOT_YET_VALID` | 署名日前 | **停止**（起動は継続） | WARN |
| `INCOMPLETE` | 承認設定・チェックリストが不完全 | 停止 | WARN（prodでは構造的な不足があると起動自体が失敗する） |

- metric（`/actuator/prometheus`、scrape時に評価）: `ses_ai_external_send_approval_state{state="..."}`（現在の状態だけ1）、`ses_ai_external_send_approval_days_remaining`（失効日までのJST暦日数、未設定はNaN）。tagは状態名だけで、決定ID・tenant・PIIを載せない。
- WARN log: 固定コード `AI_EXTERNAL_SEND_APPROVAL_STATE`（状態・残日数・警告日数だけ）。起動時と、scrape時に状態またはJST日付が変わった時に出す（schedulerは追加しない）。prod起動時に時間要因だけで外部送信を止めた場合は `AI_EXTERNAL_SEND_STOPPED_AT_STARTUP` も出す。
- 推奨alert（SRE設定）: `ses_ai_external_send_approval_state{state="EXPIRING"} == 1` で再承認の起票、`state="EXPIRED"`・`"NOT_YET_VALID"`・`"INCOMPLETE"` が1なら運用担当へ即時通知。
- 失効時の手順: (1) 再承認（新しい決定ID・署名日・失効日・digest）を得る。(2) §7.1 の手順4〜8で設定を差し替えて再起動する（実行中の差替えは `AI_EXTERNAL_SEND_DECISION_CHANGED` で拒否され、再起動まで有効にならない）。(3) 再承認を待たない場合は `ai.external-send-enabled=false` にする（再起動時のWARNを止める）。
- 失効中も保存期間のpurge（`CopilotFeatureGate#assertRetentionMaintenanceAllowed`）は止めない（構造的な不足がある場合だけ止める）。

## 8. 本番リリース前に必要な署名・決定（未完）

| # | 署名・決定 | 責任主体 | 反映先 |
|---|---|---|---|
| S-1 | provider DPA（処理目的、学習利用 opt-out、ログ保持、越境・リージョン、再委託、削除、事故通知）の締結 | Privacy / Legal | `dpa-reference`、`provider-contract` |
| S-2 | DG-08 決定（provider、catalog owner、role、retention、cost、human escalation） | Production Owner / Security / Privacy | `dg08-decision-id`、`dg08-approved`、`allowed-roles`、`human-escalation` |
| S-3 | NF07 policy（PII inventory、allow-list、data category の PII 判断、保持・purge、DSAR 取扱い） | Privacy Owner | `nf07-policy-id`、`nf07-approved`、`retention-approved`、`data-categories` |
| S-4 | model・用途・data category・宛先・費用上限を束ねる外部送信決定（決定ID・署名日・失効日・承認digest） | Production Owner | `decision-id`、`signed-on`、`expires-on`、`provider`、`model`、`purposes`、`data-categories`、`destination-host`、`approval-digest`、`owner-approved`、`approved-catalog` |
| S-5 | 料金単価（円換算・安全余裕）と1回/日次/月次上限、超過時の停止担当、GCP API quota の硬い上限値 | Finance / Owner | `cost-limit.*`、`cost-limit-approved` |
| S-6 | `GATE-S17-G10-PROD` 判定 | 既存AI production gate 責任者 | `existing-ai-production-gate` |
| S-7 | model リスク評価、secret vault の credential 発行・rotation・revoke 手順 | Security / CISO | `ai.api-key`（secret manager） |
| S-8 | DNS snapshot（numeric address）の配布と有効期限 | Security / SRE | `ai.copilot.transport.hosts.*` |
| S-9 | staging での外部送信観測（合成データのみ）と結果記録、失効前alertの設定 | Operations / SRE | 有効化手順 §7 の前提 |
| S-10 | B2c-1/2（A-NF08-2）と永続費用ledgerの Plan Review・実装・独立 Review | 開発 / Reviewer | 利用者経路での実送信解放の前提 |

署名が揃うまで、本番の `ai.external-send-enabled` は false のままとする。

## 9. 敵対的テスト（A-NF08-1、mock/fake/loopback のみ）

| class | 観点 |
|---|---|
| `AiExternalSendApprovalTest` | 承認設定の各項目欠落・未署名表記・不一致・失効・上限大小関係・data category・承認digest（欠落・形式・不一致、正規形の固定値、同じ決定IDでの内容変更）、時間要因の分類、有効期間の監視状態、違反コードに値を含めない |
| `AiProductionApprovalGateTest` | チェックリストだけでは不可、承認設定＋チェックリストで Copilot 用途のみ可、用途不明・legacy 用途・別provider・失効・digest不一致の拒否、時間要因だけの不足と構造的な不足の区別 |
| `AiProductionSafetyValidatorTest` | prod 起動時の fail-fast（承認設定欠落、チェックリスト不一致、local provider、digest不一致、失効日の書換え）、失効・署名日前では起動を継続しWARN、既定値での起動 |
| `AiExternalSendApprovalMonitorTest` | VALID/EXPIRING/EXPIRED/NOT_YET_VALID/INCOMPLETE/DISABLED の判定、metric、WARNの日付ごとの重複抑止、警告日数の丸め |
| `AiExternalSendBudgetLedgerTest` | JST日次/月次上限、日付・月替わり、時計の逆行で累計を戻さない、二重精算防止、実費精算、見積り超過 |
| `ApprovedExternalSendCostPolicyTest` | 上限見積りの算出、prompt上限・1回上限・予算0円の拒否、実費算出、決定ID＋digestのproof identity、B2c-0 境界 |
| `AiExternalSendAuditorTest` | 監査列の固定書式（digest・送信範囲）、自由文・PII らしき値の正規化、列長、監査先なしの失敗、外側transaction内で独立transactionを使えない監査先の拒否、拒否監査の best effort |
| `AiExternalSendAuditorH2PersistenceTest` | 実H2へのinsert、外側rollback後もATTEMPT/RESULT/DENIEDが残ること、既存methodは外側rollbackで消えること、proxyを通らない呼出しの拒否 |
| `AiExternalSendAuditMySqlIntegrationTest`（`mysql`） | 実MySQL（Flyway schema・CHECK制約・strict mode）でのREQUIRES_NEW独立commitと列長上限 |
| `CopilotExternalSendGateAdversarialTest` | prompt injection、最小field、`bp.bpCompany` 等のdata category外、untrusted text の連絡先・敬称付き人名・法人格付き会社名・送信契約外field値の再出現、送信field値のPII、伏せ字の通過、PII canary、承認なし/チェックリスト欠落/flag OFF/digest不一致の固定 deny-all、実行中のdigest不一致・再署名・失効、費用上限、送信前監査失敗、送信後失敗の費用維持、未送信失敗の予約取消、legacy/取込用途の拒否、読取専用 preflight の無副作用、log へ prompt・応答本文を渡さない |
| `GeminiCopilotInvocationBoundaryTest` | 本番adapterの決定ID・digest束縛（偽造証拠の拒否）、承認証拠でも test用 loopback transport は公開hostへ送らない、未承認adapterの固定 deny-all と config hash の区別（digestでも区別） |

DNS/TLS/応答body上限・429/timeout は既存の `CopilotBoundedHttpTransportTest`、`CopilotBoundedHttpTransportTlsTest`、`CopilotBoundedJsonParserTest`、`CopilotGeminiResponseParserTest` が担う。実 provider・実 TLS issuer・実 credential での確認は §8 S-9 の staging 観測で行う。

## 10. 理由コードとmessage key

理由コードは `BusinessException.of(code, key)` のmessage keyとして解決され、4言語（`messages.properties`・`_en`・`_ko`・`_zh_CN`）に訳文がある。訳文は設定値・PIIを含まない。

`AI_EXTERNAL_SEND_APPROVAL_INCOMPLETE`、`AI_EXTERNAL_SEND_PROVIDER_NOT_APPROVED`、`AI_EXTERNAL_SEND_PURPOSE_NOT_APPROVED`、`AI_EXTERNAL_SEND_NOT_APPROVED`、`AI_EXTERNAL_SEND_DECISION_CHANGED`、`AI_EXTERNAL_SEND_MODEL_NOT_APPROVED`、`AI_EXTERNAL_SEND_DESTINATION_NOT_APPROVED`、`AI_EXTERNAL_SEND_REQUEST_TOO_LARGE`、`AI_EXTERNAL_SEND_REQUEST_COST_LIMIT`、`AI_EXTERNAL_SEND_REQUEST_SIZE_UNKNOWN`、`AI_EXTERNAL_SEND_OUTPUT_UNBOUNDED`、`AI_EXTERNAL_SEND_COST_OVERFLOW`、`AI_EXTERNAL_SEND_DAILY_COST_LIMIT`、`AI_EXTERNAL_SEND_MONTHLY_COST_LIMIT`、`AI_EXTERNAL_SEND_AUDIT_UNAVAILABLE`、`COPILOT_COST_BUDGET_EXCEEDED`（以上W2の16件）、`AI_EXTERNAL_SEND_APPROVAL_DIGEST_MISMATCH`、`AI_EXTERNAL_SEND_FIELD_NOT_APPROVED`、`AI_EXTERNAL_SEND_UNMASKED_PII`（W3-03で追加）。

## 11. 未解決事項

1. 費用累計の永続化（DDL）。§6 の制約を解消する永続費用ledgerは技術計画で設計済み、実装はB2c-1（DDL承認・version割当後）。
2. B2c-1/2（durable claim、dispatch/usage authority、fresh current-source drift 検査、commit 後 permit、公開 transport 迂回封鎖）、B2d（cost capability）、B2e（故障matrix）。完了まで利用者経路の実送信は解放しない。
3. 上限見積りの「1 token ≥ 1 byte」前提と thinking token の課金扱いは、採用 model の公式料金表で S-5 時に再確認する（thinking budget を provider へ明示送信していない点はレビューP3-5として残る）。
4. レビューP3（adapter証拠の進程内偽造、識別子正規化がPIIを除けないこと、DENIED監査の量、未署名表記の不足、利用者へ返す理由コードの粒度）は本書の範囲外として残る。
