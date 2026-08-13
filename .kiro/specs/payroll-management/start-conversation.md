# HFP-01 実装開始会話

この文面は、`payroll-management`を担当する実装AIへそのまま渡すための開始テンプレートである。`<...>`だけを実値へ置換する。

## 開始時に渡す文面

```text
あなたは HFP-01「freee人事労務 給与・賞与参照連携」の実装担当です。

対象リポジトリ: <絶対パス>
対象branch/worktree: <branch名 / worktree絶対パス>
実装開始HEAD: <commit SHA>

最初に、次の順で全文を読んでください。
1. ルート AGENTS.md
2. .kiro/specs/half-finished-production-readiness/execution-review-handbook.md
3. .kiro/specs/half-finished-production-readiness/dependency-and-ownership.md
4. .kiro/specs/half-finished-production-readiness/execution-ledger.md
5. .kiro/specs/payroll-management/research.md
6. .kiro/specs/payroll-management/requirements.md
7. .kiro/specs/payroll-management/design.md
8. .kiro/specs/payroll-management/tasks.md
9. .kiro/specs/payroll-management/review-ledger.md
10. 各Taskに列挙された既存source/test/migration

開始前に必ず行うこと:
- `git status --short`と現在branch/HEADを記録し、利用者の既存変更を上書きしない。
- `research.md`の固定OpenAPI commitが公式freee repositoryに存在することを確認する。新しいschemaとの差分がある場合、勝手に追従せず、固定commitを実装正本として差分をledgerに記録する。契約破壊または安全性に関わる差分だけ作業を停止して判断を求める。
- HFP-01-001の失敗baselineを先に再現する。既存画面があることや既存testがgreenであることを完成の根拠にしない。
- freee test事業所、OAuth app、redirect URI、必要権限、審査条件の利用可否を確認し、秘密値を会話・source・test fixture・ledgerへ貼らない。

実行規約:
- `tasks.md`をHFP-01-001から依存順に実施する。各TaskはObjective、実装、指定test、Demo、完成証跡、失敗/ロールバック判定をすべて満たすまで`[x]`にしない。
- 1 Taskごとに最小の失敗testを先に追加し、失敗理由を確認してから実装する。広範な整理、名前変更、framework追加、業務sourceの全面書換えを混ぜない。
- 既存のAES暗号化、OAuth state、token row-lock/refresh競合防止、共通HTTP入口、engineer link、ApiResult、SecurityConfig、ApiAuditFilter/AuditLogService、CashFlowForecastServiceを再利用する。design.mdの「禁止」を守り、別系統を新設しない。
- S11勤怠連携とS15会計連携の機能追加は非目標である。共有OAuth/transportを壊さない回帰testだけを行う。
- 公式endpoint、root、field、string/null金額、limit/offsetをfixtureで固定する。推測したpath、field alias、架空scope、null→0変換、氏名による自動対応付けは禁止する。
- 会社境界、BP除外、静的認可、CSRF、`Cache-Control: no-store`、機微GET監査、秘密/個人情報非記録を同じ完成条件として扱う。
- freee table は V21 で導入され V1 に存在しないため V1 へ追加しない。schema変更はforward migration、対象H2 curated schema/replay、entityをAGENTS.mdの規約どおり同期し、migration番号は開始時の最新番号を再確認して採番する。
- 外部API retryはdesign.mdのmatrixを超えない。未知schema、反復page、途中空page、invalid amountを空結果や成功へ変換しない。
- 実API資格情報がなくてもHFP-01-010までの決定的testを完了する。sandbox必須条件をmockへ置換したりskipしたりせず、未実施ならHFP-01-011と全体をBLOCKEDのままにする。

証跡規約:
- review-ledger.mdは既存行を削除・上書きせず、新しい実行回を追記する。
- 各Taskに、変更file/method、実行command、test件数と結果、Demo結果、rollback確認を記録する。
- token、client secret、給与金額、氏名、外部employee ID、raw response、Cookieを証跡へ残さない。必要なら件数、hash化したfixture名、mask済みrequest IDだけを使う。
- skipは成功に数えない。Docker/Node/freee sandbox等がない場合は、gate名、理由、再実行command、必要な外部条件を明記する。

Task完了ごとの報告形式:
- Task: HFP-01-xxx
- 変更: file/methodと意図
- Test: command、実行数、成功/失敗/skip
- Demo: 手順と観測結果
- Evidence: review-ledger.mdの該当実行回
- 残件/リスク: なし、またはRequirement ID付き

全Task後は、`scripts/verify-like-ci.ps1`、MySQL migration smoke、指定security/privacy test、desktop/390px Demo、freee test事業所E2Eを実施し、独立Reviewerへ引き渡してください。必須gate未実施、AC未達、未解決P0/P1が1件でもあれば「完成」と報告しないでください。P2/NOTEを延期する場合は発注者承認、owner、期限、release影響を記録してください。
```

## 開始回答で確認する内容

実装担当の最初の回答には、少なくとも次を含める。

- 読了した仕様と現在のbranch/HEAD/dirty差分
- 公式schema固定commitの確認結果
- HFP-01-001からの実行順と、最初に失敗させるtest
- sandbox前提の利用可否（秘密値そのものは記載しない）
- 利用者判断が必要なblocking事項。なければ質問だけで停止せず着手する
