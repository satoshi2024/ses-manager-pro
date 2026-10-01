# S-NF production rehearsal 実行契約

状態: **DRAFT / NOT EXECUTED**。実production接続・credential使用・deployを許可する文書ではない。
初期baseは`1443fb68943849a3260d7ebe005259f3eb609d60`。最終試験は統合後の固定RC SHAで実施する。

## 証拠packet

各runにRC SHA、tree clean状態、command、開始終了UTC、Java/MySQL/browser/image digest、
source/target識別子（secretなし）、exit code、test/failure/error/skipped、log/XML/evidence SHA256を記録する。
過去のtargetや異なるrunのXMLを混ぜない。`verify-like-ci.ps1`は各suiteでcleanするため、
run全体logを保管し、suite別XMLを必要とする保存方法は実行前に確定する。
test selectionを狭める`-Dtest`を最終full gateに渡さない。

## 必須code gate

隔離RC worktreeで実行し、主checkoutでは実行・開発しない。

```powershell
& 'C:\work\ses-manager-pro\apache-maven-3.9.6\bin\mvn.cmd' -B test
& 'C:\work\ses-manager-pro\apache-maven-3.9.6\bin\mvn.cmd' -B test -Pmysql-tests
& 'C:\work\ses-manager-pro\apache-maven-3.9.6\bin\mvn.cmd' -B test -Pperformance-tests
& 'C:\work\ses-manager-pro\apache-maven-3.9.6\bin\mvn.cmd' -B test -Pbrowser-tests
.\scripts\verify-like-ci.ps1 -MavenExecutable 'C:\work\ses-manager-pro\apache-maven-3.9.6\bin\mvn.cmd'
git diff --check
```

上記個別commandは診断用にも使える。最終gateではverify-like-ciがFast/MySQL/Performance/Browserと
backup unit/integrationを実行するため、理由なく同じsuiteを重複実行しない。
全選択対象が存在し、skip=0を必須とする。環境不足・missing report・cancelledをPASSにしない。
remote公開が許可された後はRC headとmerge後main両方の全required jobを終端まで確認する。

## Migration rehearsal

1. RCのversioned/repeatable一覧、正規化version重複0、Flyway checksum、MySQL shard inventoryを照合する。
2. 空DBに全migrationを適用する。181 versioned + 1 repeatableは初期baseの値であり、RCの追加を別記録する。
3. productionと同じ開始Flyway version/schema形状の合成または正式承認された匿名化snapshotでupgradeする。
4. V182前にbackup/restoreを隔離targetで実証し、writer drain、DDL lock timeout、容量、binlog、schema driftを検査する。
5. V182の自動帰属は一意な根拠だけで行い、曖昧行はrepair queueへ残す。旧migrationをchecksum変更しない。
6. 認可されたdata ownerが根拠付きrepair案を確認し、tenant/legal entity/親子/actor/callbackを再照合する。
   queueだけをRESOLVEDへ更新して完了にしない。実repair DMLは別承認・CAS・audit・backup計画を必要とする。
7. fresh/upgrade/中断時のforward-fixとrestoreをそれぞれ検証する。DDLの自動down migrationを仮定しない。

## V182 postflight読取SQL

以下はV182適用後のschema用。preflight時に列・tableの存在を確認する。ここでは実行していない。

```sql
-- 修復待ちと未知状態。PENDING=0だけで完了にはしない。
SELECT status, entity_type, reason, COUNT(*) AS row_count
FROM t_nf09_scope_repair_queue
GROUP BY status, entity_type, reason;

-- 全4表でNULL/空の帰属を確認する。論理削除行も監査上の対象として除外しない。
SELECT 't_peppol_participant' AS source_table, COUNT(*) AS unresolved
FROM t_peppol_participant
WHERE tenant_id IS NULL OR TRIM(tenant_id) = '' OR legal_entity_id IS NULL
UNION ALL
SELECT 't_digital_invoice', COUNT(*) FROM t_digital_invoice
WHERE tenant_id IS NULL OR TRIM(tenant_id) = '' OR legal_entity_id IS NULL
UNION ALL
SELECT 't_digital_invoice_event', COUNT(*) FROM t_digital_invoice_event
WHERE tenant_id IS NULL OR TRIM(tenant_id) = '' OR legal_entity_id IS NULL
UNION ALL
SELECT 't_external_account_reference', COUNT(*) FROM t_external_account_reference
WHERE tenant_id IS NULL OR TRIM(tenant_id) = '' OR legal_entity_id IS NULL;

-- event親子scope、孤児の確認。
SELECT COUNT(*) AS orphan_or_scope_mismatch
FROM t_digital_invoice_event e
LEFT JOIN t_digital_invoice d ON d.id = e.digital_invoice_id
WHERE d.id IS NULL OR NOT (e.tenant_id <=> d.tenant_id)
   OR NOT (e.legal_entity_id <=> d.legal_entity_id);

-- VERIFIED callback identityの全scopeを横断した重複が0件であること。
SELECT provider, scheme_id, participant_id, COUNT(*) AS duplicate_count
FROM t_peppol_participant
WHERE deleted_flag = 0 AND status = 'VERIFIED'
GROUP BY provider, scheme_id, participant_id
HAVING COUNT(*) > 1;

-- ENGINEER所有accountとownerの帰属一致。USER所有は別の承認済み根拠を照合する。
SELECT COUNT(*) AS engineer_account_scope_mismatch
FROM t_external_account_reference a
LEFT JOIN t_engineer e ON e.id = a.assignee_id
WHERE a.assignee_type = 'ENGINEER'
  AND (e.id IS NULL OR NOT (a.tenant_id <=> e.tenant_id)
       OR NOT (a.legal_entity_id <=> e.legal_entity_id));

-- legacy主体を人の実行と捏造しない。残存数と扱いをownerが確認する。
SELECT actor_type, confirmation_source, COUNT(*) AS row_count
FROM t_digital_invoice GROUP BY actor_type, confirmation_source;
SELECT actor_type, confirmation_source, COUNT(*) AS row_count
FROM t_digital_invoice_event GROUP BY actor_type, confirmation_source;
```

PASSにはPENDING=0、4表unresolved=0、孤児/不整合/重複=0、repair承認者・根拠・version記録が必要。
SQLの一致だけでは実業務帰属を証明しない。invoice/customer/order/contractの全参照とlegal entity正本も
承認済みrepair packetで照合する。LEGACY_UNRESOLVEDを架空の人のIDへ置換しない。

## Staging・運用・有効化

- synthetic stagingでrole/tenant/CSRF/session、NF01〜10の実装済み全動線を確認する。
  NF04は再起動・複数tab・offline・衝突復旧を実browserで検証し、一般14 browser試験で代用しない。
- NF05/NF08/NF09の実adapterをprovider sandboxで確認する。secretは承認済みsecret storeから注入し、
  log/fixture/commitへ出さない。DPA/外送/許可先/model/cost/停止条件の正式決定前は実データを送らない。
- feature flagは既定OFF、1項目ずつ対象tenantで有効化する。emergency stopで新規外送/claimを止め、
  in-flight/未確認要求は消去せず追跡する。NF06/NF07が未実装のまま全機能完成とは判定しない。
- backup/restore/PITRは既存`ops/backup/tests/run-integration.sh`と運用runbookを使用し、代表性profileで
  RPO 15分/RTO 4時間を実測する。CIの小規模synthetic結果でproduction達成を宣言しない。
- Privacy disposition/hold journalは古いrestore snapshotと独立した信頼水準で保持し、再公開前に再適用する。
- notification/outbox lag、provider failure/retry、repair count、scope拒否、backup age、DLQ、cost、
  p95/5xxを監視する。alert先と対応者、閾値、観測期間はproduction運用packetで固定する。
- binary rollbackはschema互換・外部副作用・data変更が安全な場合のみ。互換性不明、data repairや
  撤権等の不可逆事象では停止・隔離してforward-fixを選び、安易な旧DB復元で新しい取引を失わない。

## Go/No-Go署名

Production Ownerはapproved scopeと段階有効化、Securityは認可/secret/外送/停止、Privacy/Legalは
policy/DPA/保持・処分・DSAR、Operationsはmigration/backup/restore/監視/当番をそれぞれ署名する。
AIは技術案・証拠・独立Reviewを作成するが、実在責任者の署名を代行しない。
未解消P0/P1、未実装approved scope、未承認実依存、代表性不明、未検証rollback、未署名のいずれかでNO-GO。
