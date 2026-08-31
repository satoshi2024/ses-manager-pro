# Tasks — カスタマーサクセス・問い合わせ/SLA・顧客ヘルス (NF-02)

> **承認状態**: traceability は CANDIDATE/DISCOVERY。Approved scope / Owner / DG-02 が APPROVED になるまで **F1以降のproduction変更は禁止**。本対話は Task 0（inventory/spec）のみ完了可。
>
> **分層test**: 通常TaskはL1〜L3の定向test、Task Mで必要gate全量。skipを成功にしない。
>
> **既定解**: `platform-invariants.md`。時間/scope/状態は `design.md` 決定表。無い論点は推測実装せずspecへ戻す。
>
> **Migration**: APPROVED着手時に merge済み `db/migration` の latest+1。V109残存時は V110。欠番埋め・公開済み編集禁止。
>
> **先行WIP**: branch `codex/customer-success-service-desk` に未承認コード（Head `eb912340`）がある。F1〜MのcheckboxはWIPを完了とみなさない。APPROVED後に `inventory.md` §8 / `design.md` §9 を是正し、定向test+Demoが通ってから `[x]` にする。

---

- [x] **0. Discovery / DG-02 提案 / 現行境界inventory**
  - **Objective**: Customer/Contact/Contract/Portal/Notification/BusinessCalendar/Documentの正本を列挙し、DG-02を提案として記録する。重複masterを作らない。
  - **Requirements**: バックログNF-02、CS-R1〜R6、traceability DG-02
  - **実装ガイダンス**: production codeを変更しない。`inventory.md` / requirements / design / review-ledger を現行コードで更新する。
  - **テスト要件**: L0。inventoryのFileReferenceProvider件数が実装数と一致。portal既存inquiryとservice deskが別物と明記。`git diff --check`。
  - **Demo**: DG-02提案表とconsumer inventoryがspecにあり、公式台帳が未APPROVEDであることが報告できる。
  - **Rollback**: specのみ。コード無し。

- [ ] **F1. request/comment/SLA/CSAT/QBR DDL と状態競合**
  - **Objective**: 10系統テーブル、policy version、clock UNIQUE(request,round)、CSAT UNIQUE、append-only event を同期する。
  - **Requirements**: CS-R1, CS-R2.1, CS-R3, CR-03
  - **実装ガイダンス**: latest+1 Flyway、V1重複ADD禁止、`schema-service-desk-h2.sql`、entity/mapper。`UNIQUE(priority,status)`は使わない。H2 replayにMySQL DDLを足さない。
  - **テスト要件**: L1〜L3。UNIQUE衝突、fresh/legacy smoke、H2起動。
  - **Demo**: 空DBと既存DBでmigration成功。
  - **Rollback**: 新テーブルDROP（既存業務テーブル非変更）。
  - **WIP注意**: V110草案あり。UNIQUEとV1同期を再確認。

- [ ] **F2. 状態機械 / SLA calculator / scope**
  - **Objective**: 営業時間・法人休日・timezone・pause営業分数・reopen round不変、DataScope/Portal scopeをserviceに固定する。
  - **Requirements**: CS-R1.4-5, CS-R2, CS-R5.3, CR-02
  - **実装ガイダンス**: `ServiceSlaCalculator` に Clock/ZoneId/休日関数。`LocalDateTime.now()`禁止。内部はDataScope（営業に組織を積集合しない）。portalはSQL customer_id。
  - **テスト要件**: 祝日跨ぎ、pause、reopen旧round不変、CAS 409、portal A/B 404、INTERNALがportal DTOに無い。
  - **Demo**: 金曜夕方P2が土日祝を飛ばすこと、WAITING_CUSTOMER中に期限が営業分数だけ延びること。
  - **Rollback**: service feature flag OFF（テーブルは残してAPI 404）。
  - **WIP注意**: 現行計算機は土日のみでWIPギャップ。是正してから完了。

- [ ] **A1. 内部 service desk UI/API**
  - **Objective**: 管理者/営業/マネージャーが一覧・詳細・起票・内部メモ/公開返信・状態変更できる。HR/要員は403。
  - **Requirements**: CS-R1, CS-R6, CR-01, CR-05
  - **実装ガイダンス**: page/API、i18n 4bundle、filter panel構造、二重click抑止、CSRF。action keyは機械生成。
  - **テスト要件**: MVC、scope 404、CSRF、HR 403、390px。
  - **Demo**: 内部起票→内部メモ→公開返信→着手。
  - **Rollback**: menu_key削除で画面到達不可。

- [ ] **A2. portal 起票 / 返信 / CSAT**
  - **Objective**: 顧客portalから自社のみ起票・返信・解決後1回CSAT。BP不可。field-inventory C-9を追加。
  - **Requirements**: CS-R1.2, CS-R3.1-2, CS-R5
  - **実装ガイダンス**: 専用DTO。visibilityパラメータをportalが送れない。invoice `portal_inquiry` は触らない。
  - **テスト要件**: IDOR matrix、二重CSAT、INTERNAL除外、390px。
  - **Demo**: portal起票が内部に見え、内部公開返信がportalに見え、INTERNALが見えない。CSAT2回目409。
  - **Rollback**: portal permission未付与でタブ非表示+API 403。
  - **WIP注意**: list.html欠落。添付未配線。

- [ ] **B1. SLA scheduler / 通知**
  - **Objective**: ShedLock付き監視、warning/breach/継続のdedupe、NotificationLinks登録。
  - **Requirements**: CS-R2.4-5, CR-06.3
  - **実装ガイダンス**: 既存 `publishToUser`。第二outbox禁止。宛先0は管理者へ。transaction外。
  - **テスト要件**: 二重scheduler、dedupe、reopen後は新roundだけ通知、test profileでcron無効でも直接呼出可。
  - **Demo**: 期限超過1件に通知1件、再実行でも増えない。
  - **Rollback**: scheduler bean条件OFF。

- [ ] **B2. health / renewal連携 / export / 添付完成**
  - **Objective**: 減点ヘルス、missing表示、カレンダー非破壊、CSV scope一致、Document+FileScope添付。
  - **Requirements**: CS-R4, CS-R5, CR-04
  - **実装ガイダンス**: Invoice overdue読取。`renewal_decision` 非WRITE。snapshot overwrite禁止。FileReferenceProvider必須。
  - **テスト要件**: factor内訳、AR missing、カレンダー非破壊、CSV injection、A/B download。
  - **Demo**: ヘルス画面のfactorとカレンダーバッジ。契約状態が変わらない。
  - **Rollback**: カレンダーDTOのhealth欄を空表示。health API OFF。
  - **WIP注意**: 加点モデル・NEUTRAL/AT_RISK・delete+insert snapshotを是正。

- [ ] **M. 統合gate / 390px / 障害 / rollback / Review handoff**
  - **Objective**: 必要gate skip 0、desktop/390px portal Demo、provider/通知障害、runbook、base/head固定。
  - **Requirements**: CS-R6, 完了定義（バックログ§9）
  - **実装ガイダンス**: fast必須。mysql/performanceは影響範囲に応じて。実測していないことを確認済みと書かない。
  - **テスト要件**: MessageBundleConsistency、IDOR、scheduler、backup/DROP手順。
  - **Demo**: 起票→SLA→portal返信→解決→CSAT→ヘルス→カレンダーまでのE2E。
  - **Rollback**: runbookのDROP手順とfeature flag。
  - **完了後**: PRは作らず remote Head と本tasks対応表を独立Reviewへ渡す。
