# 他AIへの実装ディスパッチガイド

## 1. 最初の指示（Gate 0専用）

```text
.kiro/specs/customer-product-expansion-2026/README.md、decision-log.md、shared-standards.md、
dependency-matrix.md、research-sources.mdを全て読んでください。

G1〜G6決定後の実装では `gate-decisions-g1-g6.md` も全て読んでください。

まだ実装はしません。decision-log G0〜G10について、現在のリポジトリ設定・契約情報・利用可能な
環境から確定できるものと、発注者確認が必要なものを分け、影響specと推奨回答を報告してください。
blocking=yesのG0〜G6を推測で決めてはいけません。G7〜G10も推奨既定を採用するか確認が必要です。
コード/SQL/テンプレートは変更しないでください。
```

## 2. 個別specの標準指示

`<spec-name>`だけ置換して渡す。

通常は `spec-execution-ledger.md` で状態を確認し、`spec-start-conversations.md` の17個の主実装対話と
`spec-review-conversations.md` の17個の独立Review対話を使用する。全115taskの置換済み対話
`task-start-conversations.md` は、task単位へ例外的に再分割するときだけ使用する。並行可否は
`parallel-execution-plan.md`、子Agent分担は `subagent-delegation-summary.md` を参照する。

```text
.kiro/specs/customer-product-expansion-2026/README.md、decision-log.md、shared-standards.md、
dependency-matrix.mdを先に全て読み、その後 .kiro/specs/<spec-name>/ の
requirements.md、design.md、tasks.mdを全て読んでください。

担当は <task-id> だけです。先行taskとblocking decisionが完了済みか、予約migration番号が現在も
有効か、共有ファイルに未mergeの変更がないかを最初に確認してください。未完了なら実装せず報告してください。

tasks.mdのObjective/実装ガイダンス/テスト要件/Demoを全て満たし、無関係な変更をしないでください。
DDL変更はV1、増分Flyway、H2 replay、engineer-schema-h2、MySQL smoke assertを同一コミットで同期し、
4言語i18n、CSRF、tenant/data scope、audit、file scope、export/notification経路も確認してください。

完了時は変更ファイル、対応requirements ID、実行テスト、Demo結果、未検証事項、rollback方法を報告し、
完了したtaskだけ - [x] にしてください。spec全体の完了や別taskを先取りしないでください。
```

## 3. 推奨分派順

1. `multi-company-tenant-isolation` task 0（G0とinventoryだけ）。
2. G0確定後、同spec F1〜Mを**1つずつ**。このspec中は他の新specを並行しない。
3. `organization-management-accounting` → `enterprise-identity-security` →
   `legal-document-ledger-archive` → `productivity-search-saved-view`。
4. BP masterとCRMは別AIへ並行分派可。migration基盤taskはBP V64→CRM V65の順にmergeする。
5. approvalはBP/CRM merge後。
6. Wave 2以降はREADMEの順序とdependency matrixを守る。

## 4. 必ず停止させる条件

- decision-logのblocking項目が未決。
- 予約migration番号が既に使用済み、または先行番号が未merge。
- 同じ共有ファイルを別AIが編集中。
- 法定項目/保存期間/API plan/provider仕様が公式資料と一致しない。
- 実MySQL/Docker/provider sandbox等、taskの必須検証環境がなく代替検証も定義されていない。
- data scope/tenant scopeを画面後filterで済ませようとしている。
- 外部APIをDB transaction内で同期呼出ししようとしている。
- 内部entity/APIを外部ポータルへ直接公開しようとしている。

## 5. レビューAIへの指示

```text
実装者の説明を前提にせず、担当specのrequirements/design/tasksと実diffを照合してください。
各requirements IDについて「実装箇所・テスト・Demo」を表にし、未達、過剰実装、認可漏れ、
状態競合、二重登録、migration/H2不整合、外部API障害、PII漏洩を確認してください。
特にlist/detail/count/export/download/notification/schedulerのscope母集団が同じかを検証してください。
修正不要ならその根拠を示し、問題があれば重大度、再現手順、最小修正範囲を提示してください。
```
