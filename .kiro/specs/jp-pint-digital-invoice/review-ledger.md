# Review Ledger — JP PINTデジタルインボイス

| Task | Req ID | Changed Files | Tests | Demo | Commit | Risk / Notes |
|---|---|---|---|---|---|---|
| T102 (0. Spike) | R1〜R5 | `.kiro/specs/jp-pint-digital-invoice/g5-spike-result.md` | L0 (Docs) | blockerとversionの記録 | (TBD) | Provider sandbox未入手のためB1/B2/MはPASSにしない方針 |
| spec-alignment (pre-T103) | R3,R4,R5 | `design.md`, `tasks.md`, `requirements.md` | — | 指摘4点（UNIQUE/状態機械/有効1件/§5.6保存契約）をdesign/tasksで整合 | (TBD) | Option B採用。実sandboxはB1/B2/M gate |
| compile+test-fix | R2〜R5 | `DigitalInvoiceApiController`, `InboundDigitalInvoiceApiController`, `JpPintRenderer`, `MockFastAccountingProviderImpl`, `DigitalInvoiceServiceImpl`, H2 schema, directed tests | `DigitalInvoiceSendTest,JpPintDigitalInvoiceF2Test,DigitalInvoiceWebhookApiControllerTest,DigitalInvoiceApiControllerTest,DigitalInvoiceServiceTest` **PASS (24)** | mock冪等・cancel→REVOKED+CreditNote再Queue・未検証拒否・webhook署名 | (TBD) | `@ActiveProfiles("test")` 必須。sandbox未取得のまま B1/B2/M を本番PASSにしない |
| T103 (F1. DDL) | R1.1, R1.2, R3.1, R3.2 | `V107*`, `schema-jp-pint-h2.sql`, `engineer-schema-h2.sql`, Entities | L1-L3 directed 含む | 未検証participant拒否、古いeventで巻き戻らない | (TBD) | uk_digital_invoice_send は V107_2 DROP。MySQL smokeは別途 `-Pmysql-tests` |
| T104〜T108 | — | — | — | — | — | 実装コードは存在するが、sandbox未取得のため B1/B2/M は PENDING_SANDBOX |
| T104 (F2. Validator/Renderer) | R2.1, R2.2, R2.3, R2.4 | `CanonicalInvoice.java`, `JpPintValidator.java`, `JpPintRenderer.java`, `JpPintDigitalInvoiceF2Test.java` | L1-L3 | 合計が合わないXMLの送信拒否、XXE発火防止を確認 | (TBD) |  |
| T105 (B1. Provider送信) | R3.1, R3.2, R4.1 | `DigitalInvoiceServiceImpl`, `InvoiceDeliveryDispatcher`, `AccountingIntegrationWorker`, Tests | L2-L3 | 同一invoice再送時の重複エラー、偽造署名のwebhook拒否、PDF fallbackを確認 | (TBD) | P1-1修正済: IntegrationJobServiceを用いた非同期ワーカー・リトライ制御を実装 |
| T106 (A1. UI) | R1.3, R2.3, R4.2 | `customer/list.html`, `customer.js`, `invoice/list.html`, `invoice.js`, `DigitalInvoiceApiController`, Tests | L2-L3 | participant未検証顧客の送信不可、営業のXML本文閲覧不可 (field mask) を確認 | (TBD) |  |
| T107 (B2. 受信review) | R5.1, R5.2, R5.3 | `DigitalInvoiceWebhookApiController`, `DigitalInvoiceServiceImpl`, `InboundDigitalInvoiceApiController`, UI, Tests | L2-L3 | 受信webhookからの重複検知(3系統)、不正XMLの自動reject、レビュー画面での承認・差戻しを確認 | (TBD) |  |
| T108 (M. 回帰/受入) | R5 | N/A | L4 | provider sandbox API取得待ちのため、現状はMockでのテストのみ。本番release gateとして保留 (PENDING_SANDBOX) | (TBD) |  |
