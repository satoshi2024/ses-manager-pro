# 115タスク別・着手対話カタログ

本書は、顧客視点プロダクト拡張17spec・全115タスクを、例外的に1タスクずつ再分割するときの補助プロンプト集である。
各対話は担当範囲を意図的に狭くし、先行依存、blocking decision、migration予約、共有ファイル競合を着手前に検査させる。
通常運用は `spec-start-conversations.md` と `spec-review-conversations.md` を使用し、本書から115対話を作らない。
T001は完了済みのため再派工しない。

T014〜T115は `test-execution-policy-s03-s17.md` を必須適用する。通常Taskは定向testと直接回帰で完了し、
無条件の全量testを実行しない。全量は各specのM task、明記された昇格checkpoint、CI/releaseへ集約する。

## 使用ルール

1. 1つの対話につき、原則として1つの実装AI/1つのbranchまたはworktreeへ渡す。
2. 並行可否は `parallel-execution-plan.md`、子Agentの使い方は `subagent-delegation-summary.md` を正とする。
4. task本文の順番は編集しない。依存未完了、blocking decision未決、migration競合、共有ファイル競合なら停止報告させる。
5. `M` taskは統合担当だけが実行する。複数AIが同時にチェックを付けない。
6. 各task完了後、別AIまたは主担当が実diffと受入条件をレビューしてから次へ進む。
7. T014〜T115はTEST SCOPE DECISION（level、対象consumer、除外suite、昇格条件、次のL4 checkpoint）を提出する。

## T番号索引

T番号は検索・派工用の固定IDであり、実行順ではない。実行順は `parallel-execution-plan.md` を優先する。
特にWave 1はT048〜T053（CRM）をT041〜T047（approval）より先に完了する。

| T範囲 | spec | task数 |
|---|---|---:|
| T001〜T007 | `multi-company-tenant-isolation` | 7 |
| T008〜T013 | `organization-management-accounting` | 6 |
| T014〜T020 | `enterprise-identity-security` | 7 |
| T021〜T027 | `legal-document-ledger-archive` | 7 |
| T028〜T033 | `productivity-search-saved-view` | 6 |
| T034〜T040 | `bp-company-master-procurement-compliance` | 7 |
| T041〜T047 | `approval-workflow-internal-control` | 7 |
| T048〜T053 | `crm-contact-opportunity` | 6 |
| T054〜T059 | `order-acceptance-workflow` | 6 |
| T060〜T066 | `dispatch-outsourcing-compliance-ledger` | 7 |
| T067〜T074 | `attendance-leave-overtime-compliance` | 8 |
| T075〜T080 | `staffing-capacity-planning` | 6 |
| T081〜T087 | `external-customer-bp-portal` | 7 |
| T088〜T093 | `engineer-self-service-portal-v2` | 6 |
| T094〜T101 | `accounting-payment-integration` | 8 |
| T102〜T108 | `jp-pint-digital-invoice` | 7 |
| T109〜T115 | `ai-feedback-learning` | 7 |

## T001 — `multi-company-tenant-isolation` / 0. G0決定と全SQL棚卸し

```text
あなたはSES Manager Proの `multi-company-tenant-isolation` specにおけるtask `0` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/multi-company-tenant-isolation/requirements.md
- .kiro/specs/multi-company-tenant-isolation/design.md
- .kiro/specs/multi-company-tenant-isolation/tasks.md

【担当task原文】
- [x] 0. G0決定と全SQL棚卸し
  - **Objective**: deployment方式、tenant解決方式、対象表、annotation SQL、ジョブ、cache、fileを確定。
  - **成果物**: `tenant-inventory.md`（表/SQL/unique/FK/owner/対応方法）。
  - **Demo**: 発注者がG0を決定しdecision-logへ記録。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/multi-company-tenant-isolation/tasks.md` のtask `0` だけを `- [x]` にする
```

## T002 — `multi-company-tenant-isolation` / F1. tenant/legal entity DDLと既存データ移行

```text
あなたはSES Manager Proの `multi-company-tenant-isolation` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/multi-company-tenant-isolation/requirements.md
- .kiro/specs/multi-company-tenant-isolation/design.md
- .kiro/specs/multi-company-tenant-isolation/tasks.md

【担当task原文】
- [ ] F1. tenant/legal entity DDLと既存データ移行
  - **Objective**: 将来再計画時のtenant/legal entity DDL、V1最終形、H2 2系統、smoke assert。V59は使用しない。
  - **テスト要件**: 件数/金額reconciliation、複合UNIQUE、別tenant FK拒否。
  - **Demo**: DBコピーを移行し、差分レポートが全項目0。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの実装開始時migration番号が当時のFlyway最新番号`latest + 1`から採番され、先行番号がmerge済みか確認する。V59を補完・再利用せず、競合時は採番を勝手に変更せず停止する。共有DB再開と発注者の新規実装指示がない限り、このF1を開始してはならない。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/multi-company-tenant-isolation/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T003 — `multi-company-tenant-isolation` / F2. TenantContextとMyBatis強制条件

```text
あなたはSES Manager Proの `multi-company-tenant-isolation` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/multi-company-tenant-isolation/requirements.md
- .kiro/specs/multi-company-tenant-isolation/design.md
- .kiro/specs/multi-company-tenant-isolation/tasks.md

【担当task原文】
- [ ] F2. TenantContextとMyBatis強制条件
  - **Objective**: filter/interceptor/単独DBfeature flag。
  - **テスト要件**: contextなし拒否、A→Bのlist/detail/count漏洩なし。
  - **Demo**: 同じbusiness keyをA/Bに作り、各tenantから自分の1件だけ表示。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/multi-company-tenant-isolation/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T004 — `multi-company-tenant-isolation` / F3. カスタムSQL・非HTTP経路

```text
あなたはSES Manager Proの `multi-company-tenant-isolation` specにおけるtask `F3` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/multi-company-tenant-isolation/requirements.md
- .kiro/specs/multi-company-tenant-isolation/design.md
- .kiro/specs/multi-company-tenant-isolation/tasks.md

【担当task原文】
- [ ] F3. カスタムSQL・非HTTP経路
  - **Objective**: annotation SQL、scheduler、async、cache、notificationへtenant適用。
  - **テスト要件**: inventory全行にテストIDを紐付け、thread reuse混線なし。
  - **Demo**: Aの通知生成後にBへ通知/件数が出ない。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/multi-company-tenant-isolation/tasks.md` のtask `F3` だけを `- [x]` にする
```

## T005 — `multi-company-tenant-isolation` / F4. 認証・platform管理・停止

```text
あなたはSES Manager Proの `multi-company-tenant-isolation` specにおけるtask `F4` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/multi-company-tenant-isolation/requirements.md
- .kiro/specs/multi-company-tenant-isolation/design.md
- .kiro/specs/multi-company-tenant-isolation/tasks.md

【担当task原文】
- [ ] F4. 認証・platform管理・停止
  - **Objective**: tenant別username、host照合、platform boundary、session失効。
  - **テスト要件**: host不一致、停止tenant、platformから通常API拒否。
  - **Demo**: tenant停止直後に既存sessionの更新が拒否される。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/multi-company-tenant-isolation/tasks.md` のtask `F4` だけを `- [x]` にする
```

## T006 — `multi-company-tenant-isolation` / F5. ファイル・export・backup

```text
あなたはSES Manager Proの `multi-company-tenant-isolation` specにおけるtask `F5` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/multi-company-tenant-isolation/requirements.md
- .kiro/specs/multi-company-tenant-isolation/design.md
- .kiro/specs/multi-company-tenant-isolation/tasks.md

【担当task原文】
- [ ] F5. ファイル・export・backup
  - **Objective**: tenant prefix、未知file fail-closed、tenant export/restore手順。
  - **テスト要件**: A fileをBが取得不可、backup restore件数一致。
  - **Demo**: tenant単位exportを隔離DBへrestore。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/multi-company-tenant-isolation/tasks.md` のtask `F5` だけを `- [x]` にする
```

## T007 — `multi-company-tenant-isolation` / M. 全回帰と容量確認

```text
あなたはSES Manager Proの `multi-company-tenant-isolation` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/multi-company-tenant-isolation/requirements.md
- .kiro/specs/multi-company-tenant-isolation/design.md
- .kiro/specs/multi-company-tenant-isolation/tasks.md

【担当task原文】
- [ ] M. 全回帰と容量確認
  - **Objective**: M. 全回帰と容量確認 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: `mvn test`、MySQL smoke、主要API isolation matrix、既存単独DBモード。
  - **Demo**: A/Bの提案→契約→勤怠→請求を並行実行し相互漏洩0。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/multi-company-tenant-isolation/tasks.md` のtask `M` だけを `- [x]` にする
```

## T008 — `organization-management-accounting` / F1. 組織/所属/cost center/予算DDL

```text
あなたはSES Manager Proの `organization-management-accounting` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/organization-management-accounting/requirements.md
- .kiro/specs/organization-management-accounting/design.md
- .kiro/specs/organization-management-accounting/tasks.md

【担当task原文】
- [ ] F1. 組織/所属/cost center/予算DDL
  - **Objective**: F1. 組織/所属/cost center/予算DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V60/V1/H2/smoke、entity/mapper/service。
  - **テスト要件**: 循環、期間、主所属一意、参照中無効化。
  - **Demo**: 法人→事業部→課と上長を登録。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/organization-management-accounting/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T009 — `organization-management-accounting` / F2. OrganizationScopeService

```text
あなたはSES Manager Proの `organization-management-accounting` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/organization-management-accounting/requirements.md
- .kiro/specs/organization-management-accounting/design.md
- .kiro/specs/organization-management-accounting/tasks.md

【担当task原文】
- [ ] F2. OrganizationScopeService
  - **Objective**: F2. OrganizationScopeService を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: DataScopeとの結合規則、cache keyにtenant/user/version。
  - **テスト要件**: 管理者/部門長/営業/HRの一覧・件数・export。
  - **Demo**: 部門長が子組織だけ閲覧。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/organization-management-accounting/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T010 — `organization-management-accounting` / A1. 組織管理画面

```text
あなたはSES Manager Proの `organization-management-accounting` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/organization-management-accounting/requirements.md
- .kiro/specs/organization-management-accounting/design.md
- .kiro/specs/organization-management-accounting/tasks.md

【担当task原文】
- [ ] A1. 組織管理画面
  - **Objective**: A1. 組織管理画面 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: tree CRUD、異動、cost center、user主所属。
  - **テスト要件**: API validation/CSRF/権限。
  - **Demo**: 異動前後の日付で所属が切替。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/organization-management-accounting/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T011 — `organization-management-accounting` / B1. 月次帰属snapshotと予算

```text
あなたはSES Manager Proの `organization-management-accounting` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/organization-management-accounting/requirements.md
- .kiro/specs/organization-management-accounting/design.md
- .kiro/specs/organization-management-accounting/tasks.md

【担当task原文】
- [ ] B1. 月次帰属snapshotと予算
  - **Objective**: B1. 月次帰属snapshotと予算 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 月次締めhook、予算CSV、訂正監査。
  - **テスト要件**: 異動後も過去snapshot不変、reopen規約。
  - **Demo**: 先月所属と今月所属が別部門集計。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/organization-management-accounting/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T012 — `organization-management-accounting` / B2. 管理会計ダッシュボード

```text
あなたはSES Manager Proの `organization-management-accounting` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/organization-management-accounting/requirements.md
- .kiro/specs/organization-management-accounting/design.md
- .kiro/specs/organization-management-accounting/tasks.md

【担当task原文】
- [ ] B2. 管理会計ダッシュボード
  - **Objective**: B2. 管理会計ダッシュボード を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 既存金額service再利用、予実/drilldown/export。
  - **テスト要件**: 全社合計一致、scope漏洩なし。
  - **Demo**: 部門別売上/粗利/待機費/予算差を確認。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/organization-management-accounting/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T013 — `organization-management-accounting` / M. 回帰

```text
あなたはSES Manager Proの `organization-management-accounting` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/organization-management-accounting/requirements.md
- .kiro/specs/organization-management-accounting/design.md
- .kiro/specs/organization-management-accounting/tasks.md

【担当task原文】
- [ ] M. 回帰
  - **Objective**: M. 回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/browser mobile。
  - **Demo**: 組織作成→所属→契約→締め→部門損益の一気通貫。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/organization-management-accounting/tasks.md` のtask `M` だけを `- [x]` にする
```

## T014 — `enterprise-identity-security` / 0. G1/脅威モデル/permission inventory

```text
あなたはSES Manager Proの `enterprise-identity-security` specにおけるtask `0` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/enterprise-identity-security/requirements.md
- .kiro/specs/enterprise-identity-security/design.md
- .kiro/specs/enterprise-identity-security/tasks.md

【担当task原文】
- [ ] 0. G1/脅威モデル/permission inventory
  - **Objective**: 0. G1/脅威モデル/permission inventory を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: Entra test tenant/app登録、claim/group mapping、認証flow、action一覧、PII分類、2アカウントbreak-glass手順。
  - **Demo**: security review承認。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/enterprise-identity-security/tasks.md` のtask `0` だけを `- [x]` にする
```

## T015 — `enterprise-identity-security` / F1. identity/MFA/session/permission DDL

```text
あなたはSES Manager Proの `enterprise-identity-security` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/enterprise-identity-security/requirements.md
- .kiro/specs/enterprise-identity-security/design.md
- .kiro/specs/enterprise-identity-security/tasks.md

【担当task原文】
- [ ] F1. identity/MFA/session/permission DDL
  - **Objective**: F1. identity/MFA/session/permission DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V63/V1/H2/smoke、暗号鍵version設計。
  - **テスト要件**: unique、recovery code hash、tenant分離。
  - **Demo**: F1. identity/MFA/session/permission DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/enterprise-identity-security/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T016 — `enterprise-identity-security` / A1. OIDC login/provision/logout

```text
あなたはSES Manager Proの `enterprise-identity-security` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/enterprise-identity-security/requirements.md
- .kiro/specs/enterprise-identity-security/design.md
- .kiro/specs/enterprise-identity-security/tasks.md

【担当task原文】
- [ ] A1. OIDC login/provision/logout
  - **Objective**: A1. OIDC login/provision/logout を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: subject紐付け、招待、local fallback。
  - **テスト要件**: 正常、未知subject、email衝突、issuer不正、IdP timeout。
  - **Demo**: Entra test tenantでlogin/logout。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/enterprise-identity-security/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T017 — `enterprise-identity-security` / A2. MFA/session管理

```text
あなたはSES Manager Proの `enterprise-identity-security` specにおけるtask `A2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/enterprise-identity-security/requirements.md
- .kiro/specs/enterprise-identity-security/design.md
- .kiro/specs/enterprise-identity-security/tasks.md

【担当task原文】
- [ ] A2. MFA/session管理
  - **Objective**: A2. MFA/session管理 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: TOTP setup/recovery/session一覧/失効。
  - **テスト要件**: replay防止、code一回限り、role変更即失効。
  - **Demo**: 管理者MFA登録と端末失効。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/enterprise-identity-security/tasks.md` のtask `A2` だけを `- [x]` にする
```

## T018 — `enterprise-identity-security` / B1. action permission移行

```text
あなたはSES Manager Proの `enterprise-identity-security` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/enterprise-identity-security/requirements.md
- .kiro/specs/enterprise-identity-security/design.md
- .kiro/specs/enterprise-identity-security/tasks.md

【担当task原文】
- [ ] B1. action permission移行
  - **Objective**: B1. action permission移行 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: legacy seed、AuthorizationService、主要高リスクAPIから適用。
  - **テスト要件**: role/group/action matrix、自己昇格拒否、field masking。
  - **Demo**: 財務担当は請求可・原価閲覧不可等。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/enterprise-identity-security/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T019 — `enterprise-identity-security` / B2. file quarantine/scan/fail-closed

```text
あなたはSES Manager Proの `enterprise-identity-security` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/enterprise-identity-security/requirements.md
- .kiro/specs/enterprise-identity-security/design.md
- .kiro/specs/enterprise-identity-security/tasks.md

【担当task原文】
- [ ] B2. file quarantine/scan/fail-closed
  - **Objective**: B2. file quarantine/scan/fail-closed を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: FileScanner、quarantine、再scan、未知file拒否。
  - **テスト要件**: clean/infected/unavailable/既存file参照。
  - **Demo**: test fixtureが感染表示となり配布不可。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/enterprise-identity-security/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T020 — `enterprise-identity-security` / M. セキュリティ回帰

```text
あなたはSES Manager Proの `enterprise-identity-security` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/enterprise-identity-security/requirements.md
- .kiro/specs/enterprise-identity-security/design.md
- .kiro/specs/enterprise-identity-security/tasks.md

【担当task原文】
- [ ] M. セキュリティ回帰
  - **Objective**: M. セキュリティ回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test、OWASP依存スキャン相当、OIDC/MFA/browser、監査log秘密非出力。
  - **Demo**: login→権限変更→session失効→break-glass復旧訓練。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/enterprise-identity-security/tasks.md` のtask `M` だけを `- [x]` にする
```

## T021 — `legal-document-ledger-archive` / 0. G2法務確認と既存file inventory

```text
あなたはSES Manager Proの `legal-document-ledger-archive` specにおけるtask `0` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/legal-document-ledger-archive/requirements.md
- .kiro/specs/legal-document-ledger-archive/design.md
- .kiro/specs/legal-document-ledger-archive/tasks.md

【担当task原文】
- [ ] 0. G2法務確認と既存file inventory
  - **Objective**: 0. G2法務確認と既存file inventory を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: 文書種別、起算日、保存年、法的hold、既存path/参照元/件数/容量。
  - **Demo**: 公式URL/版/確認日付きprovisional mappingと社内コンプライアンス責任者の確認記録。外部専門家承認はM/本番gateへ記録。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/legal-document-ledger-archive/tasks.md` のtask `0` だけを `- [x]` にする
```

## T022 — `legal-document-ledger-archive` / F1. 文書DDLとDocumentService

```text
あなたはSES Manager Proの `legal-document-ledger-archive` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/legal-document-ledger-archive/requirements.md
- .kiro/specs/legal-document-ledger-archive/design.md
- .kiro/specs/legal-document-ledger-archive/tasks.md

【担当task原文】
- [ ] F1. 文書DDLとDocumentService
  - **Objective**: F1. 文書DDLとDocumentService を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V64/V1/H2/smoke、version/link/access/disposal。
  - **テスト要件**: hash/version/冪等/hold/楽観ロック。
  - **Demo**: 受領PDFを登録しmetadataとhash表示。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/legal-document-ledger-archive/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T023 — `legal-document-ledger-archive` / F2. Storage adapterとstream download

```text
あなたはSES Manager Proの `legal-document-ledger-archive` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/legal-document-ledger-archive/requirements.md
- .kiro/specs/legal-document-ledger-archive/design.md
- .kiro/specs/legal-document-ledger-archive/tasks.md

【担当task原文】
- [ ] F2. Storage adapterとstream download
  - **Objective**: F2. Storage adapterとstream download を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: local/S3 interface、quarantine、orphan cleanup、fail-closed。
  - **テスト要件**: large file固定heap、scan失敗、DB失敗補償、A→B download拒否。
  - **Demo**: local/S3 fakeを設定切替して同じAPIで取得。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/legal-document-ledger-archive/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T024 — `legal-document-ledger-archive` / A1. 台帳検索/詳細/version UI

```text
あなたはSES Manager Proの `legal-document-ledger-archive` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/legal-document-ledger-archive/requirements.md
- .kiro/specs/legal-document-ledger-archive/design.md
- .kiro/specs/legal-document-ledger-archive/tasks.md

【担当task原文】
- [ ] A1. 台帳検索/詳細/version UI
  - **Objective**: A1. 台帳検索/詳細/version UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 日付/金額/相手先/種別、関連業務link、履歴。
  - **テスト要件**: filter組合せ、権限、mobile。
  - **Demo**: 3条件検索→文書→旧版→業務画面。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/legal-document-ledger-archive/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T025 — `legal-document-ledger-archive` / B1. 既存帳票/CloudSign統合

```text
あなたはSES Manager Proの `legal-document-ledger-archive` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/legal-document-ledger-archive/requirements.md
- .kiro/specs/legal-document-ledger-archive/design.md
- .kiro/specs/legal-document-ledger-archive/tasks.md

【担当task原文】
- [ ] B1. 既存帳票/CloudSign統合
  - **Objective**: B1. 既存帳票/CloudSign統合 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 見積/契約/作業報告/請求/署名済文書を冪等登録。
  - **テスト要件**: 再生成/再同期で重複なし、旧機能回帰。
  - **Demo**: 契約生成→署名同期→2文書版を確認。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/legal-document-ledger-archive/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T026 — `legal-document-ledger-archive` / B2. 税務export/retention/disposal

```text
あなたはSES Manager Proの `legal-document-ledger-archive` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/legal-document-ledger-archive/requirements.md
- .kiro/specs/legal-document-ledger-archive/design.md
- .kiro/specs/legal-document-ledger-archive/tasks.md

【担当task原文】
- [ ] B2. 税務export/retention/disposal
  - **Objective**: B2. 税務export/retention/disposal を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 非同期ZIP+manifest、候補→承認→廃棄、legal hold。
  - **テスト要件**: ZIP hash再計算、上限、approval、storage delete failure。
  - **Demo**: 検索結果exportと廃棄訓練。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/legal-document-ledger-archive/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T027 — `legal-document-ledger-archive` / M. 移行/回帰/復元

```text
あなたはSES Manager Proの `legal-document-ledger-archive` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/legal-document-ledger-archive/requirements.md
- .kiro/specs/legal-document-ledger-archive/design.md
- .kiro/specs/legal-document-ledger-archive/tasks.md

【担当task原文】
- [ ] M. 移行/回帰/復元
  - **Objective**: M. 移行/回帰/復元 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: inventory件数/hash、全test、MySQL smoke、backup整合。
  - **Demo**: DB+storageを隔離環境へ復元し文書表示。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/legal-document-ledger-archive/tasks.md` のtask `M` だけを `- [x]` にする
```

## T028 — `productivity-search-saved-view` / F1. task/saved view基盤

```text
あなたはSES Manager Proの `productivity-search-saved-view` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/productivity-search-saved-view/requirements.md
- .kiro/specs/productivity-search-saved-view/design.md
- .kiro/specs/productivity-search-saved-view/tasks.md

【担当task原文】
- [ ] F1. task/saved view基盤
  - **Objective**: F1. task/saved view基盤 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V65/V1/H2/smoke、schema registry、状態機械。
  - **テスト要件**: task遷移、view allowlist/owner/tenant。
  - **Demo**: task登録→担当変更→完了。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/productivity-search-saved-view/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T029 — `productivity-search-saved-view` / A1. 横断検索

```text
あなたはSES Manager Proの `productivity-search-saved-view` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/productivity-search-saved-view/requirements.md
- .kiro/specs/productivity-search-saved-view/design.md
- .kiro/specs/productivity-search-saved-view/tasks.md

【担当task原文】
- [ ] A1. 横断検索
  - **Objective**: A1. 横断検索 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: provider、header UI、scope付き上限。
  - **テスト要件**: 種別別結果、A/B漏洩、timeout/2文字境界。
  - **Demo**: 顧客名から顧客/案件/契約/請求へ移動。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/productivity-search-saved-view/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T030 — `productivity-search-saved-view` / A2. ToDo/通知分離

```text
あなたはSES Manager Proの `productivity-search-saved-view` specにおけるtask `A2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/productivity-search-saved-view/requirements.md
- .kiro/specs/productivity-search-saved-view/design.md
- .kiro/specs/productivity-search-saved-view/tasks.md

【担当task原文】
- [ ] A2. ToDo/通知分離
  - **Objective**: A2. ToDo/通知分離 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: todo tab、関連link、期限scheduler、通知→task。
  - **テスト要件**: 既読と完了の独立、通知冪等。
  - **Demo**: 通知を既読後もtask継続。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/productivity-search-saved-view/tasks.md` のtask `A2` だけを `- [x]` にする
```

## T031 — `productivity-search-saved-view` / B1. 保存ビュー/表示列

```text
あなたはSES Manager Proの `productivity-search-saved-view` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/productivity-search-saved-view/requirements.md
- .kiro/specs/productivity-search-saved-view/design.md
- .kiro/specs/productivity-search-saved-view/tasks.md

【担当task原文】
- [ ] B1. 保存ビュー/表示列
  - **Objective**: B1. 保存ビュー/表示列 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: engineer/customer/project/contract/invoiceから段階導入。
  - **テスト要件**: 個人/共有、無効field、default fallback。
  - **Demo**: 列/検索を保存し再login後復元。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/productivity-search-saved-view/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T032 — `productivity-search-saved-view` / B2. 安全な一括操作

```text
あなたはSES Manager Proの `productivity-search-saved-view` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/productivity-search-saved-view/requirements.md
- .kiro/specs/productivity-search-saved-view/design.md
- .kiro/specs/productivity-search-saved-view/tasks.md

【担当task原文】
- [ ] B2. 安全な一括操作
  - **Objective**: B2. 安全な一括操作 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: preview token、担当/状態/task、最大200。
  - **テスト要件**: 200/201、改ざん、partial、権限/状態競合。
  - **Demo**: 20要員へ担当営業変更preview→apply→結果CSV。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/productivity-search-saved-view/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T033 — `productivity-search-saved-view` / M. 回帰/負荷

```text
あなたはSES Manager Proの `productivity-search-saved-view` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/productivity-search-saved-view/requirements.md
- .kiro/specs/productivity-search-saved-view/design.md
- .kiro/specs/productivity-search-saved-view/tasks.md

【担当task原文】
- [ ] M. 回帰/負荷
  - **Objective**: M. 回帰/負荷 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test、MySQLで検索p95、mobile keyboard。
  - **Demo**: 検索→saved view→bulk→taskの業務シナリオ。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/productivity-search-saved-view/tasks.md` のtask `M` だけを `- [x]` にする
```

## T034 — `bp-company-master-procurement-compliance` / 0. G2法務確認/既存自由入力profiling

```text
あなたはSES Manager Proの `bp-company-master-procurement-compliance` specにおけるtask `0` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/bp-company-master-procurement-compliance/requirements.md
- .kiro/specs/bp-company-master-procurement-compliance/design.md
- .kiro/specs/bp-company-master-procurement-compliance/tasks.md

【担当task原文】
- [ ] 0. G2法務確認/既存自由入力profiling
  - **Objective**: 0. G2法務確認/既存自由入力profiling を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: 適用確認票、必須明示項目、支払rule、distinct値/件数/候補衝突。
  - **Demo**: 公式URL/版付き適用確認票の社内責任者確認と移行dry-run報告。外部専門家承認はM/本番gate。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/bp-company-master-procurement-compliance/tasks.md` のtask `0` だけを `- [x]` にする
```

## T035 — `bp-company-master-procurement-compliance` / F1. BP master/terms/contact/bank DDL

```text
あなたはSES Manager Proの `bp-company-master-procurement-compliance` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/bp-company-master-procurement-compliance/requirements.md
- .kiro/specs/bp-company-master-procurement-compliance/design.md
- .kiro/specs/bp-company-master-procurement-compliance/tasks.md

【担当task原文】
- [ ] F1. BP master/terms/contact/bank DDL
  - **Objective**: F1. BP master/terms/contact/bank DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V66/V1/H2/smoke、暗号化/masking、service。
  - **テスト要件**: unique、期間、bank非露出、状態。
  - **Demo**: BP法人と個人事業主を登録。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/bp-company-master-procurement-compliance/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T036 — `bp-company-master-procurement-compliance` / F2. 既存在庫/要員/支払移行

```text
あなたはSES Manager Proの `bp-company-master-procurement-compliance` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/bp-company-master-procurement-compliance/requirements.md
- .kiro/specs/bp-company-master-procurement-compliance/design.md
- .kiro/specs/bp-company-master-procurement-compliance/tasks.md

【担当task原文】
- [ ] F2. 既存在庫/要員/支払移行
  - **Objective**: F2. 既存在庫/要員/支払移行 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: affiliation、bp_company_id、snapshot、例外解決。
  - **テスト要件**: 件数/金額合計、同名別法人、read fallback/write ID必須。
  - **Demo**: staging DBで未解決0件まで解消。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/bp-company-master-procurement-compliance/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T037 — `bp-company-master-procurement-compliance` / A1. BP管理画面

```text
あなたはSES Manager Proの `bp-company-master-procurement-compliance` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/bp-company-master-procurement-compliance/requirements.md
- .kiro/specs/bp-company-master-procurement-compliance/design.md
- .kiro/specs/bp-company-master-procurement-compliance/tasks.md

【担当task原文】
- [ ] A1. BP管理画面
  - **Objective**: A1. BP管理画面 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: detail tabs、document link、評価、停止、autocomplete。
  - **テスト要件**: CRUD/scope/CSRF/PII field。
  - **Demo**: BP→所属要員→支払までdrilldown。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/bp-company-master-procurement-compliance/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T038 — `bp-company-master-procurement-compliance` / B1. 発注コンプライアンスrule/価格協議

```text
あなたはSES Manager Proの `bp-company-master-procurement-compliance` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/bp-company-master-procurement-compliance/requirements.md
- .kiro/specs/bp-company-master-procurement-compliance/design.md
- .kiro/specs/bp-company-master-procurement-compliance/tasks.md

【担当task原文】
- [ ] B1. 発注コンプライアンスrule/価格協議
  - **Objective**: B1. 発注コンプライアンスrule/価格協議 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: applicability確認、明示項目、60日/支払手段/手数料、交渉履歴。
  - **テスト要件**: 境界と例外承認。
  - **Demo**: 不足発注を拒否し、補完後に警告0。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/bp-company-master-procurement-compliance/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T039 — `bp-company-master-procurement-compliance` / B2. リスクdashboard/通知

```text
あなたはSES Manager Proの `bp-company-master-procurement-compliance` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/bp-company-master-procurement-compliance/requirements.md
- .kiro/specs/bp-company-master-procurement-compliance/design.md
- .kiro/specs/bp-company-master-procurement-compliance/tasks.md

【担当task原文】
- [ ] B2. リスクdashboard/通知
  - **Objective**: B2. リスクdashboard/通知 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 期限文書、未確認、低評価、支払期日。
  - **テスト要件**: 通知冪等/recipient scope。
  - **Demo**: BPリスクから対象detailへ遷移。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/bp-company-master-procurement-compliance/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T040 — `bp-company-master-procurement-compliance` / M. 回帰/旧入力廃止判定

```text
あなたはSES Manager Proの `bp-company-master-procurement-compliance` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/bp-company-master-procurement-compliance/requirements.md
- .kiro/specs/bp-company-master-procurement-compliance/design.md
- .kiro/specs/bp-company-master-procurement-compliance/tasks.md

【担当task原文】
- [ ] M. 回帰/旧入力廃止判定
  - **Objective**: M. 回帰/旧入力廃止判定 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/migration reconciliation。
  - **Demo**: 新規自由入力不可、既存フロー全通し。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/bp-company-master-procurement-compliance/tasks.md` のtask `M` だけを `- [x]` にする
```

## T041 — `approval-workflow-internal-control` / 0. G7と対象操作inventory

```text
あなたはSES Manager Proの `approval-workflow-internal-control` specにおけるtask `0` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/approval-workflow-internal-control/requirements.md
- .kiro/specs/approval-workflow-internal-control/design.md
- .kiro/specs/approval-workflow-internal-control/tasks.md

【担当task原文】
- [ ] 0. G7と対象操作inventory
  - **Objective**: 0. G7と対象操作inventory を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: 操作、現endpoint/service、申請field、route、SLA、職務分離表。
  - **Demo**: 財務/管理者レビュー。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/approval-workflow-internal-control/tasks.md` のtask `0` だけを `- [x]` にする
```

## T042 — `approval-workflow-internal-control` / F1. route/request/action/delegation DDL

```text
あなたはSES Manager Proの `approval-workflow-internal-control` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/approval-workflow-internal-control/requirements.md
- .kiro/specs/approval-workflow-internal-control/design.md
- .kiro/specs/approval-workflow-internal-control/tasks.md

【担当task原文】
- [ ] F1. route/request/action/delegation DDL
  - **Objective**: F1. route/request/action/delegation DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V68/V1/H2/smoke、engine core/CAS。
  - **テスト要件**: route/自己承認/並列/代理/競合。
  - **Demo**: F1. route/request/action/delegation DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/approval-workflow-internal-control/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T043 — `approval-workflow-internal-control` / F2. 5 target adapters

```text
あなたはSES Manager Proの `approval-workflow-internal-control` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/approval-workflow-internal-control/requirements.md
- .kiro/specs/approval-workflow-internal-control/design.md
- .kiro/specs/approval-workflow-internal-control/tasks.md

【担当task原文】
- [ ] F2. 5 target adapters
  - **Objective**: F2. 5 target adapters を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 既存service委譲、version snapshot、idempotency、outbox。
  - **テスト要件**: adapterごと正常/競合/rollback/再送。
  - **Demo**: curlで各対象申請→承認。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/approval-workflow-internal-control/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T044 — `approval-workflow-internal-control` / A1. inbox/request/diff/history UI

```text
あなたはSES Manager Proの `approval-workflow-internal-control` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/approval-workflow-internal-control/requirements.md
- .kiro/specs/approval-workflow-internal-control/design.md
- .kiro/specs/approval-workflow-internal-control/tasks.md

【担当task原文】
- [ ] A1. inbox/request/diff/history UI
  - **Objective**: A1. inbox/request/diff/history UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 一覧、差分、comment、対象link、mobile。
  - **テスト要件**: requester/approver scope、field masking。
  - **Demo**: 差戻し→修正→再申請→承認。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/approval-workflow-internal-control/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T045 — `approval-workflow-internal-control` / A2. route/代理管理

```text
あなたはSES Manager Proの `approval-workflow-internal-control` specにおけるtask `A2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/approval-workflow-internal-control/requirements.md
- .kiro/specs/approval-workflow-internal-control/design.md
- .kiro/specs/approval-workflow-internal-control/tasks.md

【担当task原文】
- [ ] A2. route/代理管理
  - **Objective**: A2. route/代理管理 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: version/有効日、approver preview、代理期間。
  - **テスト要件**: 進行中snapshot不変、解決不能拒否。
  - **Demo**: route変更前後の2申請で承認者が異なる。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/approval-workflow-internal-control/tasks.md` のtask `A2` だけを `- [x]` にする
```

## T046 — `approval-workflow-internal-control` / B1. 通知/SLA/escalation

```text
あなたはSES Manager Proの `approval-workflow-internal-control` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/approval-workflow-internal-control/requirements.md
- .kiro/specs/approval-workflow-internal-control/design.md
- .kiro/specs/approval-workflow-internal-control/tasks.md

【担当task原文】
- [ ] B1. 通知/SLA/escalation
  - **Objective**: B1. 通知/SLA/escalation を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: recipient限定、冪等scheduler、NotificationLinks。
  - **テスト要件**: 期限境界/重複なし/tenant scope。
  - **Demo**: overdueを上位責任者へ通知。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/approval-workflow-internal-control/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T047 — `approval-workflow-internal-control` / M. 対象画面統合/回帰

```text
あなたはSES Manager Proの `approval-workflow-internal-control` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/approval-workflow-internal-control/requirements.md
- .kiro/specs/approval-workflow-internal-control/design.md
- .kiro/specs/approval-workflow-internal-control/tasks.md

【担当task原文】
- [ ] M. 対象画面統合/回帰
  - **Objective**: M. 対象画面統合/回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/5業務browser通し。
  - **Demo**: 申請者単独確定不可と二重実行0を確認。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/approval-workflow-internal-control/tasks.md` のtask `M` だけを `- [x]` にする
```

## T048 — `crm-contact-opportunity` / F1. contact/lead/opportunity DDLと移行

```text
あなたはSES Manager Proの `crm-contact-opportunity` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/crm-contact-opportunity/requirements.md
- .kiro/specs/crm-contact-opportunity/design.md
- .kiro/specs/crm-contact-opportunity/tasks.md

【担当task原文】
- [ ] F1. contact/lead/opportunity DDLと移行
  - **Objective**: F1. contact/lead/opportunity DDLと移行 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V65/V1/H2/smoke、既存contact→初回contact。
  - **テスト要件**: 件数、primary、PII scope、移行値一致。
  - **Demo**: 既存顧客の担当者がdetailに表示。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/crm-contact-opportunity/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T049 — `crm-contact-opportunity` / F2. opportunity状態/変換/forecast排他

```text
あなたはSES Manager Proの `crm-contact-opportunity` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/crm-contact-opportunity/requirements.md
- .kiro/specs/crm-contact-opportunity/design.md
- .kiro/specs/crm-contact-opportunity/tasks.md

【担当task原文】
- [ ] F2. opportunity状態/変換/forecast排他
  - **Objective**: F2. opportunity状態/変換/forecast排他 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: service、project/quotation source、冪等。
  - **テスト要件**: 状態、再送、二重forecastなし。
  - **Demo**: 商機→見積/案件変換を2回実行し1件。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/crm-contact-opportunity/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T050 — `crm-contact-opportunity` / A1. 顧客contacts/timeline

```text
あなたはSES Manager Proの `crm-contact-opportunity` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/crm-contact-opportunity/requirements.md
- .kiro/specs/crm-contact-opportunity/design.md
- .kiro/specs/crm-contact-opportunity/tasks.md

【担当task原文】
- [ ] A1. 顧客contacts/timeline
  - **Objective**: A1. 顧客contacts/timeline を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 複数担当、役割、activity/mail/document link。
  - **テスト要件**: 宛先候補、退職除外、mask。
  - **Demo**: 請求担当を請求書送付先に選択。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/crm-contact-opportunity/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T051 — `crm-contact-opportunity` / A2. lead/opportunity UI

```text
あなたはSES Manager Proの `crm-contact-opportunity` specにおけるtask `A2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/crm-contact-opportunity/requirements.md
- .kiro/specs/crm-contact-opportunity/design.md
- .kiro/specs/crm-contact-opportunity/tasks.md

【担当task原文】
- [ ] A2. lead/opportunity UI
  - **Objective**: A2. lead/opportunity UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: lead list、opportunity kanban/list、next task。
  - **テスト要件**: filters/scope/mobile/D&D rollback。
  - **Demo**: lead→顧客/商機→見積。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/crm-contact-opportunity/tasks.md` のtask `A2` だけを `- [x]` にする
```

## T052 — `crm-contact-opportunity` / B1. CRM KPI

```text
あなたはSES Manager Proの `crm-contact-opportunity` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/crm-contact-opportunity/requirements.md
- .kiro/specs/crm-contact-opportunity/design.md
- .kiro/specs/crm-contact-opportunity/tasks.md

【担当task原文】
- [ ] B1. CRM KPI
  - **Objective**: B1. CRM KPI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: stage金額/滞留/転換/失注/source ROI。
  - **テスト要件**: 集計口径とscope。
  - **Demo**: 担当別funnel drilldown。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/crm-contact-opportunity/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T053 — `crm-contact-opportunity` / M. 回帰

```text
あなたはSES Manager Proの `crm-contact-opportunity` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/crm-contact-opportunity/requirements.md
- .kiro/specs/crm-contact-opportunity/design.md
- .kiro/specs/crm-contact-opportunity/tasks.md

【担当task原文】
- [ ] M. 回帰
  - **Objective**: M. 回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/customer/proposal/quotation回帰。
  - **Demo**: 新規leadから受注まで一気通貫。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/crm-contact-opportunity/tasks.md` のtask `M` だけを `- [x]` にする
```

## T054 — `order-acceptance-workflow` / F1. 注文/明細/検収DDL

```text
あなたはSES Manager Proの `order-acceptance-workflow` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/order-acceptance-workflow/requirements.md
- .kiro/specs/order-acceptance-workflow/design.md
- .kiro/specs/order-acceptance-workflow/tasks.md

【担当task原文】
- [ ] F1. 注文/明細/検収DDL
  - **Objective**: F1. 注文/明細/検収DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V67/V1/H2/smoke、entity/mapper/number/状態。
  - **テスト要件**: unique、金額、状態、複数明細。
  - **Demo**: F1. 注文/明細/検収DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/order-acceptance-workflow/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T055 — `order-acceptance-workflow` / F2. 見積→注文→契約

```text
あなたはSES Manager Proの `order-acceptance-workflow` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/order-acceptance-workflow/requirements.md
- .kiro/specs/order-acceptance-workflow/design.md
- .kiro/specs/order-acceptance-workflow/tasks.md

【担当task原文】
- [ ] F2. 見積→注文→契約
  - **Objective**: F2. 見積→注文→契約 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 条件差分、approval hook、draft共通化、冪等。
  - **テスト要件**: 引継ぎ、差分、再送、rollback。
  - **Demo**: 見積から注文2明細→契約2件。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/order-acceptance-workflow/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T056 — `order-acceptance-workflow` / A1. 注文画面/注文請PDF/archive

```text
あなたはSES Manager Proの `order-acceptance-workflow` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/order-acceptance-workflow/requirements.md
- .kiro/specs/order-acceptance-workflow/design.md
- .kiro/specs/order-acceptance-workflow/tasks.md

【担当task原文】
- [ ] A1. 注文画面/注文請PDF/archive
  - **Objective**: A1. 注文画面/注文請PDF/archive を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: CRUD、原本upload、PDF、document links。
  - **テスト要件**: PDF/hash/ACL/PO重複。
  - **Demo**: 原本受領→注文請発行。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/order-acceptance-workflow/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T057 — `order-acceptance-workflow` / B1. 月次検収service/UI

```text
あなたはSES Manager Proの `order-acceptance-workflow` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/order-acceptance-workflow/requirements.md
- .kiro/specs/order-acceptance-workflow/design.md
- .kiro/specs/order-acceptance-workflow/tasks.md

【担当task原文】
- [ ] B1. 月次検収service/UI
  - **Objective**: B1. 月次検収service/UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: submit/accept/reject/cancel、work record link。
  - **テスト要件**: 状態/CAS/差戻し/amount snapshot。
  - **Demo**: 勤怠確定→検収差戻し→再提出→検収済。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/order-acceptance-workflow/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T058 — `order-acceptance-workflow` / B2. 請求/月次締め/通知統合

```text
あなたはSES Manager Proの `order-acceptance-workflow` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/order-acceptance-workflow/requirements.md
- .kiro/specs/order-acceptance-workflow/design.md
- .kiro/specs/order-acceptance-workflow/tasks.md

【担当task原文】
- [ ] B2. 請求/月次締め/通知統合
  - **Objective**: B2. 請求/月次締め/通知統合 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: invoice SQL guard、未検収check、deadline通知/KPI。
  - **テスト要件**: 検収要/不要、重複通知、scope。
  - **Demo**: 未検収請求拒否→検収後生成。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/order-acceptance-workflow/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T059 — `order-acceptance-workflow` / M. 全通し

```text
あなたはSES Manager Proの `order-acceptance-workflow` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/order-acceptance-workflow/requirements.md
- .kiro/specs/order-acceptance-workflow/design.md
- .kiro/specs/order-acceptance-workflow/tasks.md

【担当task原文】
- [ ] M. 全通し
  - **Objective**: M. 全通し を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/document/approval回帰。
  - **Demo**: 見積→注文→契約→勤怠→検収→請求。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/order-acceptance-workflow/tasks.md` のtask `M` だけを `- [x]` にする
```

## T060 — `dispatch-outsourcing-compliance-ledger` / 0. G2公式様式field mapping

```text
あなたはSES Manager Proの `dispatch-outsourcing-compliance-ledger` specにおけるtask `0` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/requirements.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/design.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md

【担当task原文】
- [ ] 0. G2公式様式field mapping
  - **Objective**: 0. G2公式様式field mapping を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: 帳票ごとの法定項目→DB/画面/生成位置、保存期間、権限。
  - **Demo**: 厚生労働省公式URL/版/確認日付きmappingの社内責任者承認。外部社労士/法務承認はM/本番gate。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md` のtask `0` だけを `- [x]` にする
```

## T061 — `dispatch-outsourcing-compliance-ledger` / F1. workplace/profile/finding/delivery DDL

```text
あなたはSES Manager Proの `dispatch-outsourcing-compliance-ledger` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/requirements.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/design.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md

【担当task原文】
- [ ] F1. workplace/profile/finding/delivery DDL
  - **Objective**: F1. workplace/profile/finding/delivery DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V68/V1/H2/smoke、snapshot/permission。
  - **テスト要件**: FK/期間/PII scope。
  - **Demo**: F1. workplace/profile/finding/delivery DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T062 — `dispatch-outsourcing-compliance-ledger` / F2. ComplianceRule分割/拡張

```text
あなたはSES Manager Proの `dispatch-outsourcing-compliance-ledger` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/requirements.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/design.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md

【担当task原文】
- [ ] F2. ComplianceRule分割/拡張
  - **Objective**: F2. ComplianceRule分割/拡張 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 既存4rule維持、期限/欠落/期間/指示経路rule、upsert。
  - **テスト要件**: code別境界、解消、重複なし。
  - **Demo**: 欠落profileを補完してfinding解消。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T063 — `dispatch-outsourcing-compliance-ledger` / A1. 契約compliance profile/UI

```text
あなたはSES Manager Proの `dispatch-outsourcing-compliance-ledger` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/requirements.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/design.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md

【担当task原文】
- [ ] A1. 契約compliance profile/UI
  - **Objective**: A1. 契約compliance profile/UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 契約形態別field、help、権限、差分。
  - **テスト要件**: validation/field mask/mobile。
  - **Demo**: 派遣/準委任で異なる入力項目。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T064 — `dispatch-outsourcing-compliance-ledger` / B1. 法定帳票/交付/archive

```text
あなたはSES Manager Proの `dispatch-outsourcing-compliance-ledger` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/requirements.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/design.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md

【担当task原文】
- [ ] B1. 法定帳票/交付/archive
  - **Objective**: B1. 法定帳票/交付/archive を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: generator/template version/delivery/受領。
  - **テスト要件**: golden file、版、hash、ACL。
  - **Demo**: 派遣元台帳等を生成し交付記録。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T065 — `dispatch-outsourcing-compliance-ledger` / B2. deadline/リスク運用

```text
あなたはSES Manager Proの `dispatch-outsourcing-compliance-ledger` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/requirements.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/design.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md

【担当task原文】
- [ ] B2. deadline/リスク運用
  - **Objective**: B2. deadline/リスク運用 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 90/60/30日、担当、ack/resolution/evidence。
  - **テスト要件**: 日付境界/notification scope/冪等。
  - **Demo**: 抵触日alert→対応→解消。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T066 — `dispatch-outsourcing-compliance-ledger` / M. 法務受入/回帰

```text
あなたはSES Manager Proの `dispatch-outsourcing-compliance-ledger` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/requirements.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/design.md
- .kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md

【担当task原文】
- [ ] M. 法務受入/回帰
  - **Objective**: M. 法務受入/回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/既存compliance回帰。
  - **Demo**: 法務fixture3契約の台帳とfindingを照合。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md` のtask `M` だけを `- [x]` にする
```

## T067 — `attendance-leave-overtime-compliance` / 0. G6/36協定/就業規則確認

```text
あなたはSES Manager Proの `attendance-leave-overtime-compliance` specにおけるtask `0` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/attendance-leave-overtime-compliance/requirements.md
- .kiro/specs/attendance-leave-overtime-compliance/design.md
- .kiro/specs/attendance-leave-overtime-compliance/tasks.md

【担当task原文】
- [ ] 0. G6/36協定/就業規則確認
  - **Objective**: 0. G6/36協定/就業規則確認 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: source of truth、勤務区分、丸め、カレンダー、休暇、協定期間/上限。
  - **Demo**: 本システムを正とするsource matrixのHR確認。法人別36協定/就業規則と外部社労士確認はM/本番gate。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/attendance-leave-overtime-compliance/tasks.md` のtask `0` だけを `- [x]` にする
```

## T068 — `attendance-leave-overtime-compliance` / F1. calendar/attendance/month/leave/agreement DDL

```text
あなたはSES Manager Proの `attendance-leave-overtime-compliance` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/attendance-leave-overtime-compliance/requirements.md
- .kiro/specs/attendance-leave-overtime-compliance/design.md
- .kiro/specs/attendance-leave-overtime-compliance/tasks.md

【担当task原文】
- [ ] F1. calendar/attendance/month/leave/agreement DDL
  - **Objective**: F1. calendar/attendance/month/leave/agreement DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V69/V1/H2/smoke、minute model、scope。
  - **テスト要件**: period/unique/closing/leave。
  - **Demo**: F1. calendar/attendance/month/leave/agreement DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/attendance-leave-overtime-compliance/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T069 — `attendance-leave-overtime-compliance` / F2. 集計/時間外calculator

```text
あなたはSES Manager Proの `attendance-leave-overtime-compliance` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/attendance-leave-overtime-compliance/requirements.md
- .kiro/specs/attendance-leave-overtime-compliance/design.md
- .kiro/specs/attendance-leave-overtime-compliance/tasks.md

【担当task原文】
- [ ] F2. 集計/時間外calculator
  - **Objective**: F2. 集計/時間外calculator を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: official boundary fixtures、rolling平均、warning。
  - **テスト要件**: 全境界、跨夜/休日/休憩。
  - **Demo**: fixture結果をHRへ提示。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/attendance-leave-overtime-compliance/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T070 — `attendance-leave-overtime-compliance` / A1. 本人/管理画面と月次状態

```text
あなたはSES Manager Proの `attendance-leave-overtime-compliance` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/attendance-leave-overtime-compliance/requirements.md
- .kiro/specs/attendance-leave-overtime-compliance/design.md
- .kiro/specs/attendance-leave-overtime-compliance/tasks.md

【担当task原文】
- [ ] A1. 本人/管理画面と月次状態
  - **Objective**: A1. 本人/管理画面と月次状態 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: calendar、入力/提出/差戻し/承認/締め。
  - **テスト要件**: 本人/上長/HR scope、CAS、mobile。
  - **Demo**: 本人提出→上長差戻し→再提出。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/attendance-leave-overtime-compliance/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T071 — `attendance-leave-overtime-compliance` / A2. 休暇/approval統合

```text
あなたはSES Manager Proの `attendance-leave-overtime-compliance` specにおけるtask `A2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/attendance-leave-overtime-compliance/requirements.md
- .kiro/specs/attendance-leave-overtime-compliance/design.md
- .kiro/specs/attendance-leave-overtime-compliance/tasks.md

【担当task原文】
- [ ] A2. 休暇/approval統合
  - **Objective**: A2. 休暇/approval統合 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 申請、残数/外部参照、営業通知。
  - **テスト要件**: 半休/時間休/不足/重複/代理。
  - **Demo**: 休暇申請→承認→calendar反映。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/attendance-leave-overtime-compliance/tasks.md` のtask `A2` だけを `- [x]` にする
```

## T072 — `attendance-leave-overtime-compliance` / B1. freee/provider sync

```text
あなたはSES Manager Proの `attendance-leave-overtime-compliance` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/attendance-leave-overtime-compliance/requirements.md
- .kiro/specs/attendance-leave-overtime-compliance/design.md
- .kiro/specs/attendance-leave-overtime-compliance/tasks.md

【担当task原文】
- [ ] B1. freee/provider sync
  - **Objective**: B1. freee/provider sync を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 本システムの承認/締め済みdataをfreeeへ冪等送信またはCSV出力し、外部dataはread-only照合。cursor/冪等/error UI。
  - **テスト要件**: 401/429/timeout/重複/部分失敗。
  - **Demo**: sandbox syncと再実行。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/attendance-leave-overtime-compliance/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T073 — `attendance-leave-overtime-compliance` / B2. 客先工数差異/通知

```text
あなたはSES Manager Proの `attendance-leave-overtime-compliance` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/attendance-leave-overtime-compliance/requirements.md
- .kiro/specs/attendance-leave-overtime-compliance/design.md
- .kiro/specs/attendance-leave-overtime-compliance/tasks.md

【担当task原文】
- [ ] B2. 客先工数差異/通知
  - **Objective**: B2. 客先工数差異/通知 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 月次比較、理由確認、warning/escalation。
  - **テスト要件**: 金額非変更、scope、通知冪等。
  - **Demo**: 8h差異を確認して理由保存。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/attendance-leave-overtime-compliance/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T074 — `attendance-leave-overtime-compliance` / M. 回帰/法務受入

```text
あなたはSES Manager Proの `attendance-leave-overtime-compliance` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/attendance-leave-overtime-compliance/requirements.md
- .kiro/specs/attendance-leave-overtime-compliance/design.md
- .kiro/specs/attendance-leave-overtime-compliance/tasks.md

【担当task原文】
- [ ] M. 回帰/法務受入
  - **Objective**: M. 回帰/法務受入 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/給与・work record回帰。
  - **Demo**: 6か月rolling fixtureと月次全通し。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/attendance-leave-overtime-compliance/tasks.md` のtask `M` だけを `- [x]` にする
```

## T075 — `staffing-capacity-planning` / F1. position/allocation/scenario DDL

```text
あなたはSES Manager Proの `staffing-capacity-planning` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/staffing-capacity-planning/requirements.md
- .kiro/specs/staffing-capacity-planning/design.md
- .kiro/specs/staffing-capacity-planning/tasks.md

【担当task原文】
- [ ] F1. position/allocation/scenario DDL
  - **Objective**: F1. position/allocation/scenario DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V72/V1/H2/smoke、状態/区間/競合service。
  - **テスト要件**: 50+50/60+50、期間、scenario isolation。
  - **Demo**: F1. position/allocation/scenario DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/staffing-capacity-planning/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T076 — `staffing-capacity-planning` / F2. proposal/contract/availability統合

```text
あなたはSES Manager Proの `staffing-capacity-planning` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/staffing-capacity-planning/requirements.md
- .kiro/specs/staffing-capacity-planning/design.md
- .kiro/specs/staffing-capacity-planning/tasks.md

【担当task原文】
- [ ] F2. proposal/contract/availability統合
  - **Objective**: F2. proposal/contract/availability統合 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: position link、actual allocation、renewal/leave/retirement。
  - **テスト要件**: 二重計上、更新済、退職、scope。
  - **Demo**: 提案→契約でposition充足。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/staffing-capacity-planning/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T077 — `staffing-capacity-planning` / A1. position board/allocation timeline

```text
あなたはSES Manager Proの `staffing-capacity-planning` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/staffing-capacity-planning/requirements.md
- .kiro/specs/staffing-capacity-planning/design.md
- .kiro/specs/staffing-capacity-planning/tasks.md

【担当task原文】
- [ ] A1. position board/allocation timeline
  - **Objective**: A1. position board/allocation timeline を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: project/engineer画面、drag操作は失敗rollback。
  - **テスト要件**: API/CSRF/concurrency/mobile。
  - **Demo**: 兼務配置と過配賦拒否。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/staffing-capacity-planning/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T078 — `staffing-capacity-planning` / B1. 需給heatmap/KPI

```text
あなたはSES Manager Proの `staffing-capacity-planning` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/staffing-capacity-planning/requirements.md
- .kiro/specs/staffing-capacity-planning/design.md
- .kiro/specs/staffing-capacity-planning/tasks.md

【担当task原文】
- [ ] B1. 需給heatmap/KPI
  - **Objective**: B1. 需給heatmap/KPI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: skill/role/location/月aggregate、bench cost。
  - **テスト要件**: FTE口径、全社=内訳、24か月。
  - **Demo**: Java需要不足をdrilldown。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/staffing-capacity-planning/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T079 — `staffing-capacity-planning` / B2. scenario compare

```text
あなたはSES Manager Proの `staffing-capacity-planning` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/staffing-capacity-planning/requirements.md
- .kiro/specs/staffing-capacity-planning/design.md
- .kiro/specs/staffing-capacity-planning/tasks.md

【担当task原文】
- [ ] B2. scenario compare
  - **Objective**: B2. scenario compare を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: clone/仮配置/比較/共有、本データ非更新。
  - **テスト要件**: isolation/owner/scope。
  - **Demo**: 2scenarioの稼働率/粗利差。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/staffing-capacity-planning/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T080 — `staffing-capacity-planning` / M. 回帰/性能

```text
あなたはSES Manager Proの `staffing-capacity-planning` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/staffing-capacity-planning/requirements.md
- .kiro/specs/staffing-capacity-planning/design.md
- .kiro/specs/staffing-capacity-planning/tasks.md

【担当task原文】
- [ ] M. 回帰/性能
  - **Objective**: M. 回帰/性能 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL、代表データ量でp95/heap。
  - **Demo**: position作成→配置→提案→契約→需給更新。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/staffing-capacity-planning/tasks.md` のtask `M` だけを `- [x]` にする
```

## T081 — `external-customer-bp-portal` / 0. G3/G8と公開field inventory

```text
あなたはSES Manager Proの `external-customer-bp-portal` specにおけるtask `0` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/external-customer-bp-portal/requirements.md
- .kiro/specs/external-customer-bp-portal/design.md
- .kiro/specs/external-customer-bp-portal/tasks.md

【担当task原文】
- [ ] 0. G3/G8と公開field inventory
  - **Objective**: 0. G3/G8と公開field inventory を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: domain/規約/本人確認/permission×画面×field matrix、threat model。
  - **Demo**: G3 security boundary/field matrixの社内security・support承認。規約の外部法務承認はM/本番gate。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/external-customer-bp-portal/tasks.md` のtask `0` だけを `- [x]` にする
```

## T082 — `external-customer-bp-portal` / F1. portal org/user/invite/consent DDL

```text
あなたはSES Manager Proの `external-customer-bp-portal` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/external-customer-bp-portal/requirements.md
- .kiro/specs/external-customer-bp-portal/design.md
- .kiro/specs/external-customer-bp-portal/tasks.md

【担当task原文】
- [ ] F1. portal org/user/invite/consent DDL
  - **Objective**: F1. portal org/user/invite/consent DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V73/V1/H2/smoke、token/hash/session/permission。
  - **テスト要件**: token/reuse/expiry/email/tenant/停止。
  - **Demo**: F1. portal org/user/invite/consent DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/external-customer-bp-portal/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T083 — `external-customer-bp-portal` / F2. 専用security chain/DTO boundary

```text
あなたはSES Manager Proの `external-customer-bp-portal` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/external-customer-bp-portal/requirements.md
- .kiro/specs/external-customer-bp-portal/design.md
- .kiro/specs/external-customer-bp-portal/tasks.md

【担当task原文】
- [ ] F2. 専用security chain/DTO boundary
  - **Objective**: F2. 専用security chain/DTO boundary を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: principal/CSRF/cookie/rate limit/authorization/field allowlist。
  - **テスト要件**: A/B IDOR matrix、内部API拒否、秘密非ログ。
  - **Demo**: portal userが内部URLへ403。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/external-customer-bp-portal/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T084 — `external-customer-bp-portal` / A1. 顧客portal

```text
あなたはSES Manager Proの `external-customer-bp-portal` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/external-customer-bp-portal/requirements.md
- .kiro/specs/external-customer-bp-portal/design.md
- .kiro/specs/external-customer-bp-portal/tasks.md

【担当task原文】
- [ ] A1. 顧客portal
  - **Objective**: A1. 顧客portal を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: documents/acceptance/invoice/支払予定/問い合わせ。
  - **テスト要件**: acceptance冪等/差戻し/file ACL。
  - **Demo**: 作業報告→顧客検収→内部請求可。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/external-customer-bp-portal/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T085 — `external-customer-bp-portal` / A2. BP portal

```text
あなたはSES Manager Proの `external-customer-bp-portal` specにおけるtask `A2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/external-customer-bp-portal/requirements.md
- .kiro/specs/external-customer-bp-portal/design.md
- .kiro/specs/external-customer-bp-portal/tasks.md

【担当task原文】
- [ ] A2. BP portal
  - **Objective**: A2. BP portal を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: availability submission、発注確認、請求提出、支払参照、口座変更申請。
  - **テスト要件**: review/approval前非反映、BP組織scope。
  - **Demo**: BP提出→内部review→支払予定表示。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/external-customer-bp-portal/tasks.md` のtask `A2` だけを `- [x]` にする
```

## T086 — `external-customer-bp-portal` / B1. 管理/通知/利用規約

```text
あなたはSES Manager Proの `external-customer-bp-portal` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/external-customer-bp-portal/requirements.md
- .kiro/specs/external-customer-bp-portal/design.md
- .kiro/specs/external-customer-bp-portal/tasks.md

【担当task原文】
- [ ] B1. 管理/通知/利用規約
  - **Objective**: B1. 管理/通知/利用規約 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: user/invite/session/log、terms consent、email preference。
  - **テスト要件**: return URL、通知重複、terms更新。
  - **Demo**: 規約改定後再同意。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/external-customer-bp-portal/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T087 — `external-customer-bp-portal` / M. penetration/回帰/運用

```text
あなたはSES Manager Proの `external-customer-bp-portal` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/external-customer-bp-portal/requirements.md
- .kiro/specs/external-customer-bp-portal/design.md
- .kiro/specs/external-customer-bp-portal/tasks.md

【担当task原文】
- [ ] M. penetration/回帰/運用
  - **Objective**: M. penetration/回帰/運用 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/IDOR/rate/mobile/scan。
  - **Demo**: 顧客A/B/BPの3組織受入と停止/復旧訓練。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/external-customer-bp-portal/tasks.md` のtask `M` だけを `- [x]` にする
```

## T088 — `engineer-self-service-portal-v2` / F1. change/expense/1on1/survey DDL

```text
あなたはSES Manager Proの `engineer-self-service-portal-v2` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/engineer-self-service-portal-v2/requirements.md
- .kiro/specs/engineer-self-service-portal-v2/design.md
- .kiro/specs/engineer-self-service-portal-v2/tasks.md

【担当task原文】
- [ ] F1. change/expense/1on1/survey DDL
  - **Objective**: F1. change/expense/1on1/survey DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V74/V1/H2/smoke、本人scope、field allowlist。
  - **テスト要件**: A/B、JSON不正、状態、version競合。
  - **Demo**: F1. change/expense/1on1/survey DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/engineer-self-service-portal-v2/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T089 — `engineer-self-service-portal-v2` / A1. my dashboard/profile/skill申請

```text
あなたはSES Manager Proの `engineer-self-service-portal-v2` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/engineer-self-service-portal-v2/requirements.md
- .kiro/specs/engineer-self-service-portal-v2/design.md
- .kiro/specs/engineer-self-service-portal-v2/tasks.md

【担当task原文】
- [ ] A1. my dashboard/profile/skill申請
  - **Objective**: A1. my dashboard/profile/skill申請 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: preview/diff/approval apply、公開契約条件。
  - **テスト要件**: 承認前不変、再送1回、原価非表示。
  - **Demo**: skill申請→HR承認→sheet preview。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/engineer-self-service-portal-v2/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T090 — `engineer-self-service-portal-v2` / A2. 本人給与/勤怠導線

```text
あなたはSES Manager Proの `engineer-self-service-portal-v2` specにおけるtask `A2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/engineer-self-service-portal-v2/requirements.md
- .kiro/specs/engineer-self-service-portal-v2/design.md
- .kiro/specs/engineer-self-service-portal-v2/tasks.md

【担当task原文】
- [ ] A2. 本人給与/勤怠導線
  - **Objective**: A2. 本人給与/勤怠導線 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 専用DTO/MFA/no-store、attendance/timesheet統合navigation。
  - **テスト要件**: 本人scope/session再認証/provider failure。
  - **Demo**: 本人が自分の明細だけ表示。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/engineer-self-service-portal-v2/tasks.md` のtask `A2` だけを `- [x]` にする
```

## T091 — `engineer-self-service-portal-v2` / B1. 経費申請/承認/archive

```text
あなたはSES Manager Proの `engineer-self-service-portal-v2` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/engineer-self-service-portal-v2/requirements.md
- .kiro/specs/engineer-self-service-portal-v2/design.md
- .kiro/specs/engineer-self-service-portal-v2/tasks.md

【担当task原文】
- [ ] B1. 経費申請/承認/archive
  - **Objective**: B1. 経費申請/承認/archive を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: receipt scan、approval、accounting outbox link。
  - **テスト要件**: 金額/receipt/差戻し/二重連携。
  - **Demo**: 経費→承認→会計待ち。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/engineer-self-service-portal-v2/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T092 — `engineer-self-service-portal-v2` / B2. 1on1/survey/privacy

```text
あなたはSES Manager Proの `engineer-self-service-portal-v2` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/engineer-self-service-portal-v2/requirements.md
- .kiro/specs/engineer-self-service-portal-v2/design.md
- .kiro/specs/engineer-self-service-portal-v2/tasks.md

【担当task原文】
- [ ] B2. 1on1/survey/privacy
  - **Objective**: B2. 1on1/survey/privacy を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 日程、公開/private note、campaign、匿名閾値、retention input。
  - **テスト要件**: visibility/最低回答数/通知。
  - **Demo**: 回答→HR限定相談→followup。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/engineer-self-service-portal-v2/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T093 — `engineer-self-service-portal-v2` / M. 回帰

```text
あなたはSES Manager Proの `engineer-self-service-portal-v2` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/engineer-self-service-portal-v2/requirements.md
- .kiro/specs/engineer-self-service-portal-v2/design.md
- .kiro/specs/engineer-self-service-portal-v2/tasks.md

【担当task原文】
- [ ] M. 回帰
  - **Objective**: M. 回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/mobile/PII leak。
  - **Demo**: 要員loginから全my機能一気通貫。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/engineer-self-service-portal-v2/tasks.md` のtask `M` だけを `- [x]` にする
```

## T094 — `accounting-payment-integration` / 0. G4/API spike/canonical mapping

```text
あなたはSES Manager Proの `accounting-payment-integration` specにおけるtask `0` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/accounting-payment-integration/requirements.md
- .kiro/specs/accounting-payment-integration/design.md
- .kiro/specs/accounting-payment-integration/tasks.md

【担当task原文】
- [ ] 0. G4/API spike/canonical mapping
  - **Objective**: 0. G4/API spike/canonical mapping を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: plan/API可否、sandboxまたはofficial fixture response、勘定/税/部門/取引先mapping、rate limit、fallback。
  - **Demo**: sandboxがあれば最小売上/仕入1件、未契約ならWireMock/official fixtureのspikeと本番blocker記録（本番コード変更なし）。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/accounting-payment-integration/tasks.md` のtask `0` だけを `- [x]` にする
```

## T095 — `accounting-payment-integration` / F1. connection/mapping/job DDLと既存connection移行

```text
あなたはSES Manager Proの `accounting-payment-integration` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/accounting-payment-integration/requirements.md
- .kiro/specs/accounting-payment-integration/design.md
- .kiro/specs/accounting-payment-integration/tasks.md

【担当task原文】
- [ ] F1. connection/mapping/job DDLと既存connection移行
  - **Objective**: F1. connection/mapping/job DDLと既存connection移行 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V75/V1/H2/smoke、暗号/token race/outbox。
  - **テスト要件**: unique/rotation/claim/CAS/tenant。
  - **Demo**: F1. connection/mapping/job DDLと既存connection移行 の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/accounting-payment-integration/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T096 — `accounting-payment-integration` / F2. AccountingProvider/freee/CSV

```text
あなたはSES Manager Proの `accounting-payment-integration` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/accounting-payment-integration/requirements.md
- .kiro/specs/accounting-payment-integration/design.md
- .kiro/specs/accounting-payment-integration/tasks.md

【担当task原文】
- [ ] F2. AccountingProvider/freee/CSV
  - **Objective**: F2. AccountingProvider/freee/CSV を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: canonical DTO、HTTP adapter、error分類、request ID。
  - **テスト要件**: WireMock全status/timeout/秘密非ログ。
  - **Demo**: F2. AccountingProvider/freee/CSV の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/accounting-payment-integration/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T097 — `accounting-payment-integration` / A1. mapping/preview/job管理UI

```text
あなたはSES Manager Proの `accounting-payment-integration` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/accounting-payment-integration/requirements.md
- .kiro/specs/accounting-payment-integration/design.md
- .kiro/specs/accounting-payment-integration/tasks.md

【担当task原文】
- [ ] A1. mapping/preview/job管理UI
  - **Objective**: A1. mapping/preview/job管理UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: connection health、mapping不足、preview、retry/cancel。
  - **テスト要件**: 財務permission、CSRF、二重click。
  - **Demo**: validation error修正→retry成功。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/accounting-payment-integration/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T098 — `accounting-payment-integration` / B1. 売上/取消連携

```text
あなたはSES Manager Proの `accounting-payment-integration` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/accounting-payment-integration/requirements.md
- .kiro/specs/accounting-payment-integration/design.md
- .kiro/specs/accounting-payment-integration/tasks.md

【担当task原文】
- [ ] B1. 売上/取消連携
  - **Objective**: B1. 売上/取消連携 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: invoice approval後outbox、external ID、訂正/取消。
  - **テスト要件**: 10回再送1件、取消状態、金額照合。
  - **Demo**: 請求→freee sandbox→取消。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/accounting-payment-integration/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T099 — `accounting-payment-integration` / B2. BP/経費/支払連携

```text
あなたはSES Manager Proの `accounting-payment-integration` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/accounting-payment-integration/requirements.md
- .kiro/specs/accounting-payment-integration/design.md
- .kiro/specs/accounting-payment-integration/tasks.md

【担当task原文】
- [ ] B2. BP/経費/支払連携
  - **Objective**: B2. BP/経費/支払連携 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: purchase/expense、payment sync、振込guard（採用時）。
  - **テスト要件**: 口座変更/二重支払/税/手数料/源泉。
  - **Demo**: BP支払→外部→支払済sync。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/accounting-payment-integration/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T100 — `accounting-payment-integration` / B3. 月次照合/closing

```text
あなたはSES Manager Proの `accounting-payment-integration` specにおけるtask `B3` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/accounting-payment-integration/requirements.md
- .kiro/specs/accounting-payment-integration/design.md
- .kiro/specs/accounting-payment-integration/tasks.md

【担当task原文】
- [ ] B3. 月次照合/closing
  - **Objective**: B3. 月次照合/closing を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 差異matrix、drilldown、closing warning/block。
  - **テスト要件**: 内部のみ/外部のみ/金額差/ignore理由。
  - **Demo**: 不一致解消後に締め可能。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/accounting-payment-integration/tasks.md` のtask `B3` だけを `- [x]` にする
```

## T101 — `accounting-payment-integration` / M. 回帰/障害訓練

```text
あなたはSES Manager Proの `accounting-payment-integration` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/accounting-payment-integration/requirements.md
- .kiro/specs/accounting-payment-integration/design.md
- .kiro/specs/accounting-payment-integration/tasks.md

【担当task原文】
- [ ] M. 回帰/障害訓練
  - **Objective**: M. 回帰/障害訓練 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL/sandbox、429/停止/復旧。
  - **Demo**: provider停止中に内部業務継続→復旧後再送。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/accounting-payment-integration/tasks.md` のtask `M` だけを `- [x]` にする
```

## T102 — `jp-pint-digital-invoice` / 0. G5/provider/spec version spike

```text
あなたはSES Manager Proの `jp-pint-digital-invoice` specにおけるtask `0` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/jp-pint-digital-invoice/requirements.md
- .kiro/specs/jp-pint-digital-invoice/design.md
- .kiro/specs/jp-pint-digital-invoice/tasks.md

【担当task原文】
- [ ] 0. G5/provider/spec version spike
  - **Objective**: 0. G5/provider/spec version spike を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: provider契約/API/webhook/validator/test participant/spec version/料金/SLA。
  - **Demo**: 契約済みならprovider sandbox送受信、未契約なら公式fixture/mockの証跡とB1/B2/MをPASSにしないblocker記録。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/jp-pint-digital-invoice/tasks.md` のtask `0` だけを `- [x]` にする
```

## T103 — `jp-pint-digital-invoice` / F1. participant/digital invoice/event DDL

```text
あなたはSES Manager Proの `jp-pint-digital-invoice` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/jp-pint-digital-invoice/requirements.md
- .kiro/specs/jp-pint-digital-invoice/design.md
- .kiro/specs/jp-pint-digital-invoice/tasks.md

【担当task原文】
- [ ] F1. participant/digital invoice/event DDL
  - **Objective**: F1. participant/digital invoice/event DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V76/V1/H2/smoke、state/idempotency。
  - **テスト要件**: unique/status/event order。
  - **Demo**: F1. participant/digital invoice/event DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/jp-pint-digital-invoice/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T104 — `jp-pint-digital-invoice` / F2. CanonicalInvoice/renderer/validator

```text
あなたはSES Manager Proの `jp-pint-digital-invoice` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/jp-pint-digital-invoice/requirements.md
- .kiro/specs/jp-pint-digital-invoice/design.md
- .kiro/specs/jp-pint-digital-invoice/tasks.md

【担当task原文】
- [ ] F2. CanonicalInvoice/renderer/validator
  - **Objective**: F2. CanonicalInvoice/renderer/validator を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: version adapter、XML security、validation report archive。
  - **テスト要件**: official fixture/golden/rounding/XXE。
  - **Demo**: 既存invoiceをvalidatorへ通す。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/jp-pint-digital-invoice/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T105 — `jp-pint-digital-invoice` / B1. provider送信/status/webhook

```text
あなたはSES Manager Proの `jp-pint-digital-invoice` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/jp-pint-digital-invoice/requirements.md
- .kiro/specs/jp-pint-digital-invoice/design.md
- .kiro/specs/jp-pint-digital-invoice/tasks.md

【担当task原文】
- [ ] B1. provider送信/status/webhook
  - **Objective**: B1. provider送信/status/webhook を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: accounting job再利用、participant verify、署名、fallback。
  - **テスト要件**: retry/duplicate/fake/out-of-order。
  - **Demo**: sandbox送信→delivered。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/jp-pint-digital-invoice/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T106 — `jp-pint-digital-invoice` / A1. 設定/送信/状態UI

```text
あなたはSES Manager Proの `jp-pint-digital-invoice` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/jp-pint-digital-invoice/requirements.md
- .kiro/specs/jp-pint-digital-invoice/design.md
- .kiro/specs/jp-pint-digital-invoice/tasks.md

【担当task原文】
- [ ] A1. 設定/送信/状態UI
  - **Objective**: A1. 設定/送信/状態UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 顧客preference、validation、status、XML/receipt link。
  - **テスト要件**: permission/participant未検証/field mask。
  - **Demo**: PDF顧客とPeppol顧客を別送信。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/jp-pint-digital-invoice/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T107 — `jp-pint-digital-invoice` / B2. 受信review

```text
あなたはSES Manager Proの `jp-pint-digital-invoice` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/jp-pint-digital-invoice/requirements.md
- .kiro/specs/jp-pint-digital-invoice/design.md
- .kiro/specs/jp-pint-digital-invoice/tasks.md

【担当task原文】
- [ ] B2. 受信review
  - **Objective**: B2. 受信review を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: secure parse/archive/match/review→purchase候補。
  - **テスト要件**: duplicate/不正XML/照合/人手確定。
  - **Demo**: 受信invoiceをBP支払候補へ。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/jp-pint-digital-invoice/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T108 — `jp-pint-digital-invoice` / M. provider受入/回帰

```text
あなたはSES Manager Proの `jp-pint-digital-invoice` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/jp-pint-digital-invoice/requirements.md
- .kiro/specs/jp-pint-digital-invoice/design.md
- .kiro/specs/jp-pint-digital-invoice/tasks.md

【担当task原文】
- [ ] M. provider受入/回帰
  - **Objective**: M. provider受入/回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL/provider official conformance。
  - **Demo**: end-to-end送受信と障害復旧。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/jp-pint-digital-invoice/tasks.md` のtask `M` だけを `- [x]` にする
```

## T109 — `ai-feedback-learning` / 0. G10/use case/PII/metric確定

```text
あなたはSES Manager Proの `ai-feedback-learning` specにおけるtask `0` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/ai-feedback-learning/requirements.md
- .kiro/specs/ai-feedback-learning/design.md
- .kiro/specs/ai-feedback-learning/tasks.md

【担当task原文】
- [ ] 0. G10/use case/PII/metric確定
  - **Objective**: 0. G10/use case/PII/metric確定 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: provider DPA、field allowlist、mask規則、保存期間、成功metric、禁止属性。
  - **Demo**: security/HR/product owner承認。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/ai-feedback-learning/tasks.md` のtask `0` だけを `- [x]` にする
```

## T110 — `ai-feedback-learning` / F1. version/run/item/feedback/outcome/evaluation DDL

```text
あなたはSES Manager Proの `ai-feedback-learning` specにおけるtask `F1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/ai-feedback-learning/requirements.md
- .kiro/specs/ai-feedback-learning/design.md
- .kiro/specs/ai-feedback-learning/tasks.md

【担当task原文】
- [ ] F1. version/run/item/feedback/outcome/evaluation DDL
  - **Objective**: F1. version/run/item/feedback/outcome/evaluation DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V75/V1/H2/smoke、legacy移行方針。
  - **テスト要件**: active一意、trace、tenant、保存期限。
  - **Demo**: F1. version/run/item/feedback/outcome/evaluation DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/ai-feedback-learning/tasks.md` のtask `F1` だけを `- [x]` にする
```

## T111 — `ai-feedback-learning` / F2. AiExecutionGateway/PII mask

```text
あなたはSES Manager Proの `ai-feedback-learning` specにおけるtask `F2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/ai-feedback-learning/requirements.md
- .kiro/specs/ai-feedback-learning/design.md
- .kiro/specs/ai-feedback-learning/tasks.md

【担当task原文】
- [ ] F2. AiExecutionGateway/PII mask
  - **Objective**: F2. AiExecutionGateway/PII mask を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 全AI呼出をgatewayへ、schema validation、raw prompt停止。
  - **テスト要件**: canary/prompt injection/provider error/log capture。
  - **Demo**: 送信payload inspectionでPII 0。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/ai-feedback-learning/tasks.md` のtask `F2` だけを `- [x]` にする
```

## T112 — `ai-feedback-learning` / B1. feedback/outcome連携

```text
あなたはSES Manager Proの `ai-feedback-learning` specにおけるtask `B1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/ai-feedback-learning/requirements.md
- .kiro/specs/ai-feedback-learning/design.md
- .kiro/specs/ai-feedback-learning/tasks.md

【担当task原文】
- [ ] B1. feedback/outcome連携
  - **Objective**: B1. feedback/outcome連携 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: matching画面採否、proposal/contract event、冪等trace。
  - **テスト要件**: 採用/却下/面談/成約/失注/重複event。
  - **Demo**: 推薦から成約までtimeline。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/ai-feedback-learning/tasks.md` のtask `B1` だけを `- [x]` にする
```

## T113 — `ai-feedback-learning` / B2. offline evaluation/version promotion

```text
あなたはSES Manager Proの `ai-feedback-learning` specにおけるtask `B2` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/ai-feedback-learning/requirements.md
- .kiro/specs/ai-feedback-learning/design.md
- .kiro/specs/ai-feedback-learning/tasks.md

【担当task原文】
- [ ] B2. offline evaluation/version promotion
  - **Objective**: B2. offline evaluation/version promotion を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: dataset version、baseline比較、threshold、shadow/rollback。
  - **テスト要件**: metric/gate/rollback/過去不変。
  - **Demo**: 基準未達version拒否→ruleへrollback。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/ai-feedback-learning/tasks.md` のtask `B2` だけを `- [x]` にする
```

## T114 — `ai-feedback-learning` / A1. evaluation dashboard

```text
あなたはSES Manager Proの `ai-feedback-learning` specにおけるtask `A1` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/ai-feedback-learning/requirements.md
- .kiro/specs/ai-feedback-learning/design.md
- .kiro/specs/ai-feedback-learning/tasks.md

【担当task原文】
- [ ] A1. evaluation dashboard
  - **Objective**: A1. evaluation dashboard を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: funnel/reason/latency/cost/segment privacy。
  - **テスト要件**: scope/少数非表示/金額単位。
  - **Demo**: 2version比較。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/ai-feedback-learning/tasks.md` のtask `A1` だけを `- [x]` にする
```

## T115 — `ai-feedback-learning` / M. 回帰/安全性

```text
あなたはSES Manager Proの `ai-feedback-learning` specにおけるtask `M` だけを担当する実装AIです。
この対話では、別taskの実装、ついでのrefactor、仕様追加、予約外migration作成を行わないでください。

【作業開始前に完全に読むもの】
- AGENTS.md
- .kiro/specs/customer-product-expansion-2026/README.md
- .kiro/specs/customer-product-expansion-2026/decision-log.md
- .kiro/specs/customer-product-expansion-2026/gate-decisions-g1-g6.md
- .kiro/specs/customer-product-expansion-2026/shared-standards.md
- .kiro/specs/customer-product-expansion-2026/dependency-matrix.md
- .kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md
- .kiro/specs/ai-feedback-learning/requirements.md
- .kiro/specs/ai-feedback-learning/design.md
- .kiro/specs/ai-feedback-learning/tasks.md

【担当task原文】
- [ ] M. 回帰/安全性
  - **Objective**: M. 回帰/安全性 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL、mock/rule/Gemini adapter、PII scan。
  - **Demo**: mock既定の既存機能回帰と実provider opt-in。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

【着手判定】
1. design/tasksに記載された先行taskが完了し、そのdiffが現在のbranchへ取り込まれているか確認する。
2. decision-logでこのtaskに影響するblocking=yesが決定済みか確認する。blocking=noでも本taskの仕様を変える項目は決定または明示的既定が必要。
3. READMEの予約migration番号が未使用で、先行番号がmerge済みか確認する。競合時は採番を勝手に変更せず停止する。
4. 同じ共有ファイルを別AIが編集中でないか確認する。並行時は自分の所有ファイル一覧を先に宣言する。
5. 必須のDocker/MySQL/provider sandbox/法務確認がない場合、代替検証がspecに定義されているか確認する。
いずれかを満たせなければ、コードを変更せず「不足条件・影響・再開条件」だけ報告してください。

【実装ルール】
- 変更は担当taskの受入条件へ直接対応する最小範囲に限定する。既存のlayering、ApiResult、CSRF、監査、状態機械、楽観ロック、4言語i18n規約を守る。
- tenant/data/organization/file scopeはquery/service境界で強制し、画面表示後のfilterで代替しない。list/detail/count/export/download/notification/schedulerの母集団を一致させる。
- DDL変更taskでは、V1統合baseline、予約Flyway、application-test.ymlのH2 replay、engineer-schema-h2.sql、MySQL smoke assertを同時に同期する。
- 外部APIはDB transaction内で同期呼出しせず、idempotency key、correlation ID、retry/backoff、timeout、監査、失敗復旧を設計どおり実装する。
- 外部ポータル/AIへ内部entityを直接公開せず、公開DTOとallow-listを使う。未知fileやscan障害はfail-closedとする。
- まず失敗/境界/権限を含む検証可能な成功条件を列挙し、実装後にtask記載のテストとDemoを実行する。

【完了報告フォーマット】
1. 着手判定結果と前提
2. 変更ファイル一覧（各ファイルの変更目的）
3. 対応requirements ID → 実装箇所 → 自動テスト → Demoの対応表
4. 実行したコマンドと結果（未実行は理由）
5. tenant/data/organization/file scope、CSRF、監査、i18n、migration同期の確認結果
6. 未検証事項、残存risk、rollback手順、次taskの開始条件
7. 全条件を満たした場合だけ `.kiro/specs/ai-feedback-learning/tasks.md` のtask `M` だけを `- [x]` にする
```

