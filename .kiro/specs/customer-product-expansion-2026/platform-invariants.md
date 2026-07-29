# プラットフォーム不変条件 v1.0 — 既定解の表

## 0. 本書の位置づけ

本書は**チェックリストではなく「答えの表」**である。`execution-review-handbook.md` §6の横断チェックリストが
「これを考えたか」を問うのに対し、本書は**このシステムでの答えを先に決めておく**。

S02 `organization-management-accounting` は21ラウンドのReviewを要したが、その内容の大半は
新しい業務要件の発見ではなく、**時間モデル・認可母集団・cache・期間代数という、
どのspecでも同じ答えになるべき論点をspecごとに再発見する作業**だった。同じ再発見をS04〜S17で
14回繰り返さないために、S02が到達した結論をここへ固定する。

### 使い方

1. 各specの`design.md`は、本書の既定解を**再掲しない**。
2. 各specの`design.md`は、本書から**逸脱する点だけ**を「逸脱と根拠」として書く。
3. 逸脱を書かない＝本書の既定解を採用した、という意味になる。実装AIは既定解を実装する。
4. 本書に既定解が無い論点を見つけたら、specで勝手に決めず、本書へ追記する提案を出す。
   同じ論点が2つ目のspecで再び出た時点で、それは本書に属する。

### 優先順位

`execution-review-handbook.md` §1の優先順位に従う。本書は**同§1の5番目**（handbook/shared-standards/
dependency/parallel planと同格）に位置し、specの`requirements.md`/`design.md`が本書と矛盾する場合は
spec側が優先する。ただしその矛盾は「逸脱と根拠」として明記すること。黙って違う実装をしない。

---

## 1. 時間・履歴・snapshot

S02で最も高くついた領域。第十三次Reviewで**構造変更（V61/V62の履歴テーブル追加）**が必要になった。

| 論点 | 既定解 |
|---|---|
| 業務日付の型 | `LocalDate`。時刻が要る場合のみ`LocalDateTime`。DBは`DATE`/`DATETIME` |
| timezone | tenant設定を参照する。`Asia/Tokyo`をコードへ直書きしない |
| 有効期間の表現 | `valid_from`（**inclusive**）/ `valid_to`（**inclusive**、NULL=無期限） |
| 期間の重なり判定 | `valid_from <= :d AND (valid_to IS NULL OR valid_to >= :d)` |
| 主データの改定 | 行を分岐させず**同一IDのversion CAS**で更新。履歴は別テーブルへ持つ |
| 過去実績の帰属 | **月次締め時にsnapshotし、以後不変**。現在値を過去日の代用にしない |
| snapshotの訂正 | reopenでは再算定しない（既存snapshot維持）。明示的な訂正権限＋監査理由でのみ変更 |
| asOf解決の実装 | 共通Resolverへ集約する。呼出側で個別にCOALESCEしない |

### 1.1 NULL と 履歴不存在 の区別（最重要）

**`COALESCE`で「履歴行が無い」と「履歴行はあるが値がNULL」を混同してはならない。**

```sql
-- 誤り: 履歴が見つかったのにその版の値が明示NULLだった場合、現在値へ誤fallbackする
COALESCE(h.parent_id, u.parent_id)

-- 正しい: 履歴行の存在で分岐する
CASE WHEN h.id IS NULL THEN u.parent_id ELSE h.parent_id END
```

参照実装: `com.ses.service.security.OrganizationRelationResolver`。同クラスのjavadocに
「履歴が見つかった場合は履歴値、無ければ現在値」をSQL側で解決済みにする理由が書いてある。
新しい履歴テーブルを作る場合は同じ形にする。

必須test: 前日/当日/翌日、月初/月末、同日変更、未来開始、有限終了、**履歴なし**、**履歴ありNULL**、再実行。

### 1.2 期間代数（統合・異動・分割）

区間を操作する処理は、次の全caseをfixture化する。S02の第十三次/第十五次はここで追加検出された。

| case | 期待 |
|---|---|
| 同日開始 | 原地更新。過去へ分割しない |
| 未来開始 | 原地更新。現在の所属を切らない |
| 過去開始 | 開始日で分割し、旧区間を閉じる |
| 有限`valid_to`あり | 統合先へ**元の`valid_to`を保持**して移す |
| 部分重複 | 未被覆区間だけを移す。全面上書きしない |
| 完全重複 | source側を閉じ、target側は変更しない |
| 隣接 | 結合しない（別区間として保持） |
| 空区間 | `valid_from > valid_to`を拒否 |

統合の順序は**source閉鎖→target付替**。逆にすると重複区間が一時的に成立する。

---

## 2. 認可母集団（scope）

### 2.1 ロール別の結合規則

`OrganizationScopeService`のjavadocを唯一の正とする。要旨:

| ロール | 業務データ | 主データ（組織/cost center/予算/snapshot） |
|---|---|---|
| 管理者 | 全件（組織条件を一切付けない） | 全件 |
| マネージャー（部門責任者） | 主所属組織＋子孫 **∩ DataScope**。`manager_user_id`の直属ユーザーは**個人単位で**追加許可 | 主所属組織＋子孫 |
| 営業 / HR / 要員 | **既存role・DataScopeの範囲のまま。組織で追加的に絞らない** | 全件（menu権限で到達可否を制御） |

**営業/HRへ組織scopeを積集合してはならない。** 営業部の営業が技術部所属の要員の契約を担当するのは
通常運用であり、積集合を取ると担当データが0件になる。S02で実際に起きた事故である。

メニュー権限（`m_menu`/`t_role_menu`/`MenuPermissionFilter`）は**独立した認可ゲート**であり、
組織scopeはこれを置換も緩和もしない。組織scopeは**scopeの拡張には決して使わない**。

### 2.2 適用位置

- 条件は**必ずSQLへ渡す**。取得後のJava側filterは禁止。
- 許可集合が空のときは`id = -1`等でDB側0件にする（参照: `OrganizationScopeServiceImpl`）。
  「空集合＝全件」に化ける実装を書かない。
- `hasFullAccess()`が返す空集合は「該当0件」ではなく「組織条件を付けない」を意味する。
  **必ず`hasFullAccess()`を先に評価する。**

### 2.3 consumer inventory（DoD必須）

認可母集団を変える変更は、次のキーワードを`rg`して**全consumerの一覧を作ってから**着手する。
S02の第一〜七次Reviewは、この一覧が無いまま個別endpointを直し続けたことが原因で反復した。

```
list / page / detail / count / options / autocomplete / summary / dashboard /
export / CSV / Excel / PDF / ZIP / download / preview /
notification / scheduler / async / cache / webhook / batch / bulk / reopen / retry
```

### 2.4 通知の例外

宛先指定通知（`recipient_user_id`あり）へ組織条件を**重ねない**。重ねると異動した本人が
自分宛の通知を見られなくなる。業務通知（宛先なし）だけがscope対象。

### 2.5 ファイル

ファイルを保存したら**必ず2箇所へ登録する**。どちらも忘れた場合の既定が危険側に倒れる。

1. `FileReferenceProvider` — 未登録だと`FileCleanupScheduler`が実ファイルを削除する
   （`cleanup-safety-hours`経過後なので、テストでは気づかない）。
2. `FileScopeValidationService` — 未登録だと最終fallthroughが**allow**なので、
   全認証済みユーザーが読めるようになる。

---

## 3. transaction・cache・並行

### 3.1 cache失効

**原則afterCommit。** `ScopeChangeInvalidator.invalidate()`を呼ぶ（自前で
`TransactionSynchronization`を書かない）。

- commit前に失効させると、まだ見えないはずの新scopeでcacheが作られる。
- rollbackでcacheを進めてはならない。rollback callbackはcacheを書き戻さず、後続commitを保持する。
- **2 transactionのcommit/rollback順を入れ替えるtestを必ず持つ**（S02 第十五次で追加検出）。

可視範囲を変える更新は、個別にコードを書かず`ScopeChangeInvalidator`へ集約する。
S02では要員↔担当営業の割当/解除、契約の担当営業変更、アカウント連携、要員の所属組織変更、
ロール変更の5経路すべてが失効漏れだった。

### 3.2 二重実行の防止

次の3つから**必ず1つを選び、design.mdへ明記する**。「どれも書いていない」を許さない。

| 手段 | 使いどころ |
|---|---|
| `version` CAS（楽観ロック） | 属性更新。同一IDの上書き競合 |
| 状態CAS（条件付きUPDATE） | 状態遷移。二重承認・二重確定 |
| DB UNIQUE制約 | 冪等生成。同一sourceから2件作らせない |

外部連携・作成系は`Idempotency-Key`または業務一意キーで再送安全にする。
CAS失敗を握り潰さない（S02 第十二次で検出）。失敗は失敗として返す。

### 3.3 非HTTP経路

- request scopeのserviceをscheduler/asyncから呼ばない（S02でworker例外が発生）。
- ThreadLocalは`finally`で必ず解除し、**thread再利用test**を持つ。
- schedulerは明示的にcontext（tenant/principal）を設定する。暗黙の持込みに依存しない。
- 外部API呼出しはDB transaction**外**。outbox/jobでcommit後に実行する。

### 3.4 部分更新の破壊性

`mybatis-plus.global-config.db-config.update-strategy: not_null`のため、
`@TableField(updateStrategy = FieldStrategy.ALWAYS)`を付けたfieldは
**すべての`updateById(entity)`でSET句に出る**。sparseなpatch объектを渡すと
無関係な列がNULLで上書きされる。

- `updateById(entity)`は**完全なentityを送る経路だけ**。
- 単一列更新は列名を明示した`UpdateWrapper`を使う（参照: `ContractServiceImpl.updateRenewalDecision`）。

---

## 4. Migration

### 4.1 採番

- 新規migrationは**mergeされた状態の`db/migration`を見て**`latest + 1`を取る。
- 版番号の重複はアプリが**起動しない**（`FlywayException: Found more than one migration with version NN`）。
- 衝突したら**後発を上へ**繰り上げる。**前の欠番を埋めない**
  （高い版を適用済みのDBが`FlywayValidateException`で拒否する）。
- **V59は永久欠番。** 補完・再利用しない。
- 適用済みmigrationは**絶対に編集しない**。

### 4.2 5形状の検証（DDL taskのDoD）

| # | 形状 | 確認内容 |
|---|---|---|
| 1 | fresh | 空DBでV1 baseline→latest |
| 2 | legacy | 公開済みlatestの**実形状**→new |
| 3 | partial | index/constraint/columnが存在・不存在・旧定義 |
| 4 | backfill | NULL、孤児、重複、無効user、履歴なし、一部移行済み |
| 5 | repair | 途中失敗後の復旧とchecksum |

参照実装: `FlywayMigrationSmokeTest`（fresh）、`FlywayLegacyV60MigrationSmokeTest`（legacy）、
`FlywayRepairRunbookTest`（repair）、`FlywayV62ClosedHistoryMigrationSmokeTest`（closed history fixture）。

**freshの成功をlegacyの合格とみなさない。** S02では、legacy fixtureが旧indexを削除した状態で
V60が同名indexを無条件`DROP`し、Docker環境で確実に失敗するP0が第四次Reviewで見つかった。
既存indexの再構成は「無ければ追加／別キーなら削除して再作成／正しければno-op」の三分岐で書く。

MySQL固有の落とし穴（S02 第十二次で実測）:
- **STORED生成列＋FKのALTERは既存DBで`ERROR 1215`になる。** 生成列はVIRTUALを検討するか、
  空DBでしか追加できない前提で設計しない。
- MySQL 8に`ADD COLUMN IF NOT EXISTS`は無い。
- 固定checksumをCRLF前提で書かない（Linuxで失敗する）。

### 4.3 H2との同期

新しいspecは、**MySQL migrationをH2 replayリストへ足さない**。MySQL固有DDLがH2で落ちる。
代わりに`sql/schema-<spec>-h2.sql`を作り、`application-test.yml`の`schema-locations`へ追加する
（S02が`schema-organization-accounting-h2.sql`で確立したパターン）。

同じtaskで同期する対象:
1. `V1__create_tables.sql`（統合baseline。ただし増分migrationとの重複ADDを作らない）
2. 増分Flyway
3. `sql/schema-<spec>-h2.sql`
4. `engineer-schema-h2.sql`（要員系に列を足した場合）
5. MySQL smoke assert
6. entity

---

## 5. 金額・CSV・帳票

| 論点 | 既定解 |
|---|---|
| 通貨単位 | 円。DB/API/Javaすべて`BigDecimal`。`double`/`float`禁止 |
| 時間 | **分の整数**で保存し、表示時に時間へ変換 |
| 丸め | 各specのdesign.mdで丸め方向と桁を明記する。既定に頼らない |
| 負数 | 正常値として扱う。予算差・調整・取消で発生する |
| 集計の口径 | 既存serviceを再利用する。同じ指標を2箇所で計算しない |

### 5.1 CSV

- encoding/BOM/header/column/quote/comma/CR/LF/式注入を**往復fixture**で検証する。
- **正常な負数を文字列化しない。** S02では予算差の負数が文字列になった。
- 式注入対策（先頭`=`,`+`,`-`,`@`）と負数を区別する。**parse結果で判定**し、
  先頭文字だけで一律クォートしない。
- 数値として出す列と式として出す列をdesign.mdで分類する。

### 5.2 export/PDF/download

画面/APIと**同じscope**を通す。別経路で母集団を作り直さない。
大量exportはstreamingにする（全件を`List`/`byte[]`でmemoryに作らない）。

---

## 6. UI / i18n

- 4バンドル（`messages` / `_en` / `_zh_CN` / `_ko`）へ同じキー集合を追加する。
- **`messages_ja.properties`を作らない。** baseがJapaneseであり、`messages_ja`を作ると
  Thymeleaf `#{...}`（server側）と`SES.i18n.t()`（client側）で**別の文字列**が出る。
- 日本語値を他バンドルへコピーして`MessageBundleConsistencyTest`を通さない。翻訳する。
- 外部host（CDN/web font）を参照しない。`static/lib/`のvendored assetを`/lib/...`で参照する。
- 一覧画面は`.card > .card-body > form#searchForm`構造を維持する（`SES.filterPanel`が依存）。
- desktop / 390px の両方をDemoする。状態・金額・警告を**色だけ**で表現しない。
- 一覧APIは`PageUtils.safePage`を使う。手書き`LIMIT/OFFSET`は自前でsize正規化する。

---

## 7. 外部連携

| 論点 | 既定解 |
|---|---|
| 呼出位置 | DB transaction外。outbox/jobでcommit後 |
| 冪等 | payload hash + idempotency key。再実行で外部1件 |
| 相関 | correlation ID / provider request IDを保存 |
| 401 | token refresh **1回**。無限refreshしない |
| 429 / 5xx / timeout | exponential backoff + jitter、max attempts |
| 4xx validation | **retryしない**。人手修正待ちにする |
| 秘密情報 | token/key/口座/TOTP secretをログへ出さない。**log capture testを書く** |
| 応答保存 | PII込みのraw bodyを保存しない。hash / safe summaryのみ |

optional dependencyが欠落したときのfail-open / fail-closedを**明記する**。
ファイル・認証・法定帳票は原則fail-closed。

---

## 8. 各design.mdが必ず持つ3つの決定表

S02の14分類の原因のうち8つは、この3表があれば実装前に検出できた。
S04〜S17の`design.md`は次の3節を持つ。既定解どおりなら「本書§Xに従う」と書けばよく、
**逸脱と、この specに固有の行だけ**を埋める。

### 表1: 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|

### 表2: 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|

（主体は最低限: 管理者 / マネージャー / 営業 / HR / 要員 / portal user / scheduler principal）

### 表3: 状態機械 と 競合

| 状態 | 許可遷移 | 遷移の防重手段 | competing writer | rollback |
|---|---|---|---|---|

---

## 9. 逸脱の書き方

```text
## 逸脱: <本書の§番号と論点>
- 既定解:
- 本specの解:
- 根拠（requirements ID / 業務上の理由）:
- 影響するconsumer:
- 追加で必要なtest:
```

逸脱は禁止ではない。**黙って違う実装をすることだけが禁止**である。

---

## 10. 本書の更新

- 本書を変えるときは、変更理由と発見元（spec / Review round / issue ID）を併記する。
- 同じ論点が2つのspecで独立に問題化したら、それは本書へ昇格させる。
- 本書へ追記した内容は、既に実装済みのspecへ**遡及適用しない**。
  既存の判定は`spec-execution-ledger.md`を正とする。
