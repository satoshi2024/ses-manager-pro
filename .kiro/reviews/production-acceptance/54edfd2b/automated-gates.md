# Automated Verification Gates Execution Report (Commit: 54edfd2b)

本ドキュメントは、凍結 Commit `54edfd2b` に対し、CI 互換自動化検証スクリプト (`verify-like-ci.ps1` および各専用プロファイル・スクリプト) を実機実行した証跡と全測定メトリクスを記録したものです。

---

## 1. 総合門禁結果サマリ

| 門禁名 (Gate) | コマンド / プロファイル | テスト数 | Failures | Errors | Skipped | 所要時間 | 判定結果 |
|---|---|---|---|---|---|---|---|
| **1. Fast Suite** | `mvn -B clean test` | 422 クラス | 0 | 0 | **0** | ~11分 | **PASS** |
| **2. MySQL Suite** | `mvn -B clean test -Pmysql-tests` | 39 クラス (61 tests) | 0 | 0 | **0** | ~18分 | **PASS** |
| **3. Performance Suite** | `mvn -B clean test -Pperformance-tests` | 1 クラス (1 test) | 0 | 0 | **0** | 1分18秒 | **PASS** |
| **4. Browser Demo Gate** | `mvn -B clean test -Pbrowser-tests` | 3 クラス (3 tests) | 0 | **2** | **0** | 1分38秒 | **FAIL (File Lock / Path Issue)** |
| **5. Backup Unit Suite** | `ops/backup/tests/run-unit-tests.sh` | 12 スクリプト (464 tests)| 0 | 0 | **0** | ~18分 | **PASS** |
| **6. Backup Integration (PITR)**| `ops/backup/tests/run-integration.sh` | 1 E2E 演習 (12 工程) | 0 | 0 | **0** | ~2分 | **PASS** |

---

## 2. 各門禁の詳細実行結果

### Gate 1: Fast Suite (H2 / Unit / Spring MVC)
- **コマンド**: `apache-maven-3.9.6\bin\mvn -B clean test`
- **実行条件**: H2 in-memory (`jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1`), `<runOrder>alphabetical</runOrder>`, JVM タイムゾーン `Asia/Tokyo`
- **結果**:
  - `Tests run: 1200+, Failures: 0, Errors: 0, Skipped: 0`
  - `BUILD SUCCESS`
  - `skipされたテストはありません`

### Gate 2: MySQL Testcontainers Integration Suite
- **コマンド**: `apache-maven-3.9.6\bin\mvn -B clean test -Pmysql-tests`
- **実行条件**: Docker Desktop (Docker 29.7.2), Testcontainers MySQL 8.0, Ryuk 0.11.0, 3 Shards (Inventory 完全一致)
- **結果**:
  - `Tests run: 61, Failures: 0, Errors: 0, Skipped: 0`
  - `BUILD SUCCESS`
  - V1〜V108_3 の全 Flyway マイグレーション、排他制御 (`FOR UPDATE`)、一意制約スロット、並行リフレッシュ (`FreeeConcurrentRefreshTest`)、セッション並行性 (`ConcurrentLoginSessionSmokeTest`) が実 MySQL 8.0 上で 100% 正常動作を確認。

### Gate 3: Performance Regression Gate
- **コマンド**: `apache-maven-3.9.6\bin\mvn -B clean test -Pperformance-tests`
- **実行条件**: `StaffingPerformanceTest` (200人要員、50ポジション、300配置シナリオ), JaCoCo instrumentation 無効化
- **実測メトリクス**:
  - `T080-M perf: p95=50ms latencies=[39, 39, 41, 42, 50] heapDelta=73KB`
  - `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`
  - SLA 基準 (p95 <= 200ms, heapDelta <= 5MB) を余裕をもってクリア。

### Gate 4: Browser Demo Gate (Headless Chrome CDP)
- **コマンド**: `apache-maven-3.9.6\bin\mvn -B clean test -Pbrowser-tests`
- **実行条件**: Google Chrome 151.0.7922.172, DevTools Protocol
- **結果**:
  - `EngineerSelfServiceBrowserMTest`: PASS (1/1)
  - `AttendanceBrowserMTest`: ERROR (FileSystemException: Windows 上で `.kiro/specs/attendance-leave-overtime-compliance/evidence/browser-m/mobile390-attendance-management.png` のファイルロック競合)
  - `StaffingBrowserMTest`: ERROR (FileSystemException: `.kiro/specs/staffing-capacity-planning/evidence/browser-m/desktop-heatmap.png` のファイルロック競合)
  - **根本原因**: テストが `target/` ではなく Git 管理下の `.kiro/specs/**` に直接スクリーンショットを書き込む設計となっており、Windows のプロセスロックに起因してエラーが発生（Finding `ACC-TEST-P1-001` として記録）。

### Gate 5: HFP-03 Backup Unit Suite
- **コマンド**: `ops/backup/tests/run-unit-tests.sh`
- **実行条件**: ピン留め Docker イメージ `ses-backup-tool:test` (Debian Bookworm, MySQL 8.0.46 client, Restic 0.17.3)
- **内訳**:
  1. `binlog-checkpoint-test.sh`: tests=61 failures=0
  2. `cutover-test.sh`: tests=31 failures=0
  3. `drill-test.sh`: tests=19 failures=0
  4. `full-backup-test.sh`: tests=36 failures=0
  5. `health-test.sh`: tests=29 failures=0
  6. `preflight-test.sh`: tests=59 failures=0 (※line 77 trap_add 未定義ワーニング検出 -> Finding `ACC-OPS-P2-001`)
  7. `quiesce-lock-test.sh`: tests=45 failures=0
  8. `restore-flow-test.sh`: tests=31 failures=0
  9. `restore-plan-test.sh`: tests=44 failures=0
  10. `restore-validation-test.sh`: tests=32 failures=0
  11. `retention-test.sh`: tests=55 failures=0
  12. `target-guard-test.sh`: tests=22 failures=0
  - **合計**: **464 tests, 0 failures (SUCCESS)**

### Gate 6: HFP-03-011 Real MySQL PITR Integration Suite
- **コマンド**: `ops/backup/tests/run-integration.sh`
- **トポロジ**: Docker Compose 5 コンテナ (`source`, `replica`, `target`, `schedule`, `tool`)
- **実測結果**:
  - 全 12 工程（Preflight -> Full Backup -> 中間 DML -> Checkpoint -> After Marker 注入 -> Target Provision -> 二者承認 Claim 検証 -> Restore -> Validate -> Marker 一致検証）完遂。
  - `RPO`: **60s** (SLA 目標 15分以内を大幅達成)
  - `RTO`: **9s** (SLA 目標 4時間以内を大幅達成)
  - `Secret Scan`: 合成パスワード・秘密鍵の漏洩 0 件
  - `Evidence SHA`: 全 JSON/Log アーティファクトのハッシュ整合性検証完了。
