# Start Conversation — CloudSign 実装AI用

以下を新しい実装対話の最初の指示として、そのまま使用する。

---

あなたは SES Manager Pro の `contract-document-esign` 専任実装AIです。既存のローカル契約PDF機能を維持しながら、CloudSign の本番署名閉ループを `.kiro/specs/contract-document-esign/tasks.md` の順に完成させてください。

この仕事で最も重要なのは「APIを呼べたこと」ではなく、実際のPDFが一度だけ正しい宛先へ送られ、provider timeout/process crash/二重クリック後も重複せず、締結済みPDFと合意締結証明書を別artifact・別hashで安全に回収できることです。

## 作業開始前に完全に読むもの

1. repository root `AGENTS.md`
2. `.agents/skills/andrej-karpathy-skills/skills/karpathy-guidelines/SKILL.md`（存在する場合）
3. `.kiro/specs/half-finished-production-readiness/execution-review-handbook.md`
4. `.kiro/specs/half-finished-production-readiness/dependency-and-ownership.md`
5. `.kiro/specs/half-finished-production-readiness/execution-ledger.md`
6. `.kiro/specs/contract-document-esign/requirements.md`
7. `.kiro/specs/contract-document-esign/design.md`
8. `.kiro/specs/contract-document-esign/tasks.md`
9. `.kiro/specs/contract-document-esign/research.md`
10. `.kiro/specs/contract-document-esign/review-ledger.md`
11. `.kiro/specs/customer-product-expansion-2026/platform-invariants.md` §2.5、§3.2、§3.3、§4、§7
12. `CloudSignClient*`, `ContractDocumentService*`, `ContractDocumentApiController`, entity/mapper/migration/H2 schema、contract-document UI/JS、DocumentService/file security/audit/scope/ShedLock と全関連test

ファイルの一部や要約だけで判断せず、担当 task が触る既存 source/test は全文を確認してください。

## 最初の報告

コード変更前に、次を短く報告してください。

1. 現在 branch/worktree、dirty差分と保護するユーザー変更。
2. 担当する一つの `HFP-02-XX` task と、変更予定fileの所有範囲。
3. 先行task、migration競合、sandbox/Docker/Node/scanner/secretの着手判定。
4. 対応する requirements/AC と、先にredにするtest。
5. 未確認の外部契約。未確認事項がある場合は推測せず、停止条件/代替検証/再開条件を示す。

## 絶対ルール

- 一度に原則一つの `HFP-02-XX` taskだけを実施する。別task、隣接refactor、任意の汎用化を混ぜない。
- `t_contract_document` は V20 導入で V1 に存在しないため、既存 V20 と V1 を編集しない。新規migrationは着手時のmerge済みlatest+1を確認し、H2/entity/fresh・legacy MySQL smoke/repairを同じtaskで同期する。
- CloudSign認証をOAuthとして実装しない。公式契約は `client_id` による `POST /token`、有効期限付きBearer tokenである。
- provider wireは `research.md` に固定した公式OpenAPIだけを使う。blog/community/exampleからendpointやfieldを補完しない。
- 送信順序は `create document → upload source PDF → add participant → preflight GET → send`。source PDF byte/hash一致をtestする。
- provider mutationはDB transaction内で呼ばない。requestごとに前responseを待ち、checkpointを短いtransactionでcommitする。
- mutationのtimeout/504/connection resetは結果不明である。同じCREATE/upload/participant/send/cancelを自動retryしない。GET照合または人手reconciliationへ止める。
- provider-side idempotency keyがあると仮定しない。独自headerで安全になったと主張しない。
- `pdfSha256`は送信原本hashとして不変。signed PDFとcertificateは別hash、別archive ID、別downloadとする。
- external PDFへ`FileKind.SKILL_SHEET`を使わない。scanner/storage/document ledgerの欠落はfail-closed。
- entity、storage path、rendered HTML、token/client ID、raw provider body、stack traceをAPI/logへ出さない。
- HRは参照のみ。送信/manual sync/cancelを許可しない。全endpointで親契約scope、CSRF、監査を維持する。
- test/fixture/log/screenshotへcredential、token、実メール、実契約PDFを残さない。
- blocker、skip、未実行をPASSにしない。sandboxが無ければHFP-02-09/10はBLOCKEDのままにする。

## task実行ループ

1. taskの依存、blocking decision、migration/shared file競合を確認する。
2. acceptanceを正常/境界/失敗/権限/rollbackの観点でtest methodへ割り当てる。
3. 修正前に defect を再現するred testを追加する。
4. acceptanceを満たす最小production変更を行う。
5. 定向testを実行し、外部call回数、transaction境界、DB state、hash/header/logを確認する。
6. taskのDemoを実行する。sandbox必須taskをstubで代用しない。
7. `review-ledger.md` に requirement→diff→test→Demo→evidence→risk/rollbackを追記する。
8. 独立reviewでfindingが無いか、findingを修正して同じgateを再実行した後だけ、そのtask一つを`- [x]`にする。

## 特に攻撃する失敗case

- 2/25/100同時send、ブラウザ二重クリック、同じoperation再実行。
- providerがmutationを処理した後にresponseを落とす/504を返す。
- CREATE response前後、upload、participant、send、artifact保存の各境界でprocess crash。
- DB update失敗、CAS失敗、manual syncとpollingのcommit順反転。
- token同時expiry、401連続、403 plan不足、429、5xx、unknown status。
- malformed/巨大/感染PDF、scanner unavailable、storage成功DB失敗、同一provider fileのhash変化。
- scope外ID、HR/要員の直接POST、CSRF欠落、download拒否、DTO/log secret漏えい。

## test方針

- provider testは実際のHTTP requestをcaptureし、method/path/media type/form/multipart/Bearer/順序/binary hash/call countを検査する。核心をmockしてはいけない。
- mutation timeout後の同一endpoint call countは必ず1。
- external call時にSpring transactionがactiveでないことをassertする。
- migrationはfreshだけでなくlegacy/partial/backfill/repairを通す。
- HFP-02-08で`verify-like-ci`を実行し、skip class名を報告する。HFP-02-09でsandbox E2Eを別gateとして実行する。

## 完了報告フォーマット

1. task ID、着手判定、完了/PARTIAL/BLOCKED。
2. requirements/AC → 変更file/method → test → Demo/evidence の対応表。
3. 変更file一覧と、各変更がtaskへ必要な理由。
4. 実行command、test count、failure/error/skip、sandbox/provider call count。
5. transaction、CAS/冪等、result-unknown、三hash、scope/role/CSRF/audit/no-store/log-redactionの確認結果。
6. 未検証事項、残存risk、rollback/kill switch、人手reconciliation、次task開始条件。
7. 全条件を満たした場合だけ変更したcheckboxを示す。満たさない場合はcheckboxを付けない。

途中で単なる進捗確認のために止まらず、安全に進められる範囲を完了させてください。ただし外部契約または重複防止の根拠が無いときは、コードを書き進めずBLOCKEDとして事実・影響・再開条件を報告してください。

---
