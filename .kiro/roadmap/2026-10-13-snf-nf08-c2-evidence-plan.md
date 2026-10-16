# NF08 C2 費用証拠・実DB永続化計画

## 目的と前提

- 対象HEAD: `3d95d3f57dd9a9a5da6e458b3fd07f8bf145f070` (`codex/snf-prod-nf08-summary-boundary`)。C1は18クラス・91件PASSとして受入済み。本書は次のC2実装のPLANであり、実装前にAstraの独立レビューを受ける。
- 既存のbounded adapter/transport、deny-all本番cost policy、feature flags OFF、synthetic loopbackを維持する。Gemini実送信、実credential/provider、実料金の登録、artifact publish、migration/DDL、production bean/flag有効化、push/PR/deploy/production承認は対象外。
- 現状 `CopilotGeminiResponseParser.parse` はouter usage検証後にinner summary JSONも検証し、全て成功したときだけusageを返す。したがってinner JSON/schema/claim/numeric検証失敗時に、受領済みtoken usageとdispatch状態をattemptへ渡す型経路がない。
- `SummaryCostEvidence` / `PreflightCostEvidence` と `CopilotRunService` のtenant+trace+usecase+PENDING+statusVersion CASは存在する。ただし既存testはservice/mock capture中心で、実DBに対する全状態行列、duplicate completion、stale/wrong-tenant/wrong-trace拒否と行不変性を証明していない。

## C2-A: usage保持と費用計算境界

対象候補: `CopilotGeminiResponseParser`、Copilot専用provider result/exception、`CopilotProviderUsage`、`SummaryCostEvidence`、`CopilotSummaryService`、cost policy/evaluatorとそのtest。

1. parseをouter envelope段階とinner summary段階へ分ける。outer parse完了時点で、secret/raw bodyを含まないimmutable `EnvelopeEvidence`（strictly validated usage、HTTP/dispatch outcome、model/config identity）を返す。inner parse/schema/claim/numeric/deadlineが失敗してもこのevidenceを捨てず、typed failureへ添付してsummary serviceからattempt完了へ渡す。HTTP failureやouter envelope自体の破損でusageを信頼できない場合はusage=nullとし、実際にdispatchを開始した可能性に従ってUNKNOWN/UPPER_BOUNDへ進む。生応答や例外cause本文を記録しない。
2. usageは縮小・丸め前の整数token countを保持し、prompt、candidate/output、thinking、cached等、料金表でbillableとなる全dimensionを明示する。strict JSON integer、非負、個別最大値、加算overflow、total整合を検証する。各dimensionの意味を固定し、synthetic contractでは `total >= prompt + candidate`、`cached <= prompt`、`thinking <= candidate` を検証する。provider仕様がこの包含関係や丸め規則を保証しない場合は未対応としてUNKNOWNにし、推測で再分類しない。欠落/未知billable dimension/overflow/不整合はACTUAL計算不可。数値上限は実装契約として固定し、型変換前に検査する。
3. cost evaluatorはversion付きtrusted rate-card snapshotを受け取る純粋関数とする。card identityにはprovider/model revision、region/tier、通貨JPY、effective window、全token dimension単価、FX provenance（外貨ならsource/time/rate）、料金単位、唯一の総額丸め規則を含める。金額はBigDecimal等でoverflowを検知しながら計算し、円への丸めはcardに明記された規則だけを一度適用する。円額の変換/合算もoverflowを検知し、overflow時はACTUALにしない。上限計算は各billable dimensionの最大量から保守的に円へ切上げ、budget比較も丸め後の円で行う。入力上限の根拠がないとき、`maxOutputTokens`や文字数推定を入力上限として使わず上限なしとする。
4. trusted cardの生成・検証入口は内部に限定し、public DTO、利用者指定ID、文字列versionだけからtrusted扱いにしてはならない。cardを受領済みusage、provider/model revision、config hash、effective window、dispatch前preflight snapshotへ暗号学的または同等に検証可能な形で束縛し、後から別usage/別実行へ差し替えられない契約を定義する。synthetic issuer/card生成器はtest sourceにだけ置き、production beanや設定値から到達不能にする。実providerのbillable dimension包含関係、rounding、token semanticsが未確認なら、実usageをACTUALへ昇格させずUNKNOWNとする。
5. `ACTUAL`は「検証済み実usage × 承認済みversioned cardから計算した確定額」を意味する。provider請求書照合済みの実請求額とは呼ばず、メタデータにも計算basis/card versionを残す。必要usage dimensionが欠ける/不正ならACTUAL禁止。送信有無不明または送信済みでACTUALを確定できず、独立に証明された有効な上限があればUPPER_BOUND、なければUNKNOWN。dispatch前に未開始を証明した場合のみNOT_SENT。inner validation失敗でもvalid usageがあれば費用状態を維持し、summary outcomeだけFAILEDにする。
6. このC2実装の本番card/policyは引き続き固定deny-allとし、アプリ設定、DB値、artifact、承認booleanで解除不能にする。数値テスト用rate cardはtest sourceのsynthetic fixtureでのみ生成し、それは費用算術の正しさを示すだけで、実料金の正確性・dispatch許可・production evidenceを示さない。将来の本番cardには外部公式価格/契約・region・FX資料、effective date、承認者/有効化手続きが別途必要。

### C2-A 受入証拠

- loopbackでouter usage valid + inner JSON malformed/schema mismatch/claim rejection/numeric rejection/deadline expiryを各実行し、FAILED attemptに元token count、ACTUALまたは証明可能なUPPER_BOUND/UNKNOWN、dispatchOutcomeが一致して残る。raw body/key/promptは例外・ログ・DBに出ない。
- 各dimensionの0、最大境界、最大+1、欠落、負値、decimal/string、合計overflow/不整合、rate multiplication overflow、円丸め境界、budgetちょうど/超過を評価する。synthetic cardの期待計算をfixture外の独立した期待値で検査する。
- usageが無いHTTP error、outer parser failure、timeout/connection reset、dispatch前denyを区別し、送信後をNOT_SENT/0円へ誤分類しない。どの構成値でもproduction policy request count=0を維持する。

## C2-B: 実DB状態行列とCAS証明

対象候補: `CopilotRunService.completeSummaryAttempt`、`AiRecommendationRunMapper`、専用MySQL Testcontainers integration test。既存テーブル/列だけを使いmigrationは追加しない。

実DBへfixture attemptをINSERTし、Spring管理の実service proxy経由で更新した後、transaction commit後に独立connectionから行を再読込して `status/status_version/cost_jpy/error_code/redacted_summary_json/input_hash/tenant_id/trace_id/use_case` を検査する。rollbackケースはproxy呼出しを含むSpring transactionをrollbackさせ、rollback完了後に独立connectionから元行を再読込する。各ケースは独立fixtureで実行し、試行前後の全対象列を比較する。

| 入力/遷移 | 期待DB状態 |
|---|---|
| `PENDING/v0` + dispatch未開始証拠 | `FAILED/v1`, cost 0, `NOT_SENT`, bounded reason/outcome |
| `PENDING/v0` + valid usage/card | 成否に応じterminal/v1、ACTUAL円と全token dimension/card identity |
| `PENDING/v0` + valid上限証拠 | terminal/v1、cost_jpy null、UPPER_BOUNDと上限円をredacted JSONに保存 |
| `PENDING/v0` + 送信後のusage欠落/invalid | terminal/v1、cost_jpy null、UNKNOWN。0円/KNOWNにしない |
| valid usage受領後のinner parse/validator失敗 | `FAILED/v1`だがACTUAL usage/cost/card evidence保持 |
| 同一attemptを二度complete | 2回目CAS row count=0/明確なstale outcome。初回terminal行の全列不変 |
| attempt tenant/trace/usecase不一致、またはDB行status!=PENDING/statusVersion!=0 | 更新0件。対象行と他tenant行の全列不変 |
| metadata serialization/commit直前例外 | transaction rollback後に元のPENDING/v0行が全列不変 |

CAS where条件のid+tenant+trace+usecase+PENDING+version 0を保ち、更新件数!=1を成功扱いしない。tenant context必須性も実DB経路で確認し、別tenantの同じtraceや別usecaseの行へ副作用がないことを照合する。並行二重完了はbarrierで競合させ、terminal更新が厳密に1件、もう一方は例外ではなく明示的stale拒否となることを確認する。DB値はserviceの戻り値やMockito captorで代替せず、commit後に独立connectionから読み直す。

## バッチ順序とレビュー

1. **C2-A1 usage carrier/parser境界** — usageをinner validation失敗と独立して運ぶ。parser・summary serviceのunit/loopback証拠を先に揃える。本番policy deny-allを同時に再確認。
2. **C2-A2 rate-card evaluator** — versioned trusted-card契約、token dimension/overflow/roundingを実装。ただしproduction registryはdeny-all固定、synthetic cardはtest限定。Astraが料金意味と上限根拠を承認するまでproduction値を作らない。
3. **C2-B DB persistence matrix** — Testcontainersの `@Tag("mysql")` 実DB試験で全表の状態遷移、CAS、tenant isolation、duplicate/stale、rollbackを証明。スキーマ変更なし。

各バッチを実装前Astra PLAN review、実装後に対象専用gateと独立implementation reviewへ分ける。C1（18クラス/91件PASS）のcontrolは変更しない。今回のPLAN作成ではMaven、Docker、Chrome等のheavy testは実行しない。

## 明示的な非承認範囲

この計画のPASSやsynthetic試験結果は、本番価格の承認、provider contract/DPA/region承認、token上限の実運用証明、Gemini送信承認、AI flags有効化、Gemini artifact作成/ACTIVE化、production rolloutの承認を意味しない。実credential/provider、外部ネットワーク、実データは使わない。production gateway/adapterはdeny-all、`ai.provider`/Copilot/external-send flagsは現状OFFのままとする。
