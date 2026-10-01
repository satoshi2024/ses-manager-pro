# S-NF R3 remediation 最終 Gate 証跡（2026-10-01）

## 対象

| 項目 | 値 |
|---|---|
| branch | `codex/snf-r3-remediation-nf09` |
| worktree | `C:\work\ses-snf-r3-remediation-b2-b` |
| 基線 | `b1f44ac65ed5908e843c4b0410ef0ed0a9719207` |
| Gate 対象 | 上記基線から本証跡を含む remediation commit まで |
| main | `996289c00983bfccd0b75c4d3bdd3dcc26904136`（変更なし・clean） |

push、PR 作成、main への merge、production approval は実施していない。

## 実装要約

### NF09 tenant / 法人 / actor 境界

- Digital Invoice、inbound、webhook、Peppol、External Account の read/write を
  security-bound tenant と一意な法人へ束縛した。
- tenant / 法人が欠落・不一致・曖昧な場合は推測せず fail-closed とした。
- HUMAN / SYSTEM / PROVIDER の actor attribution を列・イベント・API 境界で整合し、
  SYSTEM/PROVIDER をユーザー ID `1` として記録しない。
- Digital Invoice の provider RECEIVE は inbound 正規経路だけで処理し、outbound event API
  からの RECEIVE は拒否する。
- Document 登録は tenant、法人、document type、business key を冪等境界に含め、
  Digital Invoice 文書の list/detail/download を同一 scope へ揃えた。
- Accounting worker と revoke poll は明示 tenant 単位で実行し、別 tenant の行を取得しない。

### V182

- `V182__nf09_tenant_scope_hardening.sql` を追加した。
- `t_peppol_participant`、`t_digital_invoice`、`t_digital_invoice_event`、
  `t_external_account_reference` に tenant / 法人 ownership を追加した。
- scope を含む一意制約、actor pair 制約、event 親子 scope 外部キーへ移行した。
- 自動推測できない既存行は `t_nf09_scope_repair_queue` へ記録する。
- H2 schema と migration smoke / concurrency test を同期した。
- V161 / V173 は変更していない。migration は 157 ファイル、version 重複 0。

### Gate 中に閉じた回帰

- Customer 更新 API は旧クライアントが `deliveryPreference` を送らない場合に既存値を保持する。
- Customer Contact の主担当重複判定は current read (`FOR UPDATE`) を使用する。
- 注文明細からの契約化は unique 競合後に current read で勝者契約を返す。
- fast suite の共有 H2 に依存していた Asset 法人 principal と Expense `RECEIPT` 文書種別を
  各テストの明示 fixture へ変更した。
- performance test は暗黙 default tenant に依存せず、明示的に tenant context を設定・解放する。
- real Chrome の connector / Browser Demo fixture を dedicated tenant、法人、account link、
  契約 ownership へ明示的に揃えた。production の fail-closed 境界は緩和していない。

## Gate 結果

すべて `-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds` を指定して実行した。

| Gate | 結果 |
|---|---|
| `mvn test` | **4007 / 0 failures / 0 errors / 0 skipped**、BUILD SUCCESS（12:58） |
| `mvn test -Pmysql-tests` | **160 / 0 / 0 / 0**、BUILD SUCCESS（Docker / MySQL 8、40:44） |
| `mvn test -Pperformance-tests` | **1 / 0 / 0 / 0**、BUILD SUCCESS、p95 63ms、heap 増加 53KB |
| `mvn test -Pbrowser-tests` | **14 / 0 / 0 / 0**、BUILD SUCCESS（real Chrome、2:05） |
| `mvn -DskipTests test-compile` | PASS |
| `MySqlTestShardInventoryTest` | **1 / 0 / 0 / 0**、PASS |
| migration inventory | 157 files / duplicate version 0 / latest V182 |
| `git diff --check` | PASS |

fast suite の数値は本実行で更新された 656 件の Surefire XML を合算して確認した。
MySQL profile は production code / migration の最終修正後に全件を実行した。その後の変更は
fast / performance / browser の test fixture と assertion のみであり、production code / migration は変更していない。

browser profile は旧 loopback blocker の解消後、real Chrome で全件を実行した。初回は新しい
tenant / 法人 fail-closed 境界に未適合だった legacy fixture を検出したため、各 fixture に
security-bound tenant、法人、一意な dedicated DB binding を明示した。修正後の全 profile は
zero failure / zero error / zero skipped であり、証跡は `target/browser-evidence`、
`target/browser-g2-evidence`、`target/browser-m-evidence`、`target/browser-r8-evidence` に生成した。

追加の収束確認:

| 対象 | 結果 |
|---|---|
| Asset / Contract / CustomerContact targeted | **75 / 0 / 0 / 0** |
| Asset 単独（法人 fixture 修正後） | **16 / 0 / 0 / 0** |
| Expense 単独（RECEIPT fixture 修正後） | **7 / 0 / 0 / 0** |
| Contract 単独（current-read assertion 最終化後） | **54 / 0 / 0 / 0** |

## テスト弱化確認

- `@Test` / `@ParameterizedTest` の削除なし。
- `@Disabled` の追加なし。
- skip 追加なし。fast / MySQL / performance の全 Gate で skipped 0。
- browser Gate も skipped 0。Chrome 未検出時の skip や offline fallback は使用していない。
- service loader の互換拡張は、認証済み `@WithMockUser` のみを `default` tenant の
  `LoginUser` へ置換する。未認証テストへの管理者生成と法人 fixture 投入は
  `@EnableDefaultTenantTestContext` の明示 opt-in に限定し、tenant 欠落境界のテストは
  `@DisableDefaultTenantTestContext` で互換変換を無効化する。
- migration の `outOfOrder` / ignore 設定による回避は追加していない。

## production enablement 条件

本 Gate は merge 可否の技術証跡であり、production approval ではない。V182 適用後、運用 owner は
次を実 DB で確認し、推測せず全 PENDING 行を解消する必要がある。

```sql
SELECT entity_type, reason, status, COUNT(*)
FROM t_nf09_scope_repair_queue
GROUP BY entity_type, reason, status
ORDER BY entity_type, reason, status;

SELECT *
FROM t_nf09_scope_repair_queue
WHERE status = 'PENDING'
ORDER BY entity_type, entity_id;
```

enablement 条件:

1. repair queue の `PENDING` が 0 件であること。
2. NF09 scoped table の tenant / 法人未確定行が 0 件であること。
3. production owner が migration 結果と provider credential / callback scope を承認すること。
4. AI / 外部送信等の既存 feature flag は別途 owner approval まで OFF を維持すること。

これらを満たす前に production approval を付与しない。
