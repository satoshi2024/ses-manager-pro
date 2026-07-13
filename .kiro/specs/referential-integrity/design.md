# Design — 参照整合性ガード + 契約番号採番修正(referential-integrity)

マイグレーション不要。編集対象(これ以外に触れないこと):

- `src/main/java/com/ses/service/impl/EngineerServiceImpl.java`
- `src/main/java/com/ses/service/impl/CustomerServiceImpl.java`
- `src/main/java/com/ses/service/impl/ProjectServiceImpl.java`
- `src/main/java/com/ses/service/impl/ContractServiceImpl.java`
- `src/main/java/com/ses/mapper/ContractMapper.java`(採番用 @Select の**メソッド追加のみ**)
- テスト: `service/impl/` 配下に新規統合テスト、既存 `ContractServiceImplTest` へ追加

## 1. ガードの実装位置と共通パターン(R1〜R4)

各 `*ServiceImpl` で MyBatis-Plus の `removeById(Serializable id)` をオーバーライドし、
削除前に参照チェックを行う。コントローラー(`engineerService.removeById(id)` 等)は無変更で効く。

```java
@Override
@Transactional(rollbackFor = Exception.class)
public boolean removeById(Serializable id) {
    Long engineerId = Long.valueOf(id.toString());
    long active = contractMapper.selectCount(new LambdaQueryWrapper<Contract>()
            .eq(Contract::getEngineerId, engineerId)
            .eq(Contract::getStatus, "稼動中"));
    if (active > 0) {
        throw new BusinessException("稼動中の契約があるため削除できません");
    }
    long openProposals = proposalMapper.selectCount(new LambdaQueryWrapper<Proposal>()
            .eq(Proposal::getEngineerId, engineerId)
            .notIn(Proposal::getStatus, List.of("成約", "見送り")));
    if (openProposals > 0) {
        throw new BusinessException("進行中の提案があるため削除できません");
    }
    return super.removeById(id);
}
```

注意点:
- 現状 `EngineerServiceImpl` / `CustomerServiceImpl` / `ProjectServiceImpl` は空実装。
  コンストラクタ注入(`@RequiredArgsConstructor`)で必要な Mapper を足す。
- MyBatis-Plus の `selectCount` は論理削除行を自動で除外する(=「削除されていない○○が紐づく」判定になる)。
  例外は `t_invoice` を扱う顧客ガードで、こちらも自動フィルタで `deleted_flag=0` のみ数えられるため要件どおり。
- `removeByIds` / `remove(Wrapper)` は本アプリのコントローラーから呼ばれていないため対象外
  (呼ぶ経路を新設する場合は同じガードを通すこと)。

### 各エンティティのチェック内容

| サービス | チェック(いずれかに該当すれば BusinessException) |
|---|---|
| EngineerServiceImpl | ①稼動中契約(t_contract, status=稼動中) ②進行中提案(t_proposal, status NOT IN 成約/見送り) |
| CustomerServiceImpl | ①案件(t_project, customer_id) ②契約(t_contract, customer_id) ③請求書(t_invoice, customer_id) — 件数をメッセージに含める |
| ProjectServiceImpl | ①契約(t_contract, project_id) ②進行中提案(t_proposal, project_id, status NOT IN 成約/見送り) |
| ContractServiceImpl | ①実績あり(t_work_record, contract_id — WorkRecordMapper.selectCount) ②status=稼動中(自レコード) |

`ContractServiceImpl` の稼動中チェックは削除対象自身の取得(`getById`)で判定する:

```java
Contract target = this.getById(id);
if (target == null) return false;               // 既に削除済み: 従来挙動(false)を維持
if ("稼動中".equals(target.getStatus())) {
    throw new BusinessException("稼動中の契約は削除できません。先に終了/解約へ変更してください");
}
```

## 2. 契約番号採番の最大番号方式化(R5)

`ContractMapper` に注釈 @Select を追加(論理削除フィルタを迂回して削除済みの番号も見る):

```java
@Select("SELECT MAX(contract_no) FROM t_contract WHERE contract_no LIKE CONCAT(#{prefix}, '%')")
String selectMaxContractNoIncludingDeleted(@Param("prefix") String prefix);
```

`ContractServiceImpl`:
- `generateContractNo(LocalDate baseDate)`: `selectCount+1` を廃止し、最大番号の末尾4桁+1
  (null なら `prefix + "0001"`)。`InvoiceServiceImpl.generateInvoiceNo` と同じ構造にする。
- `saveWithBusinessRules` の採番リトライループ: `count + 1 + i` 方式をやめ、
  各試行で `generateContractNo` を呼び直す(毎回最新の最大値から再採番。
  `InvoiceServiceImpl.insertWithInvoiceNoRetry` と同じ構造)。`DuplicateKeyException` 捕捉と
  3回リトライ・失敗時 `BusinessException` は現状維持。

## 3. テスト設計

新規 `service/impl/ReferentialIntegrityGuardTest.java`(H2 統合テスト、
`EngineerDeleteIntegrationTest` と同じ `@SpringBootTest` + `@Sql` パターンを踏襲)に
R1〜R4 の拒否/許可ケースをまとめる。採番(R5)は `ContractServiceImplTest` に追加する。
既存 `EngineerDeleteIntegrationTest`(参照なし要員の削除成功)がグリーンのままであることが回帰確認になる。
