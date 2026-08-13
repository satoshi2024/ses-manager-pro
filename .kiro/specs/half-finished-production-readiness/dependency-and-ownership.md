# 依存・並行実行・所有権

## 1. Wave

```text
Wave 0: 公式契約/現状 fixture/readiness 固定（production変更なし）
   ├─ HFP-01 freee API spike
   ├─ HFP-02 CloudSign API spike
   └─ HFP-03 backup/restore threat model + isolated fixture

Wave 1: spec別実装（別worktree、別branch）
   ├─ HFP-01 OAuth/company → typed HR client → payroll UI
   ├─ HFP-02 provider client → state/file sync → polling/UI
   └─ HFP-03 backup integrity → dry-run plan → isolated PITR/drill

Wave 2: spec別独立Reviewと修正差分Review

Wave 3: main最新取り込み後の統合回帰・release gate確認
```

Wave 0 の外部契約 gate が未達の spec で、推測 endpoint や sample payload による production adapter 実装を開始してはならない。別 spec の gate は相互に block しない。

## 2. branch / worktree 規則

- spec ごとに `codex/` prefix の独立 branch と独立 worktree を使う。
- branch 作成時に `main` の commit hash、dirty file、既存 worktree を記録する。
- 同一 working tree を複数実装 AI が共有しない。read-only 子 Agent だけは共有可とする。
- 実装 branch へ別 spec の production diff を混在させない。
- Review は `<BASE_COMMIT>..<HEAD_COMMIT>` を固定する。未commit差分への Reviewは中間確認であり最終判定に使わない。
- merge 前に最新 `main` を取り込み、migration 番号、共有 config、security、test schema の競合を再確認する。

## 3. 共有 file の単独所有

| file/領域 | owner | 規則 |
|---|---|---|
| `src/main/resources/db/migration/V1__create_tables.sql` | その branch の主担当1名 | 対象 table/column が V1 に既に定義される場合だけ同期する。post-baseline table を V1 へ逆輸入せず、公開済み増分 migration も編集しない |
| 新規 Flyway version | merge coordinator | 実装開始時に予約せず、実装 branch の最新 main 取り込み後に `latest + 1` を再確認する |
| `src/test/resources/application-test.yml` / H2 schema | DDL task owner | 対象の導入履歴に応じた V1 または forward migration、entity、MySQL smoke と同じ commit で同期する |
| `SecurityConfig` / menu seed | spec主担当 | 既存 method security と dynamic menu filter の両方を consumer test する |
| `AppConfig` / shared `RestTemplate` | spec主担当 | timeout/TLS設定の既存 consumerを inventory し、専用 bean が不要なら増やさない |
| `FreeeIntegrationService*` | HFP-01 | S11/S15 の既存 public method を consumer inventory に含め、対象外機能を壊さない |
| `ContractDocument*` / `CloudSignClient*` | HFP-02 | 契約/PDF/download の既存 consumerと保存pathを壊さない |
| `ops/backup/**` | HFP-03 | production DB を対象とする command は実行せず、隔離環境だけで検証する |
| 各 spec の `tasks.md` / `review-ledger.md` | 各spec主担当 | 子Agentは変更しない。task完了判定を一元化する |
| 本プログラム `execution-ledger.md` | merge coordinator | spec主担当は完了 packet を提出し、coordinatorが転記する |

## 4. Readiness decision gate

### HFP-01 freee

- freee 開発 application、test company、会社管理者 user、必要な application permission がある。
- 公式 Postman または最小 spike で `/api/v1/users/me`、employee list、salary list、bonus list の実 response を確認した。
- response fixture は identifier・氏名・金額・token を不可逆 mask し、公式 schema との field 対応を記録した。
- 認証 URL、token URL、HR API base、API version、pagination、refresh rotation の正を記録した。

不足時は client interface、contract fixture、mock error matrix までは実装可能だが、sandbox E2E task と最終判定は `BLOCKED`。

### HFP-02 CloudSign

- 契約中プランで利用可能な API、sandbox/test account、認証方式、正式 API document/version が確認できる。
- 文書作成、file upload、participant/送信、status、締結済 file、合意締結証明書の取得方法を実 response で確認した。
- provider status と local status の対応表、再送/idempotency 制約、rate limit を固定した。

不足時は既存 mock と公式 fixture による adapter contract test までは可。推測 endpoint の production実装、外部送信 task、最終 `PASS` は不可。

### HFP-03 backup/PITR

- Docker、隔離 MySQL 8、隔離 uploads、test 用 S3 compatible repository または restic local repository がある。
- production と同じ MySQL binlog format/GTID/timezone/retention の値を inventory した。秘密値は記録しない。
- RPO/RTO、backup retention、復元承認者、復元先命名規則、production apply の二者確認方針が決定済み。

production credential や production restore は開発/Reviewの必須条件ではない。隔離 PITR が未実施なら最終 `PASS` は不可。

## 5. merge 順と統合回帰

原則 merge 順は HFP-01 → HFP-02 → HFP-03 とする。HFP-03 が application source に触れず `ops/backup/**` と文書だけなら先行 merge 可。順序変更は `execution-ledger.md` に理由と競合確認を記録する。

各 merge 後に次を確認する。

1. 予約 migration と current latest に衝突がない。
2. `application*.yml` の環境変数名、default、prod validation が競合しない。
3. security/menu/audit/filter の role matrix が弱まっていない。
4. secret、fixture PII、provider response body、DB dump が repository に入っていない。
5. `verify-like-ci` の skipped test が列挙され、CI の zero-skip contract を破っていない。
6. freee/CloudSign 不通、backup repository 不通でも通常の SES 業務が起動・閲覧できる。

## 6. 禁止事項

- 外部 credential 不足を理由に mock 成功を sandbox 成功と記録する。
- API contract を確定するためだけに汎用 framework、大規模 generator、共通 retry 基盤を新設する。
- 既存 token refresh、AES-GCM、PDF generation、SHA-256、atomic file save を根拠なく書き直す。
- provider call を DB transaction 内に保持する。
- 本番 DB 名、host、repository、credential を test command や evidence に貼る。
- `restore.sh` の安全策を確認するために production へ接続する。
- 隣接 S01〜S17 の要件をこのプログラムへ取り込む。
