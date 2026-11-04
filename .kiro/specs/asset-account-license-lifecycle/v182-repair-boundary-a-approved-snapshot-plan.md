# V182 境界 A 承認資格照会・ロック済み集約スナップショット計画

**状態:** PLAN REVIEW PENDING（Astra 独立レビュー待ち）
**作成日:** 2026-11-04
**固定基点:** `8aabe1d80834978e07bac4704adec22bf055b0dd`
**対象:** NF09 V182 boundary-A の承認正本・資格の検証境界、および完全なロック済み読取スナップショットのみ。DML executor は本計画の対象外。

## 1. 現状と根拠

- V182 は `t_digital_invoice` の帰属候補を `t_invoice → m_customer`、`t_sales_order`、`t_contract` の有効行から集め、候補が一意でないものを `t_nf09_scope_repair_queue` に隔離する。イベントの帰属は親インボイスからのみ導出する。
- V182 runbook §8 の境界 A は `t_digital_invoice` 1行、その全 `t_digital_invoice_event`、親/子の修復キューを一つの集約と定義する。Peppol の identity conflict group は境界 B、外部アカウントは境界 C。
- 既存 executor boundary-A 計画はロック順を親 invoice → 子 event（`id ASC`）→ 親 queue → 子 event queue（`entity_id ASC`）とする。CAS の実装や結案 DML は後続バッチ。
- `com.ses.service.repair` の13 Javaコンポーネント（Approval/Parent/Child/Queue snapshot、scope/result、carrier/member、codec、selector、discovery、例外）は純粋Java値/計算であり、Mapper、DB接続、`FOR UPDATE`、認証・承認照会を持たない。`RepairEvidenceComponentsTest` の合成fixtureも本番修復器ではない。
- 固定候補には NF09 計画書がまだ存在しない。源 worktree `72cf4a59f9ecf6f360200fddd2c10347084e21b1` との差分のうち NF09 関連は `inventory.md`、boundary-A 計画の追加、および `CarrierEvidence.java`、`InitialCarrierSelector.java`、`RepairEvidenceCodec.java`、`RepairRetryDiscoveryService.java`、`RepairEvidenceComponentsTest.java` の変更。候補全体と源 HEAD を同一視しない。その他の源 HEAD 差分はこの計画の基点に取り込まない。

## 2. 目的・境界

次の実装に先立ち、改変可能な呼出入力ではなく、確認済みの承認権威と認証主体から現行 ticket・round・承認資格を解決する契約を定める。同じ処理契約の中で、境界 A 全行と帰属根拠を単一DBトランザクションでロックして取得し、承認 scope/version/hash と snapshot が一致することを示す。

この計画では**読取・検証のみ**。`DIGITAL_INVOICE` と `DIGITAL_INVOICE_EVENT` の親/子/queue が唯一の修復対象。`t_invoice`、`t_sales_order`、`t_contract`、`m_customer` は V182 が参照した帰属証拠としてのみ読取対象で、更新対象ではない。各参照が存在する場合は `deleted_flag = 0` とし、参照先ID、tenant、legal entity の全候補を取得して一意性と相互整合性を確認する。欠落、論理削除、異スコープ、複数候補、読取不能は拒絶する。

`t_peppol_participant`/その callback conflict group は境界 B であり、ロック・更新しない。`t_external_account_reference` は境界 C であり、ロック・更新しない。A の根拠照会で Peppol 整合性が未解決と判明した場合は A を進めず、人手裁定へ fail-closed する。scope を広げる必要が出たら別計画レビューが必要。

## 3. 信頼・承認照会契約

### 3.1 未確定のハードゲート

Production Owner、帰属判定の業務責任者、承認の正本システム/保管先、署名検証鍵・失効確認方法、IdP が未確定。旧計画の `t_approval_request` / `t_approval_action` は提案上の仮定であり、現行の正本または承認済み権威として扱わない。Owner が権威ソース、資格体系、失効/期限の意味、証跡保持責任者を文書承認するまで adapter の接続・実行を禁止し、全要求を拒否する。テスト helper、caller の `approved=true`、任意SQL、DB role、ticket文字列だけによる承認は不可。

### 3.2 契約（提案シグネチャ）

```java
public final class RepairApprovalAuthority {
    public VerifiedRepairApproval resolveCurrent(RepairApprovalQuery query, AuthenticatedActor actor, Instant now);
    public void assertCurrent(VerifiedRepairApproval approval, AuthenticatedActor actor, Instant now);
    public static final class VerifiedRepairApproval { /* private constructor; immutable claims */ }
}

public record RepairApprovalQuery(String ticketId, RepairTargetScope target) {}
public record QualifiedApprover(String stableSubject, String qualificationRef, String signatureDigest) {}
```

`AuthenticatedActor` は確立済みサーバー認証/IdP principal からのみ生成し、呼出者指定IDを受け付けない。実装は authority から署名済み/改ざん検出可能な ticket 状態を取得し、現在 round のみを評価する。ticket、用途、invoice、tenant、legal entity、承認時 invoice version、完全な承認対象 aggregate hash が照会対象と完全一致すること、ticket が承認済み・未失効・期限内であることを検証する。全必須承認者が同一の現在 round で実際に承認し、各承認者の当時および現在有効な業務資格・tenant管轄を確認する。承認者重複、実行者との SoD 違反、旧round流用、取消/期限/署名/IdP照会不明は拒絶する。承認済み資格ルールと SoD の具体値は Owner の承認事項で、ここでは人数・役職を発明しない。

`VerifiedRepairApproval` は通常のconstructor/factoryを公開しない opaque immutable class とし、`RepairApprovalAuthority` の検証経路だけが生成する。source adapterは未検証値をauthorityへ返すだけで、型を直接発行できない。`assertCurrent` はticket署名、同一round、資格/失効、期限、scopeとhashを再照会する。Loaderはopaque値の出所をauthorityへ再検証し、active write-drain leaseおよび単一DB transaction内で対象invoice/round/hashに結び直す。検証失敗、取得不能、曖昧応答、鍵/資格/IdP不在は例外で停止し、空値・cache・booleanへのfallbackをしない。authority と DB を跨ぐ原子性が得られないため、取消との競合をどう直列化するか（署名付き短命承認 lease、または同等のOwner承認済み契約）は実装前Gateとする。

## 4. ロック済み完全 snapshot 契約

### 4.1 API と不変値

```java
public final class LockedInvoiceAggregateLoader {
    public LockedInvoiceAggregateSnapshot loadForUpdate(RepairTargetScope target, VerifiedRepairApproval approval);
    public static final class SnapshotAssemblyToken { /* private constructor; transaction-bound */ }
}

public final class RepairWriteDrainGate {
    public VerifiedWriteDrainLease requireActiveLease(RepairTargetScope target, Instant transactionDeadline);
    public static final class VerifiedWriteDrainLease { /* private constructor; active writer fence */ }
}

public final class LockedInvoiceAggregateSnapshot {
    private LockedInvoiceAggregateSnapshot(/* complete locked rows and binding token */) {}
    public ParentInvoiceSnapshot invoice();
    public List<ChildInvoiceEventSnapshot> events();
    public List<ScopeRepairQueueSnapshot> queues();
    public InvoiceOwnershipEvidence ownership();
    public String aggregateHash();
}
```

これは後続設計用の契約で、現時点で既存クラスと統合/実装しない。snapshotのconstructorはprivateとし、loaderが保持するtransaction-bound `SnapshotAssemblyToken` を要する制御factoryだけを同一package内に置く。Tokenはprivate constructorで外部発行不可、当該transaction・approval round・invoice/hashに束縛する。factoryは親・全event・全対象queue・帰属根拠の完全性を検証した後だけsnapshotを生成する。public APIは防御的コピー/immutable listだけを返す。行の主キー、親子関連、scope両列、削除状態、現行schemaにある全CAS対象version（親とqueue）、queue status/reason、帰属元の参照IDと実在する版/更新識別子を保持する。NULLと空文字を同一扱いせず記録する。型名だけでは信頼済みと見なさず、loaderと後続CAS側はauthority発行のopaque approvalと同一transaction内で発行したsnapshot binding tokenの出所・invoice・round・aggregate hashを検証する。

### 4.2 DB transaction と決定的取得順

V182 boundary-A 計画 §2.5 の保守window/write-drainを、loader開始前の必須条件かつtransaction終了まで維持する条件とする。既存行の `FOR UPDATE` は未存在event/queueの挿入を防がず、eventの複合FKもNULL-scope行のphantomを防止しないため、行ロック/hashだけで完全snapshotを主張しない。信頼できるwrite-drain gateが、全event/queue書込経路（API、job、migration/operatorを含む）を遮断して継続中であることを証明するopaque leaseを提示しなければ拒否する。lease失効・確認不能・coverage不明ならtransactionを開始しないか直ちにrollbackする。通常のreadonly boolean、呼出者フラグ、テストhelperはdrain証明に使わない。

実際の承認済みdrain機構/証明sourceは未確定であり人工Gateである。`RepairWriteDrainGate.requireActiveLease(...)` とopaque `VerifiedWriteDrainLease` は候補契約に留め、Leaseを発行するDB fence/maintenance controlを捏造しない。transaction中の継続保証、writer集合の網羅性、解除の直列化をOwner/Operations/DBAが確定するまでloaderはfail-closedとする。

drain leaseが有効な状態で、一つの `@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)` read transaction が全取得を包含する。最初に対象 `t_digital_invoice` を `FOR UPDATE`。次に、V182 が実際に参照した `t_invoice`/`t_sales_order`/`t_contract` と `m_customer` の存在する帰属根拠をテーブル順 `t_invoice → t_sales_order → t_contract → m_customer`、各表内主キー昇順に locking read し、soft-delete とID結合を再検証する。その後、既定契約どおり全イベントを `digital_invoice_id` 指定・`id ASC FOR UPDATE`、親queue、子queueを `entity_id ASC FOR UPDATE` の順で取得する。欠落を空listとみなさず、対象集合の完全性を別途照合する。queueのparent FKがないため、正確な親/子queue集合の存在確認も必須。

読み取る queue の entity type は `DIGITAL_INVOICE` と `DIGITAL_INVOICE_EVENT` のみ。event/queueの一部ページング、先頭N件、MyBatis logic-delete で除外された行の見落とし、並行する第二接続での補完は禁止。query失敗・重複ID・親子不一致・未知entity type/status・soft-delete・tenant/legal entity部分一致・同じ根拠から複数scope候補・相互矛盾・active Peppol callback identity conflictの未解決をいずれも transaction abort とする。

### 4.3 snapshot/approval binding

権威が承認したhashと同一の正規化規則（アルゴリズム、列順、NULL表現、文字コード、ID順、対象行集合を含む）をOwnerが承認する。snapshot取得後に同じ不変値から `aggregateHash` を算出し、承認の invoice、tenant、legal entity、expected version、hash と完全照合する。実DBに存在しないversion列を仮定しない。versionのないevent行は取得した旧値をsnapshotに残して、後続CAS入力の原値比較元にする。hash規則または全行集合を確定できなければ停止する。

同一の `LockedInvoiceAggregateSnapshot` が後続CAS/DML計画への唯一の入力となる。後続処理が再query、異なるsnapshot、呼出者の書換可能リストを使用した場合は契約違反。transaction終了後はsnapshotからDMLを実行できない設計とし、CAS側は同一transaction内の snapshot instance/token を要求する。

## 5. 次の実装で変更を許すファイル

この計画PR/commitで変更可能なのは本ファイルのみ。承認後の実装は別レビューで、少なくとも以下に限定して着手可否を再確認する：

- 新規 `src/main/java/com/ses/service/repair/RepairApprovalAuthority.java` と verified query/value/principal 型（実Authority adapter は Owner/IdP 合意後）。
- 新規 `src/main/java/com/ses/service/repair/RepairWriteDrainGate.java`（信頼source確定後）、`LockedInvoiceAggregateLoader.java`、`LockedInvoiceAggregateSnapshot.java`、帰属根拠 snapshot 型。
- Mapperは必要な最小限の `src/main/java/com/ses/mapper/repair/` 配下に追加し、queryを `FOR UPDATE`/完全集合/決定順で定義する。Entityやmigrationは現行列で不足と証明され、別承認されるまで追加しない。
- 後続契約テストは `src/test/java/com/ses/service/repair/`。実DBロック契約は MySQL Testcontainers の専用テストにし、テスト用approval sourceは本番beanから隔離する。

このバッチでは Java/test/POM/runner/migration/config、V185、production runbook、既存要求/設計/タスクを編集しない。

## 6. 後続テスト契約（本バッチでは NOT RUN）

- 正例: 現round・署名検証済・期限内・資格有効の実合意fixtureと一意なV182根拠から完全snapshotを作り、承認scope/hashと一致。全eventと両種queueを同じtransaction/connectionで網羅し、ID順・行versionを記録する。
- 承認負例: 旧roundのみ/現round不足、署名偽造または欠落、ticket用途・invoice・tenant・legalEntity・version/hashの各不一致、承認者重複、actor/approver SoD違反、資格失効/管轄外、ticket取消/期限切れ、IdP/authority timeout・unknown key・応答曖昧。すべてsnapshot/DML開始前に拒否。
- 根拠負例: `t_invoice`/customer/order/contractのいずれか論理削除または欠落、legal entity不一致、複数tenant候補、契約元間のscope衝突、空tenant、queue/event欠落や余剰、他invoiceのevent混入、unknown status、未解決Peppol conflict。全てfail-closed、DB変更0。
- 完全性/競合: lock順序をconnection traceで検証し、イベント/queue追加・scope変更・version変更を別connectionで競合させる。特にsnapshot読取後にNULL-scope child event、または事前照会で不在だったqueueを別connectionから追加する負例を置く。書込は実際のtrusted drain制御によりblockされるか、snapshot整合性検査で検知して拒絶されなければならない。仮のreadonly boolean/Mock leaseでは合格にしない。承認hashとsnapshotが一つでもずれれば全transaction rollback。snapshotのリスト改変不可、後続CASが再query/別instanceを受け付けない。
- H2のmock testだけでロック証明としない。`@Tag("mysql")` のMySQL 8 Testcontainersで `FOR UPDATE`、集合完全性、lock order、rollback、競合を実証し、専用shard inventoryに一度だけ登録。実施は唯一のテストexecutorが許可状態になった後。

## 7. 人工ゲートと完了条件

**人工Gate:** (1) Production Ownerと業務帰属Ownerの実名/責任範囲、(2)承認authority・署名/失効・資格registry・IdPの正本と可用性SLA、(3)承認者人数/資格/管轄/SoD、(4)invoice所有根拠と複数ソース優先/衝突裁定規程、(5)正規aggregate hashとversionの定義、(6)承認取消とsnapshotの競合直列化、(7)全event/queue writerを含むwrite-drainの信頼source、coverage、transaction中の継続/解除制御、(8)業務queue保全・監査保存責任者、を人間が文書で確定する。未決項目が一つでもあればfail-closedで実装開始不可。

本計画の完了条件は、固定SHA上の単一文書diff、UTF-8 BOMなし、200行以内、diff check pass、日語commit、固定SHA・source差分・未実行試験を記録し、責任者ReviewとAstra PLAN Reviewへ提出すること。Review結果が出るまで実装、DML、SQL、Maven、Docker、Chrome、本番操作、push/PR/merge/deployはしない。

**検証状態:** NOT RUN — Maven/テスト/Dockerは実行していない。Production承認・承認authority・IdP・実DB snapshot/DMLは未実施。
