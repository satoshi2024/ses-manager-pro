# ECS app image — offline AWS / local ECS-like simulation

本番 ECS/Fargate 向けアプリコンテナイメージの**ローカル**ビルド手順。
**AWS アカウントなし — ECR push / ECS deploy / AWS API は OUT-OF-SCOPE。**

## Digest の意味

| 項目 | 意味 |
|---|---|
| `localImageId` | ローカルの `docker build` で得た **LOCAL_IMAGE_ID**（`docker inspect` の Id） |
| `baseImageDigest` | ベース JRE イメージの digest（Dockerfile の `FROM @sha256:…`） |
| `jarSha256` | イメージに含めた JAR の SHA256 |
| `gitRevision` | ビルド時の git HEAD |
| `registryDigest` | **ABSENT** — `localImageId` を ECR digest や `IMAGE_DIGEST` として扱ってはならない |

`task-definition.prod.json` は `...@sha256:IMAGE_DIGEST` のプレースホルダーのままにする。

## prod 必須設定（環境変数・secret）

- prod が起動を拒否する設定の**正本は `.kiro/specs/production-launch/deploy-runbook.md` §1.0** である。常に必須なのは1〜26の26項目で、freee を使う場合はさらに27〜30の4項目が要る。各項目の用途・形式・secret かどうか・出処はそこに書く。
- `task-definition.prod.json` には、その名前を placeholder（`*_PLACEHOLDER`、`ACCOUNT_ID` の ARN）で載せるだけで、値は書かない。
- 配備では全ての placeholder を置き換える。置き換え忘れがあると、アプリの起動時検査か ECS の secret 取得で止まる。
- `validate-task-definition.ps1` は次を検査する。
  - §1.0 の1〜26の名前が environment か secrets に全てあること。`FREEE_ENABLED=true` のときは27〜30も。
  - secret を environment に置いていないこと。
  - §1.0 の運用設定（`FILE_SCANNER_HOST` / `FILE_SCANNER_PORT`、`METRICS_SCRAPER_*`）の名前があり、§1.0 で禁止した値が無いこと。
  - 初回起動の設定（runbook §4・§4.2）: `APP_SCHEDULING_ENABLED=false`、`SECURITY_BOOTSTRAP_ENABLED=true`、`SECURITY_BOOTSTRAP_EXPIRES_AT`、空の `BREAK_GLASS_USERNAMES`、機能スイッチの環境変数が無いこと。
- 検査の mode は2つある。証跡は既定で OS の一時 directory の下に新しく作り、repository には書かない（`-EvidenceRoot` で変えられる。証跡には設定の名前だけを書き、値は書かない）。
  - `-Mode Template`（既定）: repository の雛形（placeholder のまま）を検査する。
  - `-Mode Actual -TaskDefinitionPath <file>`: 実値を入れた定義（登録する JSON、または `aws ecs describe-task-definition` の出力）を検査する。placeholder が残っていないこと、image が registry digest（`@sha256:<64桁>`）で固定されていること、EFS の ID が実在の形式であること、scraper 口座が `admin`・break-glass 名でないことも見る。`-Phase Initial`（既定）は初回起動、`-Phase Steady` は runbook §5.5 以後（bootstrap の設定を外し、`BREAK_GLASS_USERNAMES` がちょうど2名）。§5.7 の bootstrap 再開は対象外。
  - PowerShell 7.5 以降か Windows PowerShell 5.1 で実行する（7.0〜7.4 の `ConvertFrom-Json` は ISO-8601 の文字列を日時に変えて offset を落とすため、`SECURITY_BOOTSTRAP_EXPIRES_AT` の検査で止まる）。
- 次の点に注意する。
  - reviewer fingerprint の鍵（§1.0 の23〜25）を OS 環境変数で渡すときは、tenant一覧 `COMPLIANCE_GATE_FINGERPRINT_KEY_TENANTS` も要る。環境変数の名前からは tenant を列挙できないためである。
  - `BREAK_GLASS_USERNAMES` は、bootstrap の初回起動では空にする。runbook §5.5 でちょうど2名にする。
  - 雛形は `FREEE_ENABLED=false` で、freee の4項目を載せていない。freee を使うときは `true` にし、4項目（§1.0 の27〜30）を足す。足さないと起動しない。
  - `TZ=Asia/Tokyo` は必須である（runbook §1.0・§4）。image も `TZ` と `-Duser.timezone=Asia/Tokyo` を持つ。`JAVA_TOOL_OPTIONS` を上書きするときは `-Duser.timezone=Asia/Tokyo` を残す。validator はどちらが欠けても Fail する。
  - `RESUME_LEDGER_COPY_ALIGNMENT_ENABLED=false` は、V197 の署名を得るまでの値である。署名後に `true` にする（runbook §4.3）。
  - 雛形は初回起動の値である（`APP_SCHEDULING_ENABLED=false`、`SECURITY_BOOTSTRAP_ENABLED=true`、期限は placeholder）。`SchedulingConfig` は `APP_SCHEDULING_ENABLED` が無いと scheduler を動かすため、消さない。runbook §5.5 で bootstrap の2項目を外して break-glass 2名を入れ、scheduler は §4 の署名後に `true` にする。
  - ClamAV の置き方（同じ task の sidecar か外部の service か）と Prometheus の scrape 経路は未決である（runbook §1.6 の6・7）。sidecar を足しても validator は app の container を名前（`ses-manager`）で探す。
  - アプリ image の配布経路は未決である（runbook §1.6 の5）。docker save/load の決定 XB-10 は backup tool image の決定で、Fargate は registry から pull する。

## Probe 規約

- ECS container healthCheck → `/actuator/health/liveness`
- ALB Target Group → `/actuator/health/readiness`（**DEFERRED** until real AWS）

## ビルド & 検証

```powershell
pwsh infra/ecs/build-image.ps1
pwsh infra/ecs/offline/verify-offline-sim.ps1
pwsh infra/ecs/offline/validate-task-definition.ps1
# 実値を入れた定義（runbook §4・§5.5）
pwsh infra/ecs/offline/validate-task-definition.ps1 -Mode Actual -Phase Initial -TaskDefinitionPath <file> -EvidenceRoot <repository外のdirectory>
pwsh infra/ecs/offline/validate-task-definition.ps1 -Mode Actual -Phase Steady -TaskDefinitionPath <file> -EvidenceRoot <repository外のdirectory>
```

## OUT-OF-SCOPE / DEFERRED

ECS-ACTUAL, ECR, IAM actual, ALB actual, CloudWatch actual, EFS actual, SBOM/vuln scan (BLOCKED-TOOLING)

## P3 OPEN

apt パッケージ版は再ビルド日で drift し得る。ベースイメージ再設計は本バッチ対象外。
