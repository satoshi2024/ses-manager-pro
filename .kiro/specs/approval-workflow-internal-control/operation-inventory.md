# T041(0). G7と対象操作inventory

**状態**: 完了。production codeは変更していない（調査のみ）。

## 1. G7 — 承認金額閾値と承認者

- `decision-log.md` G7: `blocking=no`、状態=未決。
- **本specはdecision-log推奨既定を採用する**: 承認者チェーンは「組織上長→財務/管理者」、金額閾値は
  固定値をコードへ埋め込まず**設定画面で管理**する（既存の`m_system_config`パターン——
  `commission.base-type`/`commission.rate`、`skillsheet.templates`、`renewal.escalation-days`等と同型）。
- 発注者が別の決定を下した場合は、F1着手前に本ファイルと`decision-log.md`のG7行を更新する。
  F1のroute DDL（`m_approval_route`の金額帯カラム）は本既定を前提に設計するため、
  決定が変わった場合はF1着手前の変更として扱い、F1着手後の手戻りにしない。
- 根拠: `m_system_config`は既に金額/率設定の実績パターンであり（`SystemConfigServiceImpl`、
  `service/impl/SalesPerformanceServiceImpl`の`commission.rate`参照など）、新しい設定機構を導入しない
  という`shared-standards.md` §1「既存資産を唯一の正として再利用する」に整合する。

## 2. 対象操作inventory

現endpoint/serviceの調査はproduction codeを読むだけで行い、変更は一切していない。
「現endpoint/service」列の署名は最終承認transactionが1回だけ呼ぶ対象（design.md §3の委譲先）。

| # | 操作 | 現endpoint | 現service（1回だけ呼ぶ対象） | 申請field（diffの中身） | route（対象種別／金額帯の源） | SLA（現状） | 職務分離（現状／新設） | requirements ID |
|---|---|---|---|---|---|---|---|---|
| 1a | 見積提出 | `PUT /api/quotations/{id}/status`（[QuotationApiController.java:176](../../../src/main/java/com/ses/controller/api/QuotationApiController.java)） | `QuotationServiceImpl.changeStatus(id, "提出済")`（[QuotationServiceImpl.java:132](../../../src/main/java/com/ses/service/impl/QuotationServiceImpl.java)）。状態機械`ALLOWED`マップ既存、行lockは`selectByIdForUpdate`（`@Version`なし） | 対象quotationId、遷移先status（"提出済"） | 対象種別=`quotation.submit`。金額帯=`Quotation.unitPrice`（`t_quotation.unit_price`）。組織=customerId/engineerId所属 | 現状なし。新設route stepへ既定値なし設定を推奨（本番運用でF1着手前に閾値を設定画面で入力） | 現状は単独user操作で承認者概念なし（新設で申請者≠承認者を導入） | R1.1, R1.2, R2.2, R4.1 |
| 1b | 受注（提出済→受注、契約draft化） | `PUT /api/quotations/{id}/status` + `POST /api/quotations/{id}/create-draft`（[QuotationApiController.java:183](../../../src/main/java/com/ses/controller/api/QuotationApiController.java)） | `QuotationServiceImpl.changeStatus(id, "受注")`／`createDraftFromQuotation(id)`（[QuotationServiceImpl.java:132,187](../../../src/main/java/com/ses/service/impl/QuotationServiceImpl.java)）。受注は`engineerId`/`projectId`必須 | 対象quotationId、遷移先status（"受注"）、確定済engineerId/projectId | 対象種別=`quotation.accept`。金額帯・組織は1aと同一unitPrice/所属 | 同上 | 同上 | R1.1, R1.2, R2.1, R2.2, R2.4, R4.1 |
| 2a | 契約稼動化（準備中→稼動中） | `PUT /api/contracts/{id}/status`（[ContractApiController.java:204](../../../src/main/java/com/ses/controller/api/ContractApiController.java)） | `ContractServiceImpl.changeStatus(id, "稼動中", null)`（[ContractServiceImpl.java:279](../../../src/main/java/com/ses/service/impl/ContractServiceImpl.java)）。状態機械`ALLOWED_STATUS_TRANSITIONS`既存、`selectByIdForUpdate`（`@Version`なし）。副作用: `EngineerStatusService.onContractActive` | 対象contractId、遷移先status（"稼動中"） | 対象種別=`contract.activate`。金額帯=`Contract.sellingPrice`（`t_contract.selling_price`）。組織=契約の顧客/エンジニア所属 | 現状なし | 現状は単独user操作（新設で申請者≠承認者を導入） | R1.1, R1.2, R2.2, R4.1 |
| 2b | 単価改定 | `POST /api/contracts/{id}/price-revisions`（[ContractApiController.java:267](../../../src/main/java/com/ses/controller/api/ContractApiController.java)） | `ContractServiceImpl.revisePrice(id, applyFromMonth, selling, cost, reason)`（[ContractServiceImpl.java:430](../../../src/main/java/com/ses/service/impl/ContractServiceImpl.java)）。**状態遷移map無し**（contract statusを見ない）。既存の遡及請求/支払再計算（`t_work_record`の未確定行）を内包 | 対象contractId、applyFromMonth、新sellingPrice、新costPrice、reason | 対象種別=`contract.revisePrice`。金額帯=**改定差額**（新旧sellingPriceの差、絶対値）。組織=契約の顧客/エンジニア所属 | 現状なし。**未確定work_recordの遡及書換えを伴うため、他の4操作よりSLA/承認優先度を高める設計余地あり（F1で決定）** | 現状は単独user操作。**現状の`revisePrice`は`Contract.status`を検証しない**ため、承認routeでも対象状態を追加条件にしない設計とする（design §6.2に反映が必要な逸脱候補） | R1.1, R1.2, R2.1, R2.2, R4.1 |
| 3a | 請求送付（未送付→送付済） | `PUT /api/invoices/{id}/status`（[InvoiceApiController.java:129](../../../src/main/java/com/ses/controller/api/InvoiceApiController.java)） | `InvoiceServiceImpl.changeStatus(id, "送付済", paidDate)`（[InvoiceServiceImpl.java:205](../../../src/main/java/com/ses/service/impl/InvoiceServiceImpl.java)）。状態機械`ALLOWED`既存、`checkClosing`→`assertOpenForUpdate`**既存呼び出しあり**、`SELECT...FOR UPDATE`（`@Version`なし） | 対象invoiceId、遷移先status（"送付済"） | 対象種別=`invoice.send`。金額帯=`Invoice.total`（`t_invoice.total`）。組織=請求先customer | 現状なし | 現状は単独user操作＋`assertSalesDataScopeCustomer`（新設で申請者≠承認者を導入、既存scope guardは維持） | R1.1, R1.2, R2.2, R2.4, R4.1 |
| 3b | 請求取消 | `PUT /api/invoices/{id}/void`（[InvoiceApiController.java:136](../../../src/main/java/com/ses/controller/api/InvoiceApiController.java)） | `InvoiceServiceImpl.voidInvoice(id)`（[InvoiceServiceImpl.java:578](../../../src/main/java/com/ses/service/impl/InvoiceServiceImpl.java)）。入金済/入金行ありなら拒否、`checkClosing`**既存呼び出しあり**、`SELECT...FOR UPDATE` | 対象invoiceId | 対象種別=`invoice.void`。金額帯=`Invoice.total`。組織=請求先customer | 現状なし | 同上 | R1.1, R1.2, R2.2, R4.1 |
| 4 | BP支払確定 | `PUT /api/invoices/bp-payments/{id}`（[InvoiceApiController.java:295](../../../src/main/java/com/ses/controller/api/InvoiceApiController.java)） | `InvoiceServiceImpl.changeBpPaymentStatus(id, "支払済", paidDate)`（[InvoiceServiceImpl.java:537](../../../src/main/java/com/ses/service/impl/InvoiceServiceImpl.java)）。**状態機械mapではなくif/else許可リスト**＋status文字列CAS（`@Version`ではない）、`assertOpenForUpdate`**既存呼び出しあり**（WorkRecordのwork_month経由） | 対象bpPaymentId、遷移先status（"支払済"）、paidDate | 対象種別=`bpPayment.confirm`。金額帯=`BpPayment.amount`（`t_bp_payment.amount`）。組織=対象work_recordの契約/BP所属 | 現状なし | 現状は`BpPaymentServiceImpl.assertAllowed`→`WorkRecordService.assertAllowed`のscope guard。**行レベルpessimistic lockが無く、状態CASのみ**（F1のrequest lockと二重にならないよう設計時に確認） | R1.1, R1.2, R2.2, R2.4, R4.1 |
| 5a | 月次締め | `POST /api/monthly-closing/confirm`（[MonthlyClosingApiController.java:24](../../../src/main/java/com/ses/controller/api/MonthlyClosingApiController.java)） | `MonthlyClosingServiceImpl.confirmClosing(month, userId, role)`（[MonthlyClosingServiceImpl.java:219](../../../src/main/java/com/ses/service/impl/MonthlyClosingServiceImpl.java)）。**状態機械ではなくJSON配列への追記**＋`lockConfig()`（`SystemConfig`行のpessimistic lock）、`requireCloserRole`（管理者/マネージャーのみ）、`readyToClose`前提条件 | 対象month（YearMonth） | 対象種別=`closing.confirm`。**金額なし**（design §6.1「金額なし申請」に該当、金額帯routeの対象外） | 現状なし | 現状は`requireCloserRole`（管理者/マネージャーのみ実行可）。**承認者＝実行者と同じroleプールになりうるため、申請者≠承認者ruleの適用方法をF1で確定する必要あり**（例: マネージャーが申請したら管理者のみ承認可、等） | R1.1, R1.2, R2.2, R4.1 |
| 5b | reopen | `POST /api/monthly-closing/reopen`（[MonthlyClosingApiController.java:31](../../../src/main/java/com/ses/controller/api/MonthlyClosingApiController.java)） | `MonthlyClosingServiceImpl.reopenClosing(month, userId, role)`（[MonthlyClosingServiceImpl.java:243](../../../src/main/java/com/ses/service/impl/MonthlyClosingServiceImpl.java)）。同一lock、`error.closing.notClosed`ガード | 対象month（YearMonth） | 対象種別=`closing.reopen`。**金額なし** | 現状なし | 同上 | R1.1, R1.2, R2.2, R4.1 |

## 3. F1/F2実装ガイダンスへの申し送り（決定ではなく観測事実）

- **既存の月次締めロック呼び出しの非対称性**: 3a/3b（請求送付/取消）と4（BP支払確定）は
  `MonthlyClosingService.assertOpenForUpdate`を既に呼んでいるが、1a/1b（見積）と2a/2b（契約）は
  呼んでいない。design.md §6決定表がこの非対称性を「既存仕様として維持する」のか
  「承認engineが一律で締め済み月をrequest受付時に拒否する」のかを明記していない場合、
  F1着手前に決定表へ追記が必要（推測実装しない）。
- **2b（単価改定）はcontract statusを検証しない**唯一の対象操作。route/承認は他の4操作と同じ枠組みで
  扱えるが、「対象version再検証」（design §3/§6.4）でstatus不一致を新たに追加条件にしない設計とする。
- **4（BP支払確定）のみ行レベルpessimistic lockが無い**（状態CASのみ）。F1の
  `request lock → target version再検証 → applyApproved → request approved → outbox insert`の
  「target version再検証」で、`BpPayment`の`status`をCAS条件に含めることで二重適用を防ぐ
  （design §3のUNIQUE(approval_request_id)と合わせた二重防御）。
- **5a/5b（月次締め/reopen）は金額を持たない**。design §6.1の「金額なし申請」ルートへ倒すこと。
  承認者解決（R1.3「申請者の上長、組織責任者、財務責任者」）のうち、現状の`requireCloserRole`
  （管理者/マネージャーのみ実行可）をどうroute定義へ写像するかはF1のroute初期データ投入で確定する。

## 4. Demo（財務/管理者レビュー用の提示内容）

- 上記表を提示し、G7閾値・承認者は「decision-log推奨既定を採用」である旨を明記した（決定済みではない）。
- 対象5業務・7操作（1a/1b/2a/2b/3a/3b/4/5a/5b＝9操作）の現endpoint/serviceが全て特定済みであることを確認。
- 3の申し送り事項（非対称性3件）はF1着手前に発注者/統合担当のレビューが必要な論点として提示する。

## 5. テスト要件（L0）充足の記録

- 対象5業務・9操作の全endpointが表に存在: 満たす（本ファイル §2）。
- 各操作に対応するrequirements IDが付いている: 満たす（本ファイル §2、最終列）。
- `git diff --check`: 本task完了時にexit 0を確認する（review-ledger.mdへ記録）。
