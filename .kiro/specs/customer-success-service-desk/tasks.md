# Tasks — カスタマーサクセス・問い合わせ/SLA・顧客ヘルス (NF-02)

> **承認状態**: traceability は CANDIDATE/DISCOVERY。Approved scope / Owner / DecisionId / DG-02 は未確定で、production releaseの承認はない。本ブランチではユーザー指示により統合hardeningと検証を実施するが、承認済みとは記録しない。
>
> **分層test**: 通常TaskはL1〜L3の定向test、Task Mで必要gate全量。skipを成功にしない。
>
> **既定解**: `platform-invariants.md`。時間/scope/状態は `design.md` 決定表。無い論点は推測実装せずspecへ戻す。
>
> **Migration**: 現行mainへ統合するNF-02版はV136。旧featureのV110は履歴衝突用fixtureとしてのみreset/repair検証し、正規migration番号として再利用しない。欠番埋め・公開済み編集禁止。
>
> **先行WIP**: branch `codex/customer-success-service-desk` の旧V110実装は履歴衝突の検証対象。F1〜Mのcheckboxは、現行branchの定向test・MySQL gate・Demo evidenceが揃うまで完了とみなさない。

---

- [x] **0. Discovery / DG-02 提案 / 現行境界inventory**
  - **Objective**: Customer/Contact/Contract/Portal/Notification/BusinessCalendar/Documentの正本を列挙し、DG-02を提案として記録する。重複masterを作らない。
  - **Requirements**: バックログNF-02、CS-R1〜R6、traceability DG-02
  - **実装ガイダンス**: discoveryではproduction codeを変更しない。hardening実装の承認状態は別途保持し、`inventory.md` / requirements / design / review-ledger を現行コードで更新する。
  - **テスト要件**: L0。inventoryのFileReferenceProvider件数が実装数と一致。portal既存inquiryとservice deskが別物と明記。`git diff --check`。
  - **Demo**: DG-02提案表とconsumer inventoryがspecにあり、公式台帳が未APPROVEDであることが報告できる。
  - **Rollback**: specのみ。コード無し。

- [ ] **F1. request/comment/SLA/CSAT/QBR DDL と状態競合**
  - **Objective**: 10系統テーブル、policy version、clock UNIQUE(request,round)、CSAT UNIQUE、append-only event を同期する。
  - **Requirements**: CS-R1, CS-R2.1, CS-R3, CR-03
  - **実装ガイダンス**: V136 Flyway、V1重複ADD禁止、`schema-service-desk-h2.sql`、entity/mapper。`UNIQUE(priority,status)`は使わない。H2 replayにMySQL DDLを足さない。fresh V1→V136、V133→V136、旧NF02 V110 reset/repairを実MySQLで確認する。
  - **テスト要件**: L1〜L3。UNIQUE衝突、fresh/legacy smoke、H2起動。
  - **Demo**: 空DBと既存DBでmigration成功。
  - **Rollback**: 新テーブルDROP（既存業務テーブル非変更）。
  - **WIP注意**: 旧V110草案は正規版ではない。V136の実列・trigger・append-only schemaを確認する。

- [ ] **F2. 状態機械 / SLA calculator / scope**
  - **Objective**: 営業時間・法人休日・timezone・pause営業分数・reopen round不変、DataScope/Portal scopeをserviceに固定する。
  - **Requirements**: CS-R1.4-5, CS-R2, CS-R5.3, CR-02
  - **実装ガイダンス**: `ServiceSlaCalculator` に Clock/ZoneId/休日関数。`LocalDateTime.now()`禁止。create/resume/reopenはtenant/ZoneId/Instant/organization/legalEntity contextを明示する。内部はDataScope（営業に組織を積集合しない）。portalはSQL customer_id。
  - **テスト要件**: 祝日跨ぎ、pause、reopen旧round不変、CAS 409、portal A/B 404、INTERNALがportal DTOに無い。
  - **Demo**: 金曜夕方P2が土日祝を飛ばすこと、WAITING_CUSTOMER中に期限が営業分数だけ延びること。
  - **Rollback**: service feature flag OFF（テーブルは残してAPI 404）。
  - **WIP注意**: 完了判定には休日跨ぎ、pause営業分数、reopen旧round不変、CAS競合を定向testと実MySQLで確認する。

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
  - **WIP注意**: portal添付はDocumentService/FileScopeValidationService経由でCLEANと自社request scopeを再検証する。

- [ ] **B1. SLA scheduler / 通知**
  - **Objective**: ShedLock付き監視、warning/breach/継続のdedupe、NotificationLinks登録。
  - **Requirements**: CS-R2.4-5, CR-06.3
  - **実装ガイダンス**: 既存 `publishToUser`。第二outbox禁止。warning/初回breach/継続breach、受信者不在・配信失敗の永続化とretryをdedupe台帳で管理する。transaction外。
  - **テスト要件**: 二重scheduler、dedupe、reopen後は新roundだけ通知、test profileでcron無効でも直接呼出可。
  - **Demo**: 期限超過1件に通知1件、再実行でも増えない。
  - **Rollback**: scheduler bean条件OFF。

- [ ] **B2. health / renewal連携 / export / 添付完成**
  - **Objective**: 減点ヘルス、missing表示、カレンダー非破壊、CSV scope一致、Document+FileScope添付。
  - **Requirements**: CS-R4, CS-R5, CR-04
  - **実装ガイダンス**: Invoice overdue読取。`renewal_decision` 非WRITE。P0=-30/P1=-15、CSATは直近90日で全traceability/runbook/codeを統一。snapshot overwrite禁止、非空訂正理由、最大version、DB append-only防線。FileReferenceProvider必須。portal添付はDocumentService/FileScopeValidationServiceを再利用しCLEANのみ許可。
  - **テスト要件**: factor内訳、AR missing、カレンダー非破壊、CSV injection、A/B download。
  - **Demo**: ヘルス画面のfactorとカレンダーバッジ。契約状態が変わらない。
  - **Rollback**: カレンダーDTOのhealth欄を空表示。health API OFF。
  - **WIP注意**: snapshotはoverwriteせず、非空理由付きappend-only revisionとし、旧targetMonthはas-of未提供なら拒否する。

- [ ] **M. 統合gate / 390px / 障害 / rollback / Review handoff**
  - **Objective**: 必要gate skip 0、desktop/390px portal Demo、provider/通知障害、runbook、base/head固定。
  - **Requirements**: CS-R6, 完了定義（バックログ§9）
  - **実装ガイダンス**: fast必須。今回のDDL/CAS/SLA/snapshot変更によりmysql gateも必須。Owner/Approved scope/DecisionIdは存在しない限り未確定のまま記録し、実測していないことを確認済みと書かない。
  - **テスト要件**: MessageBundleConsistency、IDOR、scheduler、backup/DROP手順。
  - **Demo**: 起票→SLA→portal返信→解決→CSAT→ヘルス→カレンダーまでのE2E。
  - **Rollback**: runbookのDROP手順とfeature flag。
  - **完了後**: PRは作らず remote Head と本tasks対応表を独立Reviewへ渡す。
