# SES Manager Pro 結合テスト 実行ゲート・工数・体制計画

本書は、固定 4 週間の希望日程ではなく、frozen inventory、M-PASS、論理ケース、実行 instance、性能・security、欠陥再試験から工数と完了予測を算出する計画である。開始前に日付だけを固定し、未完成機能や欠陥修正時間を 0 とみなすことを禁止する。

## 1. Review 判定と schedule の基本原則

- Review 前版の「20 営業日」「全 36 画面」「400+ ケース」「JaCoCo 95%」には、frozen 分母、ケース ID、実測生産性、強制 build gate がなかったため、commitment として撤回する。
- 現行 tree の画面数は数え方だけでも変わる。候補 build から page route、API operation、template、action permission を生成し、その SHA-256 を分母の正本とする。36 を先に置かない。
- S10 は `IN PROGRESS`、S11 は `PASS`、S12〜S17 は `NOT READY` である。full-plan の calendar は S10〜S17 の必要な M-PASS が揃うまで開始できない。実装待ち時間と実装修正工数は QA 実行工数の外側に明示する。
- 300 人は MySQL 上のデータ母集団であり、300 concurrent browser ではない。機能 E2E、API load、同一行競合を別 run、別証跡、別合格基準で扱う。
- current scope と future scope を分離して報告するが、future を削って full-plan 完了とはしない。

## 2. 分母と進捗指標

### 2.1 論理ケースと実行 instance

| 記号 | 意味 |
|---|---|
| `N_ITA` | `module-test-matrix.md` の重複しない論理 ID。固定値を手入力せず機械集計する |
| `N_ITB_CURRENT / FULL` | current 24 / full 36 ID |
| `N_E2E_CURRENT / FULL` | current 12 / full 21 ID |
| `I_*` | 論理 ID × actor/role × browser × data partition × adapter ×境界値× concurrency profile の実行 instance |
| `D_CURRENT` | current scope の frozen requirement/route/action/transition/validation/write/external inventory 総数 |
| `D_FUTURE` | M-PASS 待ち inventory 総数。`BLOCKED` のまま分母に残す |
| `D_FULL` | `D_CURRENT + D_FUTURE` |

件数は論理ケース、実行 instance、assertion を別々に報告する。同じ操作を 300 actor で回した場合は 1 論理ケース、300 instance であり、「300 ケース」とは数えない。

### 2.2 3 つの coverage を混同しない

各 inventory 種別と current/full scope について、次を別々に算出する。

- `設計 coverage = test ID に mapping 済み inventory / frozen inventory 分母`
- `実行 coverage = (PASS + FAIL) inventory / frozen inventory 分母`
- `合格率 = PASS inventory / (PASS + FAIL) inventory`

`BLOCKED` と `NOT_RUN` は frozen 分母に残す。合格率の分母からは式どおり外れるが、実行 coverage と件数表示から隠せない。Current completion は `D_CURRENT` に対する 3 指標が全て 100%、Full-plan completion は `D_FULL` に対する 3 指標が全て 100% の場合だけ宣言する。future gate を `N/A` に置換して分母を縮める場合は、scope change request と Product/QA/開発責任者の承認が必要である。

## 3. M-PASS と Entry Criteria

### 3.1 モジュール単位 M-PASS

ITb は両端モジュール、E2E は全参加モジュールが次を満たしてから実行する。

1. 対象 spec の実装 task と独立 review が完了し、中央 execution ledger が `PASS`。
2. 候補 build SHA から生成した page/API/template/menu/action/state/table inventory と設計契約が一致。
3. MySQL 8 の空 DB migration smoke、H2 curated schema を使う test、JS syntax、対象 module test が全て skip 0 で合格。
4. 正常、主要拒否、transaction rollback、permission/data scope の module smoke が合格し、P0/P1 が 0。
5. 外部 adapter は version、固定 success、timeout、4xx/429/5xx、reset、secret 管理を持つ。

M-PASS 前のケースは `BLOCKED(M-PASS)` とし、実行済み/PASS に数えない。部分実装を使った exploratory run は別 ledger に保存し、release evidence へ昇格させない。

### 3.2 全体 Entry Criteria

| Gate | 必須 evidence |
|---|---|
| Candidate freeze | clean worktree の commit SHA、branch、build artifact checksum、feature flag/adapter、JDK/Node/browser/Docker、Tomcat/Hikari/heap 設定 |
| Inventory freeze | normalized page/API route、template、menu/action、state transition、validation、write path、table/migration の一覧、件数、SHA-256 |
| CI baseline | `scripts/verify-like-ci.ps1` 相当の clean run が skip 0。Docker が必要な MySQL/Testcontainers と Node check を実際に実行 |
| DB/seed | MySQL 8、Flyway history/checksum、`V100` checksum。既存 `admin` を含む 300、role 別件数、**297 active login 成功 / disabled 3 拒否**を seed validator で確認 |
| Isolation/reset | `E2E-BASE-300` snapshot、ケース DB clone/破棄、mock/mailbox/storage/cache reset の rehearsal 合格 |
| Fixture/oracle | `TEST_MONTH=2026-07`、`AS_OF=2026-08-17T09:00:00+09:00`、自然キー、期待金額、owner、adapter response を持つ manifest |
| Evidence pipeline | case ID ごとの request/response、DB before/after、screenshot/download、log、metrics を build SHA に結び付けて保存できる |
| Observability | correlation ID、HTTP status/latency、JVM/GC、Tomcat queue、Hikari pool、DB CPU/lock/deadlock/slow query、外部 stub call を同一時刻軸で追跡可能 |
| Defect baseline | open defect の severity/owner/再現 ID が確定し、P0/P1 0。既知 P2 は scope と waiver を明示 |

`V100` は `migration-dev` の MySQL seed であり、H2 適用を Entry 条件にしない。disabled アカウントをログイン失敗として正しく検証する。

## 4. 工数積上げモデル

### 4.1 単価は pilot で校正する

最初の 10 ケースは、一覧、更新 transaction、権限、file/external、concurrency を含む代表 pilot とし、設計補完、automation、実行、証跡、review の実測時間を記録する。下表は初期見積単価であり、pilot の中央値が ±15% を超えて違う場合は全 forecast を再計算する。

| 作業クラス | 初期単価 |
|---|---:|
| API/DB 中心 ITa 論理ケースの補完・automation・初回証跡 | 1.25 h / ID |
| UI/状態遷移/role matrix を含む ITa | 2.00 h / ID |
| file/external/concurrency を含む ITa | 3.00 h / ID |
| ITb | 2.50 h × 36 = 90 h（full-plan） |
| E2E | 4.00 h × 21 = 84 h（full-plan） |
| Security matrix 実行・review | 24 h の harness + 0.08 h × `I_SEC` cell |
| 性能試験 | 32 h の script/telemetry + 8 h × 7 profile = 88 h |
| M-PASS 受入 smoke | 4 h ×参加モジュール数 |
| Inventory/traceability freeze | 40 h |
| MySQL/seed/reset/mock/evidence/observability 基盤 | 72 h |
| 最終 reconciliation/report/sign-off | 40 h |

欠陥調査、修正待ち、再試験には、上記の可変実行工数（ITa/ITb/E2E/security cell/performance profile）の **30% を初期 buffer** として積む。これは余れば削る予備日ではなく、実績 defect arrival/closure rate で毎日再予測する。開発者の修正工数と M-PASS 待ちは別に加算する。

### 4.2 計算式

```text
H_ITA   = Σ(クラス別 ITa ID 数 × pilot 校正単価)
H_VAR   = H_ITA + 2.50×N_ITB + 4.00×N_E2E + 0.08×I_SEC + 8×N_PERF
H_FIXED = 40 + 72 + 4×N_MODULE + 24 + 32 + 40
H_BUFFER = 0.30×H_VAR
H_TOTAL = H_FIXED + H_VAR + H_BUFFER
営業日 = ceil(H_TOTAL / (参加 test FTE × 1日実効6h))
```

例として、full-plan で `N_ITA=150`、平均 1.50 h、`I_SEC=600`、15 module、7 profile と仮置きした場合だけを計算すると、`H_FIXED=268h`、`H_VAR=503h`、buffer 約 151h、計 **約 922h** となる。後述の 5.0 test FTE（30 実効 h/日）では **約 31 営業日**であり、M-PASS 待ちと開発修正を含まない。この例は commitment ではないが、根拠のない 20 営業日では収まらないことを示す。実際の forecast は frozen `N_ITA/I_SEC` と pilot 実測値で置き換える。

## 5. 体制と実効 capacity

| Role | FTE | 責務 |
|---|---:|---|
| QA lead | 0.5 | scope/inventory freeze、gate 判定、defect triage、sign-off |
| SDET | 1.0 | API/UI automation、fixture/reset、evidence pipeline、CI |
| QA engineer | 3.0 | ITa/ITb/E2E、DB oracle、exploratory、再試験 |
| Performance/Security specialist | 0.5 | load model、telemetry、role/scope/IDOR/MFA/CSRF/file 検証 |
| Developer support | 1.5 以上（別 capacity） | failpoint、原因調査、修正、unit/integration 回帰 |
| DBA/Infra | 0.25（別 capacity） | MySQL clone、監視、負荷環境、backup/restore |
| Product/業務責任者 | 0.25（別 capacity） | 金額・法令・scope oracle、P2 waiver、最終受入 |

test FTE の日次 capacity は会議・triage・環境待ちを除いた 6 h/FTE で計算し、8 h を全て実行時間として扱わない。特定の 1 名しか mock、DB、帳票を扱えない場合は、その skill bottleneck で critical path を再計算する。

## 6. 実行 wave と依存関係

絶対日付は全 M-PASS と pilot 校正後に付ける。各 wave の期間は `ceil(工数 / 当該 wave へ割当てた実効 h/日)` で計算する。

| Wave | 開始条件 | 作業・工数 | Exit |
|---|---|---|---|
| W0 Readiness | 開発中 | S10〜S17 M-PASS、P0/P1 解消。QA calendar 外 | module ごとの M-PASS evidence |
| W1 Freeze/Pilot | Entry gate 合格 | inventory/trace 40h、環境基盤 72h、代表 10 ケースで単価校正 | 分母、SHA、単価、forecast を承認 |
| W2 ITa | 各 module M-PASS | `H_ITA`。ready module から開始可 | module inventory の設計/実行/合格率 100% |
| W3 ITb | 両端 module の W2 合格 | current 24 ID、M-PASS 後に future 12 ID。full 90h | current/full の status を分離、対象 ID 全 PASS |
| W4 E2E | 参加する全 module/ITb 合格 | current 12 ID、M-PASS 後に future 9 ID。full 84h | current/full の status を分離、対象 ID 全 PASS |
| W5 Security | frozen route/action/owner fixture | harness 24h + cell 工数。W2 と一部並行可 | security gate 合格、P0/P1 0 |
| W6 Performance | 機能 smoke 合格、専用環境 | harness 32h + 7 profile×8h。機能実行と環境を分離 | profile 別基準、DB 整合、capacity report 合格 |
| W7 Defect/Regression | 修正 build ごと | 30% 初期 buffer を burn-down。影響 module→ITb→E2E→security/perf の順 | reopen 0、flake 未解決 0 |
| W8 Report | 全 gate 合格 | reconciliation/report 40h | evidence index、coverage、defect、残 risk の sign-off |

ITb と E2E を 2 日へ押し込まず、上流 M-PASS と defect closure を依存関係にする。性能 run は機能 run と同じ DB/host で同時に実行せず、観測ノイズを混ぜない。

## 7. 性能・同時実行計画

### 7.1 300 人母集団と load profile

負荷は k6、JMeter、Gatling 等の HTTP load runner で実行する。Playwright/browser 300 process は DOM/E2E smoke には使えても、server capacity の load generator として使用しない。ブラウザ証跡は各 profile の代表 1〜5 session に限定する。

| Profile ID | 目的 | 負荷形状 |
|---|---|---|
| `PERF-01-BASE` | 単一 user baseline | 1 VU、10 分、warm-up 後計測 |
| `PERF-02-25` | 小規模 step | 25 VU、5 分 ramp、15 分 hold |
| `PERF-03-50` | 通常 step | 50 VU、5 分 ramp、20 分 hold |
| `PERF-04-100` | release peak | 100 VU、10 分 ramp、30 分 hold |
| `PERF-05-300-STRESS` | capacity/回復点探索 | 0→300 session を 10 分 ramp、10 分 hold、10 分 recovery。release SLO とは分離 |
| `PERF-06-WRITE-254` | active 要員一斉提出相当 | disabled `member200` を除く 254 要員の一意 transaction を 5 分に分散（約 51/分）、最大 60 VU |
| `PERF-07-SOAK` | leak/pool 枯渇 | 50 VU、2 時間 + 10 分 recovery |

read profile の標準 mix は list/search 45%、detail 20%、timesheet read/save 20%、proposal 10%、invoice/帳票 metadata 5%、think time 2〜8 秒とし、role ごとの許可 operation だけを送る。login ramp と authenticated steady-state を別区間で計測する。同じ record の競合は性能 mix に混ぜず、versioned resource の barrier test として別に行う。

### 7.2 測定と暫定 gate

全 profile で p50/p95/p99、throughput、status/error、Tomcat queue/thread、Hikari active/pending/timeout、JVM heap/GC/CPU、DB CPU/connection/lock/deadlock/slow query、外部 stub latency、最終 DB 件数/checksum を保存する。

候補 build freeze 時に Product/SRE が承認するまでは、次を暫定 gate とする。

- `PERF-02`〜`04`: 標準 JSON GET p95 `≤ max(1.0s, BASE×1.20)`、更新 p95 `≤ max(1.5s, BASE×1.20)`、file/一括処理 p95 `≤ max(10s, BASE×1.20)`。
- 全 profile: HTTP 5xx 0、想定外 4xx 0、deadlock/lock timeout 0、cross-owner 更新 0、重複請求/支払/勤怠 0。
- `PERF-05`: 300 は stress/capacity 境界であり、上記 latency SLO 未達だけで release FAIL にしない。ただしデータ破損 0、pool/queue が recovery 区間内に baseline 近傍へ戻ること、capacity breakpoint と graceful rejection を報告する。
- `PERF-07`: warm-up 後の full-GC heap、active connection、thread 数が単調増加せず、recovery 終了時に承認済み baseline 範囲へ戻る。範囲は W1 baseline で数値固定する。
- SQL 回数が返却件数に比例して増える N+1 は、時間閾値内でも FAIL。

負荷環境の vCPU/RAM/network、Tomcat max threads、Hikari maximum pool size、MySQL buffer/connection、load agent 数を report に含めない結果は比較不能として `BLOCKED_EVIDENCE` とする。

## 8. Security gate

1. 5 role + 未認証 × frozen page/API/action inventory の allow/deny cell を生成する。
2. 25 営業それぞれに排他的 owner fixture を作り、25 actor×25 owner を customer/engineer/project/proposal/contract/invoice で検証する。既存 seed は副担当・組織共有を含む実関連から expected set を別に算出する。
3. CSRF missing/invalid、IDOR、session expiry/権限変更後 cache、MFA/recovery/lockout、disabled account、file ACL/MIME/path、PII/log/stacktrace、XSS 出力を検査する。
4. `ApiAuditFilter` の実際の対象 method と `MenuPermissionFilter` の拒否監査を区別する。監査されない request を「監査成功」としない。
5. Security inventory の設計 coverage、実行 coverage、合格率は current/full scope ごとに 100%。越境、認証 bypass、secret/PII 漏えいは 1 件でも P0。

## 9. Defect gate、停止・再開、回帰範囲

### 9.1 severity

| Severity | 例 | Gate |
|---|---|---|
| P0 Blocker | tenant/data-scope 越境、認証 bypass、データ消失/破損、二重請求/支払、貸借不一致、migration 不能、secret 漏えい、主要環境停止 | 即時全体停止。1 時間以内 triage。修正・原因・再発防止・関連全回帰まで再開不可 |
| P1 Critical | 主要 E2E 不通、誤金額/税/締め、rollback 不成立、権限誤許可、外部重複、主要帳票不正 | 当該 module/下流を停止、当日 triage。Exit 0 件 |
| P2 Major | workaround がある機能欠損、非主要境界誤り、性能 gate 未達 | security/金額/法令領域は Exit 0。その他は owner/期限/risk と Product・QA waiver 必須 |
| P3 Minor | 表示崩れ、軽微な文言等 | 件数と waiver を報告。重要 evidence を妨げる場合は修正 |

### 9.2 停止条件

- P0 1 件、同じ root cause のデータ不整合、baseline/seed checksum drift、migration drift、evidence pipeline 破損は即時停止。
- 環境起因 failure が直近 20 instance の 5% を超えた場合は品質 defect と混ぜず環境を停止・修復する。
- M-PASS 後に route/schema/feature flag が変わったら inventory を再 freeze し、影響分母・ケース・工数を再計算する。

### 9.3 修正後の必須回帰

1. defect 再現 ID。
2. 変更 module の正常、拒否、rollback、security 回帰。
3. 同じ ITb 連携族 3 ID と直接上流・下流の連携族。
4. 関連 E2E の正常/拒否/回復 3 ID。
5. SQL、cache、thread、外部 retry に影響する場合は該当性能 profile。

rerun で偶然 PASS した flaky test は close しない。root cause、修正 commit、少なくとも 3 回の連続再現不能と関連回帰 evidence を必要とする。

## 10. JaCoCo と CI gate

JaCoCo は業務 coverage の代用にしない。`pom.xml` は現在 report を生成するだけで `jacoco:check` を強制していない。`target/site/jacoco/jacoco.csv` の参考集計は line **70.79%**（16,917/23,899）、branch **53.44%**（7,217/13,506）だが、2026-08-13 15:18 JST の artifact で commit が確認できないため Entry/Exit evidence に使用しない。

W1 で clean candidate build を実行し、commit SHA、test result、JaCoCo XML/CSV checksum、package/include/exclude を baseline として固定する。その後にだけ次を gate 化する。

- line/branch の全体・対象 package が frozen baseline から後退しない。
- 変更した Controller/Service の新規分岐は inventory/test ID に mapping される。
- 数値閾値を Exit に使うなら `jacoco:check` を設定し、CI が機械的に fail することを先に検証する。

根拠なく 4 週間で 95% へ上げる約束はしない。coverage 改善は不足 branch 数と 1 branch 当たり pilot 工数を別見積し、本計画へ追加する。CI は `verify-like-ci` 相当で skipped test 0 を必須とし、Docker/Node 不在による silent skip を許可しない。

## 11. Exit Criteria と報告

### 11.1 Current completion

1. `D_CURRENT` の設計 coverage、実行 coverage、合格率が全 inventory 種別で 100%。
2. current ITa、ITb 24 ID、E2E 12 ID と適用 instance が全 PASS。
3. future ITa/ITb 12/E2E 9 と S10/S16 追加 instance を `BLOCKED(M-PASS)` として ID、依存 spec、owner、見込みを欠落なく報告。
4. current security/performance/CI/defect/evidence/cleanup gate が全て合格。

### 11.2 Full-plan completion

1. `D_FULL` の設計 coverage、実行 coverage、合格率が全て 100%。`BLOCKED/NOT_RUN` 0。
2. ITa の frozen 全 ID、ITb 36 ID、E2E 21 ID、security cell、7 性能 profile が全て証跡付き PASS。
3. P0/P1 0、security/金額/法令 P2 0、その他 P2/P3 は承認 waiver 付き。
4. clean candidate SHA で CI skip 0、coverage gate は W1 で承認・実装した基準を合格。
5. case DB、session、mock state、mailbox、object storage、cache の残存 0。evidence index のリンク切れ 0。
6. QA lead、開発責任者、Product/業務責任者が scope、残 risk、性能 capacity、defect waiver を sign-off。

### 11.3 日次 forecast

日次報告は PASS 数だけでなく、frozen 分母、mapped、PASS/FAIL/BLOCKED/NOT_RUN、実行 instance、証跡欠落、defect arrival/closure、実測 h/ID、残工数、実効 capacity、M-PASS 待ちを含める。完了予測は毎日 `残工数 / 直近 5 日の実効 throughput` で更新し、固定終了日に合わせて scope や拒否/回復ケースを削らない。

