# Design — 複数法人・テナント分離

## 1. DDL（V59は永久欠番、現在は未作成）

G0の現在正式モードは顧客ごとの独立DBである。以下は共有DB方式を再開する場合の将来設計であり、今回のT001ではDDL、V59、V1、H2 schemaを変更しない。V59は作成せず永久欠番として保持する。将来再開する場合もV59を補完・再利用せず、その時点のFlyway最新番号`latest + 1`から新しいmigrationを採番する。独立DBであることを理由にデータ隔離の設計を削除せず、新規テーブルにも将来のtenant互換性を確認する。

- `m_tenant(id, tenant_code, tenant_name, status, default_locale, timezone, version, timestamps)`。
- `m_legal_entity(id, tenant_id, legal_entity_code, name, corporate_number, invoice_registration_number,
  address, representative_name, default_flag, status, version, timestamps)`。
- `sys_user.tenant_id`、主要業務/マスタ/監査/通知/連携テーブルへ`tenant_id NOT NULL`。
- 法人主体が必要な`quotation/contract/invoice/bp_payment/order`へ`legal_entity_id`。
- 既存UNIQUEを`(tenant_id, business_key)`へ変更。FKは可能な範囲で`(tenant_id,id)`複合参照にする。

大表は `NULL追加 → default tenant backfill（batch）→ index/constraint → NOT NULL` の段階migrationとし、
MySQL実行時間・lockを事前計測する。V1には最終形だけを反映する。

## 2. tenant context（共有DB再開後）

- 新 `TenantContext`（ThreadLocal、AutoCloseable scope）と`TenantResolver`。
- `TenantContextFilter`: 認証済み`LoginUser.tenantId`とrequest hostのtenant一致を検証。
- `MyBatisPlusConfig`: `TenantLineInnerInterceptor`をpaginationより前へ追加。
- 除外表は`m_tenant`とplatform管理用表だけ。`m_menu`をglobalにするかtenant overrideにするかは
  初期実装ではglobal seed + tenant権限mappingとする。
- `@Async`は`TaskDecorator`でtenant/user/localeを伝播、schedulerはtenant一覧を明示loopし
  `try (TenantScope ignored = ...)`で必ず解除する。

## 3. 認証とURL

- `LoginUser`へtenantId/legalEntityIdsを追加。
- 独立DB（現在）: 既存`/login`、顧客別DB境界、既存認証・データスコープを維持する。tenant context/interceptorは今回導入しない。
- 共有DB: `{tenant}.example.jp/login`を推奨。tenant code form入力はfallbackのみ。
- username UNIQUEは`(tenant_id, username, deleted_flag相当)`。
- platform管理は別prefix `/platform/**`、別authority `ROLE_PLATFORM_ADMIN`、通常sidebarに表示しない。

## 4. ファイル・キャッシュ・ジョブ（共有DB再開後）

- 保存keyを`tenant/{tenantId}/{kind}/{uuid}`へ変更。DB参照のない未知fileは拒否する。
- dashboard/menu/permission cache keyへtenantIdを含める。
- `shedlock.name`はtenant IDをsuffixに含めるか、1ジョブが全tenantをloopする方式へ統一する。
- audit logはtenant_id必須、platform操作は別event type。

## 5. 変更対象棚卸し（将来実装の対象台帳）

`BaseEntity`, `SysUser`, 全entity/mapper、`MyBatisPlusConfig`, `SecurityConfig`, `CustomUserDetailsService`,
`GlobalControllerAdvice`, scheduler群、AsyncConfig、CacheConfig、FileStorage/FileScope、Audit、Export、全annotation SQL。
`rg "@Select|@Update|@Delete|@Insert"`の結果をtasksの添付資料へ残し、1件ずつ分類する。

## 6. テスト（共有DB再開時の将来テスト）

- `TenantIsolationIntegrationTest`: 全主要APIをA/B fixtureでparameterized実行。
- annotation SQL、集計、export、download、notification、scheduler、cacheの漏洩テスト。
- migration reconciliation: 各表count、invoice total、work_record billing/payment合計。
- async context伝播とfinally解除、thread reuse時のtenant混線テスト。
