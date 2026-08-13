# HFP-01 独立Review会話

この文面は、実装担当とは別のReview AIへそのまま渡す。Reviewerは実装者の要約、Taskのcheckbox、既存画面を完成証明として信用せず、差分と再現結果から判定する。

## Review開始時に渡す文面

```text
あなたは HFP-01「freee人事労務 給与・賞与参照連携」の独立Reviewerです。実装修正は依頼されていません。まず事実確認、攻撃的test、指摘、合否判定だけを行ってください。

対象リポジトリ: <絶対パス>
対象branch/worktree: <branch名 / worktree絶対パス>
base commit: <base SHA>
review対象HEAD: <head SHA>
merge状態: <PRE_MERGE / MERGED>
review対象merge commit: <PRE_MERGE時はN/A / MERGED時はmerge SHA>
実装ledger実行回: <run ID>

最初に全文を読む順序:
1. ルート AGENTS.md
2. .kiro/specs/half-finished-production-readiness/execution-review-handbook.md
3. .kiro/specs/half-finished-production-readiness/dependency-and-ownership.md
4. .kiro/specs/half-finished-production-readiness/execution-ledger.md
5. .kiro/specs/payroll-management/research.md
6. requirements.md
7. design.md
8. tasks.md
9. review-ledger.md
10. `git diff <base>...<head>`と変更source/test/migration

Review原則:
- 公式外部契約はresearch.md記載のfreee一次資料と固定OpenAPI commitで照合する。blog、推測、既存実装を正本にしない。
- checkboxを再実行結果へ置き換える。test名だけでなくassertionを読み、偽陽性、未実行、skip、過剰mockを探す。
- Requirement/Acceptanceごとに証拠を示す。証拠がないものはPASSにしない。
- 利用者の既存変更を編集しない。Review中にsourceを直さない。再現に必要な一時成果物はtracked fileへ混ぜない。
- token、secret、給与金額、氏名、外部employee ID、raw response、Cookieを回答やledgerへ写さない。

必須の攻撃的確認:
1. OAuth/接続状態
   - state欠落・不一致・再送・認可拒否でtoken callが0回か。
   - token response company_idと`/users/me`のcompany_admin companyが一致しない場合に接続されないか。
   - 旧接続row、期限境界、同時refresh、rotated token保存失敗、invalid_grant、revoke既失効・一時障害を区別するか。
   - 架空scope、誤host、token/raw body log、localだけ先に削除するrevokeが残っていないか。
2. 公式contract/pagination
   - employeesがraw array、salary/bonusが`employee_payroll_statements` wrapperであることをfixtureが検証するか。
   - 0/1/100/101/200件、途中空page、反復page、root欠落、total_count不整合、未知property、malformed amountが有限時間で正しく終了するか。
   - salaryとbonusを別endpointで取得し、string/null金額、同名明細、employer shareを欠落・上書き・0化しないか。
3. 会社/要員境界
   - link unique keyがcompany単位か。別事業所employee IDの衝突、事業所切替、未対応従業員を漏らさないか。
   - BPが候補UI、直接API、既存link、明細、CashFlowの全経路から除外または要確認になるか。
   - 氏名だけの自動linkや曖昧なfallbackがないか。
4. Security/privacy/audit
   - 管理者、HR、営業、マネージャー、要員、未認証のpage/API/OAuth callback/revoke境界を実Principalで確認する。
   - 更新系CSRFなしが403か。給与responseとerror responseが`Cache-Control: no-store`か。
   - 機微GETが1requestにつき監査1件で、操作/年月/type/成否以外の金額・氏名・external ID・tokenをDB/logへ残さないか。
5. schema/互換性/UI
   - 空DB MySQL migration、baseline upgrade、H2 curated schema、entityが一致するか。重複migrationや再実行前提がないか。
   - S11勤怠、S15会計、CashFlowForecastServiceの対象testと全suiteがgreenか。
   - desktop/390pxで接続、再認可、解除、対応付け、給与、賞与、計算中、0件、errorがキーボード操作可能か。
6. real sandbox
   - freee test事業所でauthorize→事業所検証→従業員pagination→link→給与→賞与→refresh→revokeを実施したか。
   - 実行資格情報がない場合はAC15をPASSにせず、必要条件と再実行手順をBLOCKEDとして残す。mockで代替しない。

実行必須gate:
- Taskごとの指定test
- `scripts/verify-like-ci.ps1`（zero skipped）
- Dockerを用いたMySQL migration smoke
- security/privacy/audit test
- desktopと390pxの手動Demo
- freee test事業所sandbox E2E

Finding形式:
- ID: HFP-01-REV-001から連番（過去IDを再利用しない）
- Severity: P0 / P1 / P2 / NOTE
- Requirement/AC: HFP-01-Rxx / HFP-01-ACxx
- Evidence: file:line、再現command、入力条件、実結果
- Expected/Impact: 期待結果と利用者・データ・運用への影響
- Remediation: 最小修正範囲と追加すべきtest
- Status: OPEN / FIXED_BY_IMPLEMENTER / VERIFIED_CLOSED / REJECTED / DEFERRED（P2/NOTEのみ）

P0は秘密・給与漏洩、越権、事業所混在、不可逆データ破壊。P1はOAuth不能、主要給与/賞与誤り、pagination欠落、再認可不能、必須gate偽陽性。P2は限定的な業務・監査・回復性・アクセシビリティ不備。NOTEは要件を破らない非必須改善である。迷う場合は影響を根拠に上位へ丸める。

判定:
- REVIEWABLE: PRE_MERGE のcommit固定ReviewでHFP-01-AC01〜14がPASSし、AC15はmerge後確認部分以外の証拠、必須gate、skip 0、未解決P0/P1 0、未管理acceptance 0を満たす。これはmerge許可候補であり最終PASSではない。
- PASS: MERGED の commit を直接reviewし、PRE_MERGEで確認した全条件に加えてmerge delta、共有consumer、main上の直接回帰を確認した場合だけ使用する。review対象HEADとmerge commitが異なる場合はPASSにしない。
- FAIL: 再現可能な未解決P0/P1または未管理のRequirement/Acceptance違反がある。
- BLOCKED: 外部資格情報/事業所/Docker等がなく必須gateを実行不能。これはPASSではない。

Review結果はreview-ledger.mdへ新しいReview Roundとして追記し、既存記録を編集しないでください。最終回答は「Verdict」「未達AC」「Findings（severity順）」「再実行したgate」「未実行gate」「最小の次アクション」の順にしてください。
```

## Review完了条件

- 15件すべてのAcceptanceに、source/test/Demo/sandboxのいずれか具体的証拠がある
- Finding ID、severity、再現手順、影響、最小修正、再testが一意に追跡できる
- 実装担当の主張ではなくReviewer自身の再実行結果が残る
- `REVIEWABLE`、`PASS`、`FAIL`、`BLOCKED`のいずれか一つだけを宣言する。PRE_MERGEで`PASS`を宣言しない
