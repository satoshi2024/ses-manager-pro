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
  - 設定の名前が英数字と `_`・`.`・`-` だけであること。secret を environment に置いていないこと（property の正規名の綴り（`SPRING_DATASOURCE_PASSWORD` 等）と、secret らしい名前（`*_SECRET`・`*_PASSWORD`・`*_KEY`・`*_KEYS_*`・`*_PEPPER`。key version の `*_CURRENT_KEY_VERSION` を除く）を含む）。同じ設定を2回（environment と secrets の両方、または Spring が同じ property に結び付ける別の綴り）書いていないこと。
  - §1.0 の運用設定と 2026-11-18 の決定（runbook §1.6 の5〜7・11）: `FILE_SCANNER_HOST=127.0.0.1`・`FILE_SCANNER_PORT=3310`（ClamAV の sidecar）、`METRICS_SCRAPER_ENABLED=true` と `METRICS_SCRAPER_*`、`PRIVACY_TRUST_KMS_ANCHOR_FILE` の名前、`UPLOAD_BASE_PATH=/app/uploads/current`（upload root を相対 symlink `current` にする。runbook §1.6 の11 (f)。雛形の mode でも見る）。§1.0 で禁止した値が無いこと。
  - 設定の経路: 値で判定する設定を secrets に置いていないこと、`SPRING_PROFILES_ACTIVE` が大文字小文字まで `prod` で `SPRING_PROFILES_INCLUDE`・`SPRING_CONFIG_*` が無いこと、`environmentFiles`・`volumesFrom` が無く app の container が image の `command`・`entryPoint` を上書きしないこと、機能スイッチを `feature-switches.md` 1 の名前と NF05 の `INTEGRATION_HUB_PUBLIC_API_ENABLED`・`INTEGRATION_HUB_EXTERNAL_TRANSPORT_ENABLED` 以外の綴り（`APP_FEATURES_NF01_ENABLED`・`privacy.retention.enabled` 等）で渡していないこと、security・scheduler・upload・management・`spring.main`・転送ヘッダーの設定を `application.yml` に宣言された短い名前以外（`APP_SECURITY_*`・`APP_UPLOAD_*`・`MANAGEMENT_*` 等の正規名）で渡さず、`spring.flyway.*` を設定していないこと（integration hub は NF05 を開けるときに短い名前の無い設定が要るため対象外で、スイッチだけを綴りによらず見る）、`JAVA_TOOL_OPTIONS`・`JDK_JAVA_OPTIONS`・`_JAVA_OPTIONS` に `-Duser.timezone` 以外の `-D`（引用符付きを含む）・`@argfile`・`-XX:VMOptionsFile`・`-XX:Flags` が無いこと、environment の `SPRING_APPLICATION_JSON` が検査対象の設定（scheduler・bootstrap・security・Flyway・profile・config・機能スイッチ・NF07・management・compliance・upload 等）を上書きしないこと。`SPRING_APPLICATION_JSON` を secret store から、または別の綴り（`spring_application_json` 等）で渡すと中身を見られないため FAIL にする。正当な `-D` が要るようになったら validator の許可を変える（復審を経る）。
  - task の形: container は app（`ses-manager`）と ClamAV の sidecar（`clamav`）の2つだけ。task は 2048 CPU / 6144 MiB、app は 1024 / 2048 の hard limit（JVM の heap が task 全体を基準にしないため）、`clamav` は essential にせず（停止中の upload は quarantine に留まる）、自前の health check を持ち、root filesystem を読取り専用にして自分の bind volume（`clamav-*`）だけを mount し、secret・app の設定・port を持たない。volume は uploads（読書き）と NF07 の trust 文書（`privacy-trust`、読取り専用・uploads と別の access point）の EFS で、どちらも transit encryption と IAM 認可（`iam=ENABLED`）。
  - 初回起動の設定（runbook §4・§4.2）: `APP_SCHEDULING_ENABLED=false`、`SECURITY_BOOTSTRAP_ENABLED=true`、`SECURITY_BOOTSTRAP_EXPIRES_AT`、空の `BREAK_GLASS_USERNAMES`、空の `PRIVACY_TRUST_KMS_ANCHOR_FILE`、機能スイッチの環境変数（NF05 の公開API・外部送信を含む）が無いこと。
- 検査の mode は2つある。証跡は既定で OS の一時 directory の下に新しく作り、repository には書かない（`-EvidenceRoot` で変えられる）。証跡には設定の名前と期待した状態を書き、設定の値（ID・host・ARN 等）は書き写さない。
  - `-Mode Template`（既定）: repository の雛形（placeholder のまま）を検査する。
  - `-Mode Actual -TaskDefinitionPath <file>`: 実値を入れた定義（登録する JSON、または `aws ecs describe-task-definition` の出力）を検査する。placeholder が残っていないこと、2つの image が registry digest（`@sha256:<64桁>`）で固定されていること、EFS の ID が実在の形式であること、scraper 口座が `admin`・break-glass 名でないこと、`_offlineAcceptanceNotes` を消してあること（ECS は未知の field を拒否する）も見る。
    - `-Phase Initial`（既定）: 初回起動。期限は offset 付きで、現在から24時間以内の未来（越えると FAIL。起動の直前に設定して照合する）。
    - `-Phase Steady -Scheduler Enabled|Disabled`: runbook §5.5 以後（bootstrap の設定を外し、`BREAK_GLASS_USERNAMES` がちょうど2名、`PRIVACY_TRUST_KMS_ANCHOR_FILE` は空か読取り専用の mount の下の file）。`-Scheduler` は必須で、`APP_SCHEDULING_ENABLED` の期待値を指定する: scheduler の署名（runbook §4）の前と、release の drain 用 revision（runbook §2 の2）は `Disabled`（`false` だけを通す）、署名後の常態へ戻す revision は `Enabled`（`true` か未設定を通す）。§5.7 の bootstrap 再開は対象外。
  - PowerShell 7.5 以降か Windows PowerShell 5.1 で実行する（7.0〜7.4 の `ConvertFrom-Json` は ISO-8601 の文字列を日時に変えて offset を落とすため、`SECURITY_BOOTSTRAP_EXPIRES_AT` の検査で止まる）。内蔵の負例自己検査は、実行中と同じ PowerShell で子 process を起こすため、pwsh の無い 5.1 だけの環境でもそのまま動く（`-SkipInlineNegativeSelfTest` で省ける）。
  - validator を変えたときは `offline/test-validate-task-definition.ps1` で規則ごとの正例・負例を流す（雛形から作った定義を一時 directory に書いて validator を子 process で動かし、期待した PASS と FAIL の文言を照合する。pwsh と Windows PowerShell 5.1 の両方で `0 mismatched` になること。CI では動かない）。2つの script は、BOM の無い file を Windows PowerShell 5.1 が ANSI の code page で読むため、ASCII だけで書く（`ProductionTaskDefinitionTemplateTest` が検査する）。child process への引数は引用符で囲むため、空白を含む path でも動く。
- 次の点に注意する。
  - reviewer fingerprint の鍵（§1.0 の23〜25）を OS 環境変数で渡すときは、tenant一覧 `COMPLIANCE_GATE_FINGERPRINT_KEY_TENANTS` も要る。環境変数の名前からは tenant を列挙できないためである。
  - `BREAK_GLASS_USERNAMES` は、bootstrap の初回起動では空にする。runbook §5.5 でちょうど2名にする。
  - 雛形は `FREEE_ENABLED=false` で、freee の4項目を載せていない。freee を使うときは `true` にし、4項目（§1.0 の27〜30）を足す。足さないと起動しない。
  - `TZ=Asia/Tokyo` は必須である（runbook §1.0・§4）。image も `TZ` と `-Duser.timezone=Asia/Tokyo` を持つ。`JAVA_TOOL_OPTIONS` を上書きするときは `-Duser.timezone=Asia/Tokyo` を残す。validator はどちらが欠けても Fail する。
  - `RESUME_LEDGER_COPY_ALIGNMENT_ENABLED=false` は、V197 の署名を得るまでの値である。署名後に `true` にする（runbook §4.3）。
  - 雛形は初回起動の値である（`APP_SCHEDULING_ENABLED=false`、`SECURITY_BOOTSTRAP_ENABLED=true`、期限は placeholder）。`SchedulingConfig` は `APP_SCHEDULING_ENABLED` が無いと scheduler を動かすため、消さない。runbook §5.5 で bootstrap の2項目を外して break-glass 2名を入れ、scheduler は §4 の署名後に `true` にする。
  - アプリ image は ECR の private repository から registry digest で配る（2026-11-18 決定。runbook §1.4・§1.6 の5）。docker save/load の決定 XB-10 は backup tool image だけの決定である。ClamAV の image も ECR へ写して digest で固定するのは RB-DEPLOY の初期設計で、SRE が確かめる。
  - ClamAV を同じ task の sidecar で動かし task の memory を増やすことは 2026-11-18 の決定である（runbook §1.6 の6）。task と container の大きさ、essential にしないこと、読取り専用の root filesystem と bind volume、ECR への写しは RB-DEPLOY の初期設計で、SRE が確かめ実測で見直す。signature の更新経路・health の command・`TCPAddr 127.0.0.1`・停止中の upload の再scan等は runbook §1.6 の6 にある。
  - NF07 の trust 文書は EFS に置き、app は読取り専用で mount する（2026-11-18 決定。runbook §1.6 の11。path `/app/privacy-trust` と別の access point は初期設計）。第1期は `PRIVACY_TRUST_KMS_ANCHOR_FILE` を空のままにする。読取り専用の mount は app の container だけの制約で IAM の境界ではない（task role の credential は clamav にも渡る）。EFS の file system policy（access point ごとの `ClientMount`・`ClientWrite`。backup・restore の主体を含む）、backup が uploads と staging を同じ mount で使う access point と POSIX の権限、task role の最小権限は Security が確かめる（runbook §1.6 の11 の (a)〜(f)。validator は見ない）。
  - 登録する前に `_offlineAcceptanceNotes` を消す（ECS は未知の field を拒否し、実値の mode も FAIL にする）。

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
pwsh infra/ecs/offline/validate-task-definition.ps1 -Mode Actual -Phase Steady -Scheduler Disabled -TaskDefinitionPath <file> -EvidenceRoot <repository外のdirectory>   # scheduler の署名前・drain 用
pwsh infra/ecs/offline/validate-task-definition.ps1 -Mode Actual -Phase Steady -Scheduler Enabled -TaskDefinitionPath <file> -EvidenceRoot <repository外のdirectory>    # 署名後の常態
# pwsh の無い Windows PowerShell 5.1 だけの環境
powershell -NoProfile -ExecutionPolicy Bypass -File infra/ecs/offline/validate-task-definition.ps1 -Mode Actual -Phase Initial -TaskDefinitionPath <file> -EvidenceRoot <repository外のdirectory>
```

## OUT-OF-SCOPE / DEFERRED

ECS-ACTUAL, ECR, IAM actual, ALB actual, CloudWatch actual, EFS actual, SBOM/vuln scan (BLOCKED-TOOLING)

## P3 OPEN

apt パッケージ版は再ビルド日で drift し得る。ベースイメージ再設計は本バッチ対象外。
