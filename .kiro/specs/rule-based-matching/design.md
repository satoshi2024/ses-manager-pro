# Design Document — ルールベースマッチング(P4)

## 1. プロバイダ切替

`AiMatchingService` interface はそのまま活かし、実装を設定で選択する:

```java
// 既存モック: 明示的に mock 指定 or 未指定時のデフォルト
@Service
@ConditionalOnProperty(name = "ai.provider", havingValue = "mock", matchIfMissing = true)
public class AiMatchingServiceImpl implements AiMatchingService { ... }

// 新規: service/ai/impl/RuleMatchingServiceImpl.java
@Service
@ConditionalOnProperty(name = "ai.provider", havingValue = "rule")
@RequiredArgsConstructor
public class RuleMatchingServiceImpl implements AiMatchingService { ... }
```

`application.yml` の `ai.provider` を `rule` に変更。interface にメソッド追加:

```java
public interface AiMatchingService {
    List<MatchResultDto> findMatchingProjects(Long engineerId);
    List<MatchResultDto> findMatchingEngineers(Long projectId);   // 新規(mock 実装にも固定データで追加)
}
```

## 2. 採点エンジン(`service/ai/MatchScoreCalculator.java`)

純粋関数のユーティリティクラスとして分離(単体テストを書きやすくする):

```java
public final class MatchScoreCalculator {
    /**
     * @param mustSkillIds   案件の必須スキルID
     * @param niceSkillIds   案件の尚可スキルID
     * @param engineerSkillIds 要員の保有スキルID
     * @param priceMin/priceMax 案件単価レンジ(万円, null許容)
     * @param expectedPrice  要員希望単価(万円, null許容)
     * @param projectStart   案件開始予定日(null許容)
     * @param availableDate  要員稼動可能日(null許容)
     */
    public static MatchScore calculate(Set<Long> mustSkillIds, Set<Long> niceSkillIds,
            Set<Long> engineerSkillIds, BigDecimal priceMin, BigDecimal priceMax,
            BigDecimal expectedPrice, LocalDate projectStart, LocalDate availableDate) { ... }
}
// MatchScore: int total, double mustCoverage, List<Long> matchedMust, List<Long> missingMust, ...
```

配点は requirements.md 1-AC1 の通り。null の扱い:
- 必須/尚可スキル未定義 → その項目は満点
- 単価いずれか null → 単価項目は満点の半分(10点)
- 日付いずれか null → 日付項目は満点の半分(5点)

## 3. `RuleMatchingServiceImpl` の処理フロー

`findMatchingProjects(engineerId)`:
1. 要員 + 保有スキルIDセットを取得(`EngineerSkillMapper`)。
2. 「募集中」案件を全件取得し、`ProjectSkillMapper` で対象案件の要求スキルを **1クエリ**で取得
   (`selectList(in(projectId, ids))` → `Map<Long, List<ProjectSkill>>` にグルーピング。N+1 禁止)。
3. 各案件を `MatchScoreCalculator.calculate` で採点。必須充足率 <50% は除外。
4. スコア降順・上位10件を `MatchResultDto` に詰める。`reason` は
   「必須スキル 3/4 充足(不足: AWS)。単価レンジ内。稼動可能日OK」の形式で組み立て(スキル名は `m_skill_tag` を一括取得して解決)。
   `sellingPoints` には充足した上級スキル・経験年数の長いスキルを列挙。
5. `AiLogMapper.insert`(request_type=マッチング, request_params=`{"engineerId":1}`, created_by=`SecurityUtils.currentUserId()`, tokens_used=0, cost_jpy=0)。
6. P3 導入済みなら `notificationService.publish("AI_MATCHING", ...)`(`ObjectProvider` 経由で任意依存にし、P3 未導入でも動くようにする)。

`findMatchingEngineers(projectId)` は対称形(対象 = status IN('Bench','提案中') の要員、要員スキルを1クエリで取得)。

## 4. API / フロントエンド

- `AiApiController`(既存 `/api/ai/matching/{engineerId}`)はそのまま(実装が差し替わる)。
  `GET /api/ai/matching/project/{projectId}` を追加。
- `ai-matching.js`: 結果カードに「この案件に提案」ボタン → 提案作成モーダル(engineerId/projectId/提案単価=案件上限を事前入力、POST `/api/proposals`)。成功後カンバンへの導線リンク。
- `project.js`: 一覧行に「候補要員」ボタン → `#matchingResultModal` に上位10名(スコア・根拠・「提案作成」ボタン)。
- `MatchResultDto` に `engineerId` / `engineerName` / `proposedPrice` を追加(逆方向用。既存フィールドは維持)。

## 5. テスト

- `MatchScoreCalculatorTest`(純粋単体): 満点/必須半分/除外境界(50%)/単価乖離減点/null 系の各ケース。
- `RuleMatchingServiceImplTest`(H2): スキルシード投入 → 上位順位とソート・除外・AIログ記録を検証。
- `application-test.yml` に `ai.provider: rule` を指定するテスト用プロファイル分岐(または `@TestPropertySource`)。既存モックのテストが壊れないことを確認。
