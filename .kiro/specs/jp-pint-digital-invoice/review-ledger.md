# Review Ledger — JP PINTデジタルインボイス

| Task | Req ID | Changed Files | Tests | Demo | Commit | Risk / Notes |
|---|---|---|---|---|---|---|
| T102 (0. Spike) | R1〜R5 | `.kiro/specs/jp-pint-digital-invoice/g5-spike-result.md` | L0 (Docs) | blockerとversionの記録 | (TBD) | Provider sandbox未入手のためB1/B2/MはPASSにしない方針 |
| T103 (F1. DDL) | R1.1, R1.2, R3.1, R3.2 | `V107__jp_pint_digital_invoice.sql`, `engineer-schema-h2.sql`, Entities, Services, SmokeTest | L1-L3 | 未検証participantへの送信拒否、非終端・古いeventでステータスが巻き戻らないことを確認 | (TBD) | P1-1修正済: event_atの比較による順序逆転防止を実装 |
| T104 (F2. Validator/Renderer) | R2.1, R2.2, R2.3, R2.4 | `CanonicalInvoice.java`, `JpPintValidator.java`, `JpPintRenderer.java`, `JpPintDigitalInvoiceF2Test.java` | L1-L3 | 合計が合わないXMLの送信拒否、XXE発火防止を確認 | (TBD) |  |
| T105 (B1. Provider送信) | R3.1, R3.2, R4.1 | `DigitalInvoiceServiceImpl`, `InvoiceDeliveryDispatcher`, `AccountingIntegrationWorker`, Tests | L2-L3 | 同一invoice再送時の重複エラー、偽造署名のwebhook拒否、PDF fallbackを確認 | (TBD) | P1-1修正済: IntegrationJobServiceを用いた非同期ワーカー・リトライ制御を実装 |
| T106 (A1. UI) | R1.3, R2.3, R4.2 | `customer/list.html`, `customer.js`, `invoice/list.html`, `invoice.js`, `DigitalInvoiceApiController`, Tests | L2-L3 | participant未検証顧客の送信不可、営業のXML本文閲覧不可 (field mask) を確認 | (TBD) |  |
| T107 (B2. 受信review) | R5.1, R5.2, R5.3 | `DigitalInvoiceWebhookApiController`, `DigitalInvoiceServiceImpl`, `InboundDigitalInvoiceApiController`, UI, Tests | L2-L3 | 受信webhookからの重複検知(3系統)、不正XMLの自動reject、レビュー画面での承認・差戻しを確認 | (TBD) |  |
| T108 (M. 回帰/受入) | R5 | N/A | L4 | provider sandbox API取得待ちのため、現状はMockでのテストのみ。本番release gateとして保留 (PENDING_SANDBOX) | (TBD) |  |
