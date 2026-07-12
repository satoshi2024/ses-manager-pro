# Design Document — 通知センター・ToDo(P3)

## 1. DDL(`sql/004_create_notification.sql`)

通知本体は broadcast(全員宛)で1行、既読はユーザー別の中間テーブルで持つ。

```sql
CREATE TABLE t_notification (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  type        VARCHAR(30)  NOT NULL COMMENT '種別(CONTRACT_END/PROPOSAL_STALE/BENCH_LONG/PROJECT_URGENT/AI_MATCHING)',
  title       VARCHAR(200) NOT NULL COMMENT 'タイトル',
  message     VARCHAR(500)          COMMENT '本文',
  link_url    VARCHAR(300)          COMMENT '関連画面URL',
  dedupe_key  VARCHAR(200) NOT NULL UNIQUE COMMENT '重複防止キー(例 CONTRACT_END:12:2026-08-01)',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  INDEX idx_notification_type (type),
  INDEX idx_notification_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知';

CREATE TABLE t_notification_read (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  notification_id BIGINT NOT NULL,
  user_id         BIGINT NOT NULL,
  read_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_notification_read (notification_id, user_id),
  FOREIGN KEY (notification_id) REFERENCES t_notification(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知既読';

-- ToDo画面のメニュー登録(sort_order は既存メニューの並びに合わせて調整)
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('todo', 'ToDo・通知', '/todo', '/api/notifications', 75);
INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id FROM (SELECT '管理者' role UNION SELECT '営業' UNION SELECT 'HR' UNION SELECT 'マネージャー') r,
     m_menu m WHERE m.menu_key = 'todo';
```

※ H2 テストスキーマにも同等の2テーブルを追加する(ENUM を使っていないのでほぼコピーで可)。

## 2. エンティティ / Mapper / サービス

- `entity/Notification.java`(`@TableName("t_notification")`、deletedFlag なし)
- `entity/NotificationRead.java`
- `NotificationMapper` に未読件数・一覧の注釈 SQL:

```java
@Select("SELECT COUNT(*) FROM t_notification n WHERE NOT EXISTS " +
        "(SELECT 1 FROM t_notification_read r WHERE r.notification_id = n.id AND r.user_id = #{userId})")
long countUnread(Long userId);

@Select("SELECT n.*, (r.id IS NOT NULL) AS is_read FROM t_notification n " +
        "LEFT JOIN t_notification_read r ON r.notification_id = n.id AND r.user_id = #{userId} " +
        "ORDER BY is_read ASC, n.created_at DESC LIMIT #{limit} OFFSET #{offset}")
List<NotificationDto> selectPageForUser(Long userId, int limit, int offset);
```

- `NotificationService`(既存 interface を改修):

```java
public interface NotificationService {
    List<NotificationDto> getRecentNotifications(Long userId);   // 上位10件(未読優先)
    Page<NotificationDto> pageForUser(Long userId, long current, long size, String type, Boolean unreadOnly);
    long unreadCount(Long userId);
    void markRead(Long notificationId, Long userId);   // insert ignore 相当(重複時は無視)
    void markAllRead(Long userId);
    void publish(String type, String title, String message, String linkUrl, String dedupeKey); // 重複キーで冪等
}
```

`publish` は `dedupe_key` の UNIQUE 違反(`DuplicateKeyException`)を握りつぶす方式で冪等化。
`NotificationDto` に `id` / `isRead` / `linkUrl` / `type` を追加(既存の icon/message/date は維持し互換を保つ)。

## 3. 通知生成バッチ

- `SesManagerApplication` に `@EnableScheduling` を追加。
- `service/scheduler/NotificationScheduler.java`(新規):

```java
@Component @RequiredArgsConstructor
public class NotificationScheduler {
    @Scheduled(cron = "0 0 8 * * *")
    public void generateDaily() { generateService.generateAll(); }
}
```

- `service/NotificationGenerateService` + impl: 各ルールを private メソッドに分割。
  - `contractEnding()`: `t_contract`(稼動中, end_date が今日〜+30日)を要員と **1回の JOIN**(注釈 `@Select` で contract+engineer 氏名を同時取得)→ N+1 解消。dedupe_key=`CONTRACT_END:{contractId}:{endDate}`
  - `proposalStale()`: オープン提案で `updated_at <= now-7d`。dedupe_key=`PROPOSAL_STALE:{proposalId}:{updatedAt日付}`
  - `benchLong()`: status=Bench かつ 最新契約 end_date(なければ created_at)が30日超過。dedupe_key=`BENCH_LONG:{engineerId}:{当月}`(月1回)
  - `projectUrgent()`: priority=急募 かつ status=募集中。dedupe_key=`PROJECT_URGENT:{projectId}`
- AIマッチング完了通知は P4 実装時に `publish("AI_MATCHING", ...)` を直接呼ぶ(バッチ対象外)。
- 起動直後にも1回生成したい場合に備え `POST /api/notifications/generate`(管理者のみ、`SecurityConfig` で `/api/notifications/generate` を `hasRole("管理者")`)を用意する。

## 4. API(`NotificationApiController` 改修)

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/notifications` | 上位10件(ヘッダードロップダウン用、互換維持) |
| GET | `/api/notifications/page?current=&size=&type=&unreadOnly=` | ToDo 画面用ページング |
| GET | `/api/notifications/unread-count` | バッジ用 |
| PUT | `/api/notifications/{id}/read` | 既読化 |
| PUT | `/api/notifications/read-all` | 全既読 |
| POST | `/api/notifications/generate` | 手動生成(管理者のみ) |

## 5. フロントエンド

- `common.js` の `SES.notification`: `load()` で一覧 + `unread-count` を取得しバッジ更新。項目クリック → `PUT read` → `link_url` へ遷移。「すべて既読」リンク追加。ポーリング間隔は現行踏襲。
- 新画面 `templates/todo/list.html` + `static/js/modules/todo.js` + `controller/page/TodoPageController`(`GET /todo` → `todo/list`)。種別フィルタ(select)・未読のみ(checkbox)・ページネーション・行クリック遷移。
- `layout/sidebar.html` に `th:if="${allowedMenus.contains('todo')}"` で項目追加。

## 6. テスト

- `NotificationServiceImplTest`(既存を書き換え): publish の冪等性(同一 dedupe_key 2回で1件)、unreadCount、markRead/markAllRead。
- `NotificationGenerateServiceTest`: 各ルールの境界(30日ちょうど/31日、7日未満は対象外など)を H2 で検証。
- `NotificationApiControllerTest`(既存改修): 新エンドポイントのステータス/ApiResult 形式。
