# Design — 請求・実績データ整合性(billing-integrity)

マイグレーションは不要(既存スキーマのまま)。編集対象は以下に限定する(他WSとの並行実施のため):

- `src/main/java/com/ses/service/WorkRecordService.java` / `service/impl/WorkRecordServiceImpl.java`
- `src/main/java/com/ses/controller/api/WorkRecordApiController.java`
- `src/main/java/com/ses/dto/workrecord/WorkRecordSaveRequest.java`(新規)
- `src/main/java/com/ses/service/InvoiceService.java` / `service/impl/InvoiceServiceImpl.java`
- `src/main/java/com/ses/controller/api/InvoiceApiController.java`
- `src/main/java/com/ses/mapper/InvoiceMapper.java` / `mapper/InvoiceItemMapper.java` / `mapper/BpPaymentMapper.java`
- `src/main/resources/static/js/modules/invoice.js` / `work-record.js`
- テスト: `WorkRecordServiceImplTest` / `InvoiceServiceImplTest` / `InvoiceApiControllerTest` / `WorkRecordMapperTest`

## 1. reopen ガード(R1, R2)

`WorkRecordServiceImpl.reopenMonth(String workMonth)` を以下の順に再構成する:

```
1. 対象月の確定実績 records を取得(現状どおり)
2. ids = records の id リスト。空なら return
3. [R2] 請求済みチェック:
   InvoiceItemMapper に @Select を追加:
     selectActiveInvoiceNosByWorkRecordIds(List<Long> ids)
     → SELECT DISTINCT i.invoice_no FROM t_invoice_item it
        JOIN t_invoice i ON it.invoice_id = i.id AND i.deleted_flag = 0
        WHERE it.work_record_id IN (...)
     (IN 句は <script> + <foreach> を使う)
   結果が非空なら BusinessException("請求書(" + String.join(", ", nos) + ")に計上済みの実績が含まれるため解除できません")
4. [R1] 支払済チェック:
   bpPaymentMapper.selectCount(QueryWrapper: work_record_id IN ids AND status = '支払済')
   > 0 なら BusinessException("支払済のBP支払が" + n + "件あるため解除できません")
5. ステータス回退(現状どおり)
6. BP支払削除は status = '未払' 条件を追加:
   bpPaymentMapper.delete(new QueryWrapper<BpPayment>().in("work_record_id", ids).eq("status", "未払"))
```

チェック(3,4)が更新(5,6)より**必ず先**。`@Transactional` は既存のまま。

## 2. saveHours の請求済みガード(R3)

`saveHours` の確定チェックの直後に、既存レコードがある場合のみ:

```java
if (record != null) {
    List<String> nos = invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(List.of(record.getId()));
    if (!nos.isEmpty()) {
        throw new BusinessException("請求書(" + nos.get(0) + ")に計上済みの実績は編集できません");
    }
}
```

(1章で追加した @Select を再利用。`WorkRecordServiceImpl` に `InvoiceItemMapper` を注入する。)

## 3. 請求書取消(R4)

`InvoiceService` に `void voidInvoice(Long id)` を追加。実装(`@Transactional(rollbackFor = Exception.class)`):

```
1. invoice = getById(id) … null なら BusinessException("請求書が見つかりません")
2. "入金済".equals(invoice.getStatus()) なら BusinessException("入金済の請求書は取消できません。先に入金を取り消してください")
3. invoiceItemMapper.delete(new QueryWrapper<InvoiceItem>().eq("invoice_id", id))  // 物理削除(実績解放)
4. this.removeById(id)  // 論理削除(deleted_flag=1、番号履歴保持)
```

`InvoiceApiController` に `@PutMapping("/{id}/void")` を追加し委譲する。
※ `t_invoice_item` は `deleted_flag` を持たないため MyBatis-Plus の delete は物理削除になる(意図どおり)。

`selectUnbilledWorkRecords`(InvoiceMapper)の `NOT IN` サブクエリは、防御のため
取消済み請求書を除外する形に書き換える:

```sql
AND w.id NOT IN (
    SELECT it.work_record_id FROM t_invoice_item it
    JOIN t_invoice i ON it.invoice_id = i.id AND i.deleted_flag = 0
)
```

(R4 で明細は物理削除されるので通常は残らないが、手動データ操作等への保険。)

### フロントエンド(invoice.js)

一覧の各行(`未送付`/`送付済` のみ)に「取消」ボタンを追加。クリックで
`Swal.fire`(警告: 「請求書 {invoiceNo} を取消しますか？対象実績は再請求可能になります」)→
確認後 `PUT /api/invoices/{id}/void` → 成功 Toast + `loadInvoices()` 再実行。
既存の fetch パターン(CSRF ヘッダー付与)を踏襲する。

## 4. 採番の論理削除対応(R5)

`InvoiceMapper` に注釈 @Select を追加(論理削除フィルタを迂回するのが目的なので生SQL必須):

```java
@Select("SELECT MAX(invoice_no) FROM t_invoice WHERE invoice_no LIKE CONCAT(#{prefix}, '%')")
String selectMaxInvoiceNoIncludingDeleted(@Param("prefix") String prefix);
```

`generateInvoiceNo` はこれを使い、null なら `prefix + "0001"`、あれば末尾4桁+1。
`insertWithInvoiceNoRetry` のリトライ構造は現状維持(マルチインスタンス競合対策として引き続き有効)。

## 5. 請求書ステータス状態機械(R6)

`InvoiceServiceImpl` に許可遷移表を定義(`ProposalServiceImpl.ALLOWED` と同じ Map<String, Set<String>> パターン):

```java
private static final Map<String, Set<String>> ALLOWED = Map.of(
    "未送付", Set.of("送付済"),
    "送付済", Set.of("入金済", "未送付"),
    "入金済", Set.of("送付済")
);
```

`changeStatus(Long id, String status, LocalDate paidDate)` を書き換え:

```
1. invoice 取得(null チェックは現状どおり)
2. ALLOWED.getOrDefault(old, Set.of()).contains(new) でなければ
   BusinessException("「" + old + "」から「" + new + "」へは変更できません")
3. "入金済" へ: paidDate == null なら BusinessException("入金日を指定してください")、設定する
4. "入金済" から離れる遷移: invoice.setPaidDate(null)
   ※ MyBatis-Plus の update-strategy: not_null は null を無視するため、paid_date のクリアは
   UpdateWrapper で明示的に行う:
   update(new UpdateWrapper<Invoice>().eq("id", id).set("status", newStatus).set("paid_date", null))
5. それ以外は updateById(現状どおり)
```

## 6. BP支払ステータスのサービス化(R7)

`InvoiceService` に `void changeBpPaymentStatus(Long id, String status, LocalDate paidDate)` を追加:

```
1. bpPayment = bpPaymentMapper.selectById(id) … null なら BusinessException("BP支払が見つかりません")
2. "支払済": status/paid_date(未指定なら LocalDate.now()) を設定して updateById
3. "未払": UpdateWrapper で status='未払', paid_date=null を明示 SET(not_null 戦略の迂回)
4. それ以外の値: BusinessException("不正なステータスです: " + status)
```

`InvoiceApiController.updateBpPaymentStatus` は委譲のみに簡素化。`BpPaymentMapper` の注入は不要になれば除去。

## 7. saveHours の DTO 化と競合処理(R8)

新規 `dto/workrecord/WorkRecordSaveRequest.java`:

```java
@Data
public class WorkRecordSaveRequest {
    @NotNull(message = "契約IDは必須です")
    private Long contractId;
    @NotBlank(message = "対象月は必須です")
    @Pattern(regexp = "\\d{4}-\\d{2}", message = "対象月はYYYY-MM形式で指定してください")
    private String workMonth;
    @NotNull(message = "実績時間は必須です")
    @DecimalMin(value = "0", message = "実績時間は0以上を指定してください")
    @DecimalMax(value = "999.9", message = "実績時間は999.9以下を指定してください")
    private BigDecimal actualHours;
    @Size(max = 500, message = "備考は500文字以内で入力してください")
    private String remarks;
}
```

`WorkRecordApiController.saveHours` は `@Valid @RequestBody WorkRecordSaveRequest` で受けて委譲。
`WorkRecordServiceImpl.saveHours` の `this.saveOrUpdate(record)` を try-catch で包み、
`DuplicateKeyException` を `BusinessException("他のユーザーが同じ実績を登録しました。再読み込みしてください")` に変換する。

## 8. テスト設計

H2(`application-test.yml`)前提。既存の `InvoiceServiceImplTest` / `WorkRecordServiceImplTest` の
テストデータ構築ヘルパーを流用する。追加すべきケースは tasks.md の各タスクに記載。
