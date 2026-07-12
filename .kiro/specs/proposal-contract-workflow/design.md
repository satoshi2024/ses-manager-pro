# Design Document — 提案・契約ワークフロー(P2)

新規テーブルなし。既存列(`closed_at` / `changed_by` / `proposal_id` / `contract_no`)を使い切る設計。

## 1. 現在ユーザー取得ユーティリティ(共通部品・P8 でも使用)

`common/util/SecurityUtils.java`(新規):

```java
public final class SecurityUtils {
    /** ログイン中ユーザーの sys_user.id を返す(未認証なら null) */
    public static Long currentUserId() { ... }
    public static String currentUsername() { ... }
}
```

実装方式: `CustomUserDetailsService` が返す `UserDetails` を、`SysUser` を保持するカスタム実装
`LoginUser implements UserDetails`(`config/LoginUser.java` 新規)に変更し、
`SecurityContextHolder.getContext().getAuthentication().getPrincipal()` から ID を直接取れるようにする。
(毎回 `selectByUsername` で DB を引かない。既存の認証フローは username/password のまま変更なし。)

## 2. 提案ステータス状態機械

### 2.1 `service/impl/ProposalServiceImpl` の改修

```java
private static final Map<String, Set<String>> ALLOWED = Map.of(
    "書類選考中", Set.of("一次面接", "見送り"),
    "一次面接",   Set.of("二次面接", "結果待ち", "見送り"),
    "二次面接",   Set.of("結果待ち", "見送り"),
    "結果待ち",   Set.of("成約", "見送り"),
    "成約", Set.of(), "見送り", Set.of());

@Override
@Transactional(rollbackFor = Exception.class)
public void changeStatus(Long id, String newStatus) {
    Proposal proposal = getById(id);
    if (proposal == null) throw new BusinessException("提案が見つかりません");
    String old = proposal.getStatus();
    if (!ALLOWED.getOrDefault(old, Set.of()).contains(newStatus)) {
        throw new BusinessException("「" + old + "」から「" + newStatus + "」へは変更できません");
    }
    proposal.setStatus(newStatus);
    if ("成約".equals(newStatus) || "見送り".equals(newStatus)) {
        proposal.setClosedAt(LocalDateTime.now());
    }
    updateById(proposal);
    // 履歴(changed_by = SecurityUtils.currentUserId())
    ...
    // 見送り時の要員ステータス巻き戻し(Requirement 4-AC4)
    if ("見送り".equals(newStatus)) engineerStatusService.releaseIfIdle(proposal.getEngineerId());
}
```

### 2.2 カンバン側(`proposal-kanban.js`)

`changeStatus` の AJAX が `res.code !== 200` の時、SortableJS のカードを元の列へ戻す
(D&D 前の列 ID を `onStart` で保持 → 失敗時 `onEnd` で差し戻し + `Toast` でエラーメッセージ表示)。

## 3. 要員ステータス連動サービス

`service/EngineerStatusService` + `service/impl/EngineerStatusServiceImpl`(新規):

```java
public interface EngineerStatusService {
    void onProposalCreated(Long engineerId);   // Bench → 提案中
    void onContractActive(Long engineerId);    // → 稼動中
    void releaseIfIdle(Long engineerId);       // 他にオープン提案/稼動中契約が無ければ → Bench
}
```

- `releaseIfIdle`: `t_proposal`(status NOT IN 成約/見送り)件数 + `t_contract`(status=稼動中)件数が
  ともに 0 の場合のみ `Bench` へ更新。
- 呼び出し箇所: `ProposalApiController.save`(作成後)/ `ProposalServiceImpl.changeStatus`(見送り)/
  `ContractServiceImpl.saveOrUpdate系`(下記 4)。
- Proposal ↔ Contract 双方から使うため独立サービスにする(循環依存回避)。

## 4. 契約サービスの業務ロジック化

### 4.1 `ContractService` にメソッド追加

```java
public interface ContractService extends IService<Contract> {
    String generateContractNo(LocalDate baseDate);          // C-YYYYMM-NNNN
    void saveWithBusinessRules(Contract contract);          // 採番+検証+要員連動
    void updateWithBusinessRules(Contract contract);        // 検証+要員連動(終了/解約→releaseIfIdle)
    boolean hasActiveContract(Long engineerId);             // Requirement 2-AC3
}
```

### 4.2 採番(`generateContractNo`)

```java
String prefix = "C-" + baseDate.format(DateTimeFormatter.ofPattern("yyyyMM")) + "-";
Long count = count(new LambdaQueryWrapper<Contract>().likeRight(Contract::getContractNo, prefix));
// prefix + String.format("%04d", count + 1)。DuplicateKeyException 時は +1 して最大3回再試行
```

### 4.3 検証(`validate(Contract c)` private)

- `endDate != null && endDate.isBefore(startDate)` → `BusinessException("契約終了日は開始日以降...")`
- `settlementHoursMax < settlementHoursMin` → `BusinessException`
- `sellingPrice < costPrice` → 例外にせず、API レスポンスの `message` に警告文を載せる
  (`ApiResult.success(data, "警告: 粗利がマイナスです")` — `ApiResult` に message 付き success が無ければオーバーロード追加)。

### 4.4 `ContractApiController` の改修

POST/PUT を `saveWithBusinessRules` / `updateWithBusinessRules` 経由に差し替え。
`GET /api/contracts/check-active?engineerId=` を追加(成約前の重複稼動チェック用、Requirement 2-AC3)。

## 5. 成約→契約作成の画面連携

`proposal-kanban.js`:
1. 「成約」列へのドロップ成功後、`GET /api/proposals/{id}`(既存 detail)+ 案件情報から
   契約作成モーダル `#contractCreateModal` を開く(契約画面のモーダルをカンバンページにも配置、または共通フラグメント化)。
2. 事前入力: engineerId / projectId / customerId(案件から)/ sellingPrice = proposedUnitPrice / proposalId / startDate = 案件 start_date。
3. 事前に `check-active` を呼び、稼動中契約があれば SweetAlert2 で「続行しますか?」確認。
4. 保存 = POST `/api/contracts`(4.4 の業務ルート)。

## 6. テスト

- `ProposalServiceImplTest`: 許可遷移/禁止遷移/closed_at 設定/履歴 changed_by(SecurityContext をテスト内でセット)。
- `ContractServiceImplTest`: 採番形式・連番・月替わり、日付逆転エラー、精算上下限逆転エラー。
- `EngineerStatusServiceImplTest`: releaseIfIdle の3分岐(オープン提案あり/稼動中契約あり/どちらも無し)。
- H2 スキーマに不足テーブルがあれば追加。
