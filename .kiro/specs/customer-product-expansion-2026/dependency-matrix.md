# 依存・共有ファイル・並行実装マトリクス

## 1. 依存関係

```text
G0 ─ tenant ─ organization ─ identity ─ archive ─ productivity
                         ├─ BP master ─ approval ─ order ─ external portal
                         ├─ CRM ────────────────┘
                         ├─ attendance ─ staffing ─ engineer portal
                         └─ dispatch compliance ┘
order + BP + archive ─ accounting ─ JP PINT
CRM + proposal + staffing + outcomes ─ AI feedback
```

## 1.1 G0決定後の現行境界

- 2026-07-26の発注者決定により、現在の正式な配備方式は顧客ごとの独立DBである。
- `multi-company-tenant-isolation` はT001のinventoryを完了した状態で停止する。共有DBの全表tenant_id化を実装済みとは扱わない。
- V59は作成せず、従来の予約を取消して永久欠番とする。T002/F1、DDL、TenantContext、tenant interceptor、tenant単位backup/restoreは共有DB方式の正式再開まで延期し、現在のtenant実装taskは存在しない。再開時はV59を再利用せず、当時のFlyway最新番号`latest + 1`から新たに採番する。
- データ隔離の考え方は削除しない。現行のDB境界、認証、データスコープ、ファイル参照検証を維持し、将来の新規テーブルもtenant互換性とglobal/tenant分類を設計時に確認する。

## 2. 主な共有ファイル

| ファイル/領域 | 触るspec | 競合回避 |
|---|---|---|
| `SecurityConfig.java` | tenant, identity, external portal, engineer portal | Wave順に逐次。各specでsecurity chain全体テスト |
| `BaseEntity.java`/MyBatis config | tenant（共有DB再開後） | 現在は変更しない。共有DB方式の正式再開後にtenant担当が所有 |
| `GlobalControllerAdvice`/sidebar | organization, identity, productivity | organization→identity→productivity |
| `m_menu`/`t_role_menu` | ほぼ全spec | 実装開始時のFlyway採番順、sort_order表を各designに記載 |
| `FileStorageServiceImpl`/download | archive, portal, engineer | archiveがStorage abstractionとfail-closedを先行 |
| `Contract`/`ContractServiceImpl` | approval, order, dispatch, staffing | approval→order→dispatch→staffing |
| `InvoiceServiceImpl` | approval, order, accounting, JP PINT | approval→order→accounting→JP PINT |
| `WorkRecordServiceImpl` | order acceptance, attendance連携 | orderを先行。雇用勤怠は別テーブルで既存精算を汚さない |
| `Customer`/customer画面 | CRM, portal | CRM先行、portalは参照のみ |
| `BpPayment`/invoice画面 | BP master, approval, accounting | BP→approval→accounting |
| `SysUser`/user画面 | tenant, organization, identity | 逐次、各段階でログイン回帰 |
| `FreeeIntegrationServiceImpl` | accounting, engineer portal | accountingがadapter分割、portalは公開DTOのみ |
| `Ai*` | AI feedbackのみ | 他specはAIパッケージを変更しない |

## 3. 並行可否

- 並行可: BP master と CRM。
- 並行可: dispatch compliance と attendance（ただしContract/WorkRecordの担当メソッドを分離）。
- 条件付き並行: external portal と engineer portal。SecurityConfig担当をportal側に一本化する。
- 並行禁止: tenant/organization/identity/archive/productivityのWave 0。現時点のtenantはT001のみで、T002以降は共有DB再開まで延期。
- 並行禁止: accounting と JP PINT。
- 全specのmigrationを同時に作らない。番号順に基盤DDLタスクだけ先にマージする。

## 4. 既存hardeningとの関係

`.kiro/audits/2026-07-26-unresolved-hardening-action-plan.md` のTask A/B/C0は新機能とは別レーンである。
ただし大規模export、ポータル公開、全社検索の本番受入前に、Task B（ストリーミング）とC0（実MySQL容量測定）を
完了させる。新specが全件List/byte[]応答を再導入してはならない。
