# NF-03 初回リリース範囲と事後確認資料（資格番号PII・研修費用・月次締め・HR_FINAL）

- 状態: **草案 / 署名待ち / production NO-GO の解除を意味しない**
- 根拠: 2026-11-13 の決定記録 B-NF03-1（初回は非PII部分だけを公開し、資格番号はDG-07確定まで本番で入力しない）、B-NF03-2（費用口径・月次締め・HR_FINALは現行実装のまま公開し、Finance/HRが事後確認する）
- 本資料は実装側が現行コードを読んで整理したものであり、Finance・HR・Privacy/Legal・Securityの判断や署名を代行しない。確認欄・署名欄は空欄のまま残す。
- 対象コードの基準: branch `codex/snf-w2-first-release-scope-gating-20261113`（base `1836eb73`）

## 1. 初回リリースで公開する範囲

| 区分 | 初回リリース | 制御 |
|---|---|---|
| 資格マスタ、資格の申請・取得日・有効期限、証憑upload、HR確認（verify/reject）、訂正・取消・更新、期限通知 | 公開 | NF-03本体スイッチ `FEATURE_NF03_ENABLED` |
| 学習計画・受講（enrollment）・研修費用の承認 | 公開 | 同上 |
| skill gap、SELF/MANAGER評価、HR_FINAL確定 | 公開 | 同上 |
| **資格番号（retention class `CERTIFICATION_PII`）の入力** | **公開しない** | 子スイッチ `FEATURE_NF03_CERTIFICATION_PII_ENABLED`（未設定＝OFF） |

運用手順は `.kiro/specs/production-launch/feature-switches.md`（3.4、4.6.1）を正とする。

## 2. 資格番号（CERTIFICATION_PII）

### 2.1 入力経路の棚卸し

`certificateNumber` / `certificate_number_*` の全参照を確認した結果、資格番号が入力・保存される経路は次のとおりである。

| # | 経路 | 種別 | 資格番号の扱い | 初回リリースの処置 |
|---|---|---|---|---|
| 1 | `POST /api/my/certification-learning-gap/certifications`（`MyCertificationLearningGapApiController#apply` → `CertificationLearningGapSelfServiceImpl#applyCertification` → `EngineerCertificationServiceImpl#submitApplication`） | API（本人） | request の `certificateNumber` を暗号化して保存 | 子スイッチOFFで値が空欄でなければ400（`certification.record.numberEntryDisabled`）。書込み・lock・暗号化の前に拒否（本人scope確認のための読取りは先に行う） |
| 2 | `POST /api/my/certification-learning-gap/certifications/{recordId}/resubmit`（`#resubmit` → `#resubmitCertification` → `EngineerCertificationServiceImpl#resubmit`） | API（本人） | 新recordへ暗号化して保存 | 同上。元recordのlock・CAS・RESUBMIT eventより前に拒否する（拒否時は元recordを変えない）。本人scope確認とversion照合は本人service側で先に行うため、version不一致は409が先に返る |
| 3 | `templates/my/certification-learning-skill-gap.html` の申請モーダル `#my-cert-number` | 画面 | 入力欄 | OFF中は入力欄を出さない（`disabledFeatures.contains('nf03-certification-pii')`） |
| 4 | `static/js/modules/my-certification-learning-skill-gap.js` の再申請ダイアログ | 画面 | SweetAlert2の入力欄 | OFF中（#3の入力欄が無い場合）は確認だけのダイアログにし、`certificateNumber: null` を送る |
| 5 | `EngineerCertificationMapper#updateCertificateNumberForTenant` | DB書込 | 暗号文・key version・cipher format・maskを書く唯一の更新 | 呼出し元は `submitApplication` だけで、#1のガードの後にある |
| 6 | 管理側API `/api/certification-learning-gap/**`（masters、verify、reject、一覧、count、export、AI） | API（管理） | 資格番号を受け取らない | 対象外 |
| 7 | 訂正・取消・更新（`correct` / `cancel` / `renew`） | service | 資格番号を変更しない。`renew` の新recordは資格番号を引き継がない | 対象外 |
| 8 | CSV/Excel取込（`importcenter/**`、要員CSV取込） | 取込 | 資格recordを作らない | 対象外 |

出力側（参考）: 本人CSV（`/api/my/certification-learning-gap/export`）は資格番号の列を持たない。管理側一覧は mask を表示し、`certification.pii.view` 権限者だけが復号値を受け取る（`CertificationLearningGapQueryServiceImpl`）。子スイッチOFFで運用した期間は暗号文が作られないため「未登録」表示になる。

対象外として残すもの（Privacy確認事項）: 証憑（資格証の画像・PDF）は資格番号や氏名を含み得るが、`CERTIFICATION_EVIDENCE` 文書として既存の文書台帳・retention/legal hold・scan・typed resolverに従う（DG-03-2）。verifyに証憑が必須のため、証憑uploadを止めると資格をACTIVEにできない。本資料では証憑を `CERTIFICATION_PII` の入力として扱っていない。

### 2.2 実装

- `com.ses.config.feature.Feature.NF03_CERTIFICATION_PII`（NF-03の子スイッチ。設定キー `app.features.nf03-certification-pii.enabled`、環境変数 `FEATURE_NF03_CERTIFICATION_PII_ENABLED`、既定 `false`。実効値は NF-03 との論理積）。URLは遮断しない。
- `EngineerCertificationServiceImpl#requireCertificateNumberEntryAllowed`: `submitApplication` と `resubmit` の先頭で、資格番号が空欄でなく子スイッチの実効値がOFFなら `BusinessException(400, "certification.record.numberEntryDisabled")`。空欄（null・空文字・空白のみ）は「入力なし」として通す。黙って捨てないのは、利用者が保存されたと誤認しないため。
- 判定の位置: 両メソッドの先頭（`@Transactional` のため、この時点で既にtransactionは開始している）。拒否は資格recordのinsert・lock・CAS・暗号化・event追記より前であり、拒否時に書込みは残らない。
- message key `certification.record.numberEntryDisabled` は本変更ではbundleへ追加していない。追加されるまでは `SafeErrorPolicy` により400の汎用文言「入力内容を確認してください。」が返る（keyの生文字列は出ない）。
- test profileは子スイッチもONのため、既存の資格番号暗号化テスト（`EngineerCertificationServiceTest` 等）の意味は変わらない。OFF時の挙動は `EngineerCertificationNumberEntrySwitchTest` で検証する。

### 2.3 資格番号の暗号鍵（prod fail-fast）との関係（現状）

- `CertificationNumberKeyProviderImpl#init`（prod profile）は `certification.number-crypto.current-key-version` と対応する `certification.number-crypto.keys.<version>` が揃わないと `IllegalStateException` を投げ、**アプリ全体が起動しない**。この検査は機能スイッチと独立しており、本変更でも変えていない。
- 子スイッチOFFの間は暗号化（`encrypt`）に到達しない。復号（`decrypt`）は既存の暗号文がある場合だけ呼ばれ、新規構築では暗号文が存在しない。したがって**初回リリースでは鍵は機能上使われないが、起動のためだけに必要**である。

### 2.4 設計案（未実装・Security承認待ち）: 鍵が無くても起動し、資格番号の入力だけを止める

鍵（KMS）の準備が初回リリースに間に合わない場合の案として記録する。採否はSecurity/SREが判断する。

1. 起動時の判定: prod profileで鍵を必須にする条件を「`app.features.nf03.enabled` かつ `app.features.nf03-certification-pii.enabled`」（子スイッチの実効値と同じ論理積）に限定する。条件を満たさない場合は鍵なしで起動し、WARNログを1回出す。
   - `KeyProvider` は `Environment` から同じ設定キーを読む（`FeatureSwitches` Beanの初期化順に依存しない）。
   - key versionだけ設定されて鍵が無い等の**部分設定は従来どおりfail-fast**（設定誤りを起動失敗で検出する方針を保つ）。
   - 子スイッチをONにして鍵が無ければ、その時点でfail-fast（有効化時に必ず検出される）。
2. 実行時: 鍵なしで起動した場合、`getCurrentKeyVersion()` / `getKey()` は `IllegalStateException` でfail closedにする。暗号化は2.2のガードで到達しない。
3. 復号の扱い: 現在 `CertificationLearningGapQueryServiceImpl` は復号例外をそのまま伝播するため、暗号文が存在すると `certification.pii.view` 権限者の一覧・詳細が500になる。本案を採る場合は、鍵が使えないときに復号せずmask表示へ倒し、WARNを出す変更を同時に入れる（backup復元やstaging dataの持込みで暗号文が入る可能性があるため）。
4. 追加テスト: `CertificationNumberKeyProviderImplTest` に「prod・子OFF・鍵なし → 起動しgetKeyはfail closed」「prod・子ON・鍵なし → fail-fast」「prod・NF-03 OFF・子ON・鍵なし → 起動」「部分設定 → fail-fast」、QueryServiceに「鍵不可時はmask表示」を追加する。
5. 得失: 鍵の準備が初回リリースの阻害要因でなくなり、使わない秘密情報を本番へ置かずに済む。一方で「OFFを理由にsecret供給を省略できない」という現行の運用原則（feature-switches.md 4.1）を変えるため、原則の改訂とSecurityの承認が必要になる。
6. 推奨: 初回リリースまでにKMS鍵を供給できるなら、**現状（常にfail-fast）のまま**とする（コード変更が無く、原則も変わらない）。供給できない場合だけ、本案を独立した小さな変更としてSecurity承認後に実装する。

## 3. Finance/HR 事後確認事項（B-NF03-2）

以下は現行実装の挙動である。各行の「確認結果」は Finance/HR が記入する（実装側は記入しない）。

### 3.1 研修費用の口径（Finance）

| # | 項目 | 現行実装 | 根拠 | 確認結果 |
|---|---|---|---|---|
| F-1 | 金額の単位 | 税込JPY、整数（小数不可）、NULL不可、負数不可 | `TrainingPlanServiceImpl#validatePlanDraft` / `#submit`、requirements R3 | |
| F-2 | 0円の研修 | 理由入力を必須とし、`ZERO_COST_CONFIRMED` eventだけを残して承認済みにする。ExpenseRequestは作らず、承認エンジンも通さない | `#submit` | |
| F-3 | 正数の研修 | 計画の申請時に経費（category `研修費`）の下書きを作り、そのまま申請して既存の経費承認（`ExpenseRequestApprovalAdapter` → 承認エンジン、承認経路は既存の `m_approval_route` 設定）へ載せる。NF-03側で金額閾値をhard-codeしない | `#submit`、`ExpenseRequestServiceImpl#submit`、DG-03-5 | |
| F-4 | 経費の計上日 | 計画の開始予定日（未入力なら申請日）を経費日付（expenseDate）にする | `#submit` | |
| F-5 | 計画額 | 計画の申請時の金額をsnapshotとして保持する（eventにも金額を記録） | `#submit`、`#appendEvent` | |
| F-6 | 実費の正本 | 既存のExpenseRequest（承認済・会計送信済・支払済のみ有効）。受講（enrollment）側に第二の支払状態・金額を持たない | `#validateExpenseBudget`、DG-03-5 | |
| F-7 | 予算超過 | 実費が計画額を超える場合、(a) 増額の計画変更（`LEARNING_PLAN_BUDGET_AMENDMENT` の独立承認。金額一致、申請者以外の承認者）または (b) 差額経費の独立承認（計画の承認とは別の `EXPENSE_REQUEST` 承認）が無ければ、受講完了・経費紐付けを409で拒否する | `#amendBudget`、`#validateExpenseBudget` | |
| F-8 | 減額 | 計画変更は増額だけ。計画額より小さい変更は400 | `#amendBudget` | |
| F-9 | 自己承認 | 計画の作成者は自分の計画を承認できない（403）。独立承認でも申請者自身の承認は数えない | `#approve`、`#isIndependentApprovedRequest` | |

確認してほしい論点: 税込で統一してよいか（消費税・源泉の扱い）、0円研修を承認なしで確定してよいか、開始予定日の月に経費を計上する口径でよいか、減額時の扱い、研修費の会計連携は既存経費の会計送信に従ってよいか。

### 3.2 月次締めとの関係（Finance）

締め判定は `MonthlyClosingService#assertOpenForUpdate(YYYY-MM)` で行う（締め行をFOR UPDATEで取得し、確定済みなら400 `error.closing.hardLocked`）。締め解除（reopen）は既存の月次締めworkflowだけから行う。

| # | 操作 | 締め判定の対象月 | 判定 | 根拠 | 確認結果 |
|---|---|---|---|---|---|
| C-1 | 学習計画の作成 | 開始予定日の月（未入力なら判定なし） | あり | `TrainingPlanServiceImpl#createDraft` | |
| C-2 | 学習計画（下書き）の更新 | 変更前・変更後の開始予定日の月 | あり | `#updateDraft` | |
| C-3 | 学習計画の申請 | 開始予定日の月。経費作成・申請時に経費日付の月も判定 | あり | `#submit`、`ExpenseRequestServiceImpl#createDraft` / `#submit` | |
| C-4 | 学習計画の取消 | 開始予定日の月 | あり | `#cancelPlan` | |
| C-5 | 実費経費の紐付け | 経費日付の月 | あり | `#linkExpense` | |
| C-6 | 経費の下書き更新・削除・再申請・領収書添付・支払済 | 経費日付の月 | あり | `ExpenseRequestServiceImpl#updateDraft` / `#deleteDraft` / `#resubmit` / `#attachReceipt` / `#markPaid` | |
| C-7 | 学習計画の承認・却下 | — | **なし**（承認エンジン側の締め判定は本資料では確認していない） | `#approve` / `#reject` | |
| C-8 | 予算の増額変更 | — | **なし** | `#amendBudget` | |
| C-9 | 受講の登録・開始・完了・取消 | — | **なし**（完了時は予算整合だけを検査） | `#enroll` / `#startEnrollment` / `#completeEnrollment` / `#cancelEnrollment` | |

確認してほしい論点: 計画の開始予定日の月で締めを判定する口径でよいか、締め済み月の計画について承認・増額・受講完了を許すか（C-7〜C-9）、締め後の取消が拒否されることを運用上許容できるか。

### 3.3 スキル評価と HR_FINAL（HR）

| # | 項目 | 現行実装 | 根拠 | 確認結果 |
|---|---|---|---|---|
| H-1 | 評価の種類 | SELF（本人）・MANAGER（上長）・HR_FINAL（HR確定）を別recordで保存する。同一fieldの上書きで表現しない | `SkillAssessmentServiceImpl`、DG-03-6 | |
| H-2 | SELF | account linkが一致する有効な本人だけ。提案（PROPOSED）として保存し、公式skillを変えない | `#submitSelf` | |
| H-3 | MANAGER | role `マネージャー` かつ評価の有効日時点で当該要員の上長に割り当てられている者だけ。提案として保存し、公式skillを変えない | `#submitManager` | |
| H-4 | HR_FINAL の実行者 | role `HR` または `管理者` | `#finalizeByHr` | |
| H-5 | HR_FINAL の前提 | 理由入力が必須（SELF・MANAGERも同じく理由必須）。事前のSELF/MANAGER提案が無くても確定できる | `#finalizeByHr`、`#requireActor` | |
| H-6 | HR_FINAL の反映 | 要員の公式skill（`t_engineer_skill`）の該当skillの習熟度を置き換え、無ければ追加する（経験年数は保持）。決定event（`SKILL_LEVEL`）を追記する | `#finalizeByHr`、`EngineerSkillService#replaceSkills` | |
| H-7 | 習熟度 | 「初級」「中級」「上級」の3段階だけ | `#save` | |
| H-8 | 有効日 | 未指定なら当日（Asia/Tokyo） | `#save` | |
| H-9 | AI | AI候補の採用だけでは公式skill・配置・採否・昇格・給与を変えない。AIを唯一の根拠とする不利益判断を禁止 | DG-03-6 | |
| H-10 | 本人の異議・訂正 | 本人はHR_FINALを直接変更できない（approval-decision の規定）。専用の異議申立て画面の有無は本資料では確認していない | approval-decision.md「通知・利用者境界」 | |

確認してほしい論点: 管理者にもHR_FINALを許すか、提案なしのHR確定を許すか、3段階の習熟度でよいか、異議・訂正を受ける運用（窓口・期限）。

## 4. 署名欄（空欄。各担当が記入する）

| 対象 | 役割 | 氏名 | 日付 | 結果（承認 / 条件付き / 差戻し） | 備考 |
|---|---|---|---|---|---|
| 3.1 研修費用の口径 | Finance | | | | |
| 3.2 月次締め | Finance | | | | |
| 3.3 HR_FINAL・スキル評価 | HR | | | | |
| 2.1 資格番号の入力停止範囲・証憑の扱い | Privacy/Legal | | | | DG-07未確定 |
| 2.4 鍵なし起動の設計案 | Security/SRE | | | | 採否のみ |
