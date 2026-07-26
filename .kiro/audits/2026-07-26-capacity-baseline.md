# 2026-07-26 Task C0 容量基線

状態: **実測未完了（環境未整備）**。本書はスクリプト準備と実行阻止条件の記録であり、容量受入報告ではない。

## 範囲

本書は `.kiro/audits/2026-07-26-unresolved-hardening-action-plan.md` の Task C0 だけを扱う。業務コード、スレッドプール、接続プール、Redis、キャッシュ、リードレプリカは変更しない。

作成した再実行用スクリプトは [scripts/capacity-baseline.ps1](../../scripts/capacity-baseline.ps1) である。PowerShell から実行し、Node.js は必要としない。

## 実施した環境確認

確認日時: 2026-07-26（Asia/Tokyo）

| 項目 | 確認結果 |
|---|---|
| OS / 実行環境 | Windows。Java 17.0.19 が PATH にある |
| PowerShell | スクリプトは Windows PowerShell 5.1 互換の構文を使用。実行時バージョンはスクリプトの `environment.json` に記録する |
| MySQL | Windows サービス `MySQL84` は Running。`127.0.0.1:3306` は LISTENING / TCP 接続成功 |
| MySQL CLI | PATH にはないが `C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe` が存在 |
| アプリ | `127.0.0.1:8080` は LISTENING ではない。実行中の Java アプリプロセスも確認できなかった |
| Actuator | `pom.xml` に `spring-boot-starter-actuator`、Micrometer Prometheus 依存がない。Hikari/Tomcat/JVM の HTTP メトリクス端点は利用できない |
| 既存変更 | 作業開始時点で複数の未コミット変更が存在したため、対象外ファイルは変更していない |

MySQL のポート疎通は確認したが、データベース名・認証情報での SQL ログイン、データ件数、MySQL の CPU/メモリ仕様は未確認である。パスワードは文書へ記録しない。

## スクリプトの測定仕様

スクリプトは各仮想ユーザーが最初に `GET /login` を実行し、`XSRF-TOKEN` Cookie を取得する。その値を URL デコードして `X-XSRF-TOKEN` Header に複写し、`POST /login` のフォーム送信を行う。ログイン後も更新系リクエストには同じ Cookie/Header 対を使用する。ログイン失敗、CSRF/権限 403、4xx、5xx、リダイレクト、通信エラーを別分類で保存する。

通常 API とエクスポートは別ステージとして実行する。

| シナリオ | 内容 |
|---|---|
| normal | `GET /api/engineers`、`GET /api/engineers/{id}`、`GET /api/dashboard/summary`、`GET /api/notifications/unread-count` を巡回。更新を有効にした場合は `PUT /api/notifications/read-all` を 20 リクエストに 1 回（5%）含める |
| export | 通常 API と混ぜず、`/api/engineers/export` を既定値として最大 2 並行で測定。`-ExportPath /api/contracts/export` で契約出力へ差し替え可能 |
| 段階 | 通常 API は 20 → 50 → 100 仮想ユーザー。各段階の既定時間は 30 分。エクスポートは各段階の後に、指定した最大 2 並行だけで実行 |

各リクエストの所要時間を収集し、ステージごとに p50/p95/p99、req/s、HTTP/通信エラー分類を `summary.csv` に出力する。ログイン処理は `requests.csv` の `Kind=setup` として残し、通常 API/エクスポートのレイテンシ percentiles には混ぜない。

通常更新はデータの追加・削除を行わない `read-all` 操作だが、監査ログ等のアプリ側副作用があり得る。実行前にローカル DB をバックアップし、不要なら `-SkipUpdates` で除外する。その場合、更新系の測定は未実施になる。

## 再現コマンド

以下はアプリを別ターミナルで起動済み、かつローカル開発用ログインが有効な場合の例である。スクリプトはアプリや MySQL を起動・停止しない。

```powershell
$env:LOADTEST_USERNAME = 'admin'
$env:LOADTEST_PASSWORD = 'admin123'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '<ローカルDBパスワード>'

.\scripts\capacity-baseline.ps1 `
  -BaseUrl 'http://localhost:8080' `
  -StageDurationSeconds 1800 `
  -ThinkTimeMs 250 `
  -AppPid <アプリJavaプロセスID> `
  -MySqlCli 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'
```

まずアプリ疎通と監視端点だけを確認する場合:

```powershell
.\scripts\capacity-baseline.ps1 -CheckOnly
```

契約エクスポートを別に測る場合:

```powershell
.\scripts\capacity-baseline.ps1 -ExportPath '/api/contracts/export'
```

本スクリプトは仮想ユーザーごとに PowerShell runspace を作り、RunspacePool で同時数を制限する。実測時は負荷発生元の CPU/メモリも同じ時間帯に確認する。負荷発生元が先に飽和した場合、その結果をアプリ容量の根拠にしない。

## 監視と現時点の未検証項目

スクリプトは次を記録する。

- `summary.csv`: ステージ別 p50/p95/p99、req/s、総エラー数、エラー分類。
- `requests.csv`: リクエスト単位のメソッド、パス、HTTP ステータス、分類、所要時間、UTC 時刻。
- `environment.json`: 実行環境、負荷条件、ログイン前の `/login`/Actuator 疎通。
- `monitor-snapshots.json`: ステージ前後のアプリプロセス使用メモリ、任意の MySQL CLI ステータス、Actuator 疎通。

現環境では Actuator がないため、次の値はまだ測定不能である。

- Hikari active / idle / pending、DB プール待機時間。
- Tomcat busy thread。
- JVM heap / GC。
- MySQL CPU / メモリ / slow query の時間軸付き値（CLI は存在するが、認証情報未確認）。
- 上位 5 API の SQL 本数。現状の HTTP スクリプトだけでは SQL 本数を分離できない。

したがって、現時点で p50/p95/p99、req/s、エラー率、容量限界、SLO 達成可否の実測データは存在しない。数値を推測・補完していない。

## 実行阻塞条件

今回の実行を実データ負荷試験まで進められなかった具体的理由は次のとおり。

1. アプリが起動しておらず、`127.0.0.1:8080` に接続できない。
2. MySQL の TCP サービスは稼働しているが、`ses_manager_db` へ接続する認証情報と代表データ件数が未確認。
3. Hikari/Tomcat/JVM の必要メトリクス端点がアプリに存在しない。

アプリを起動し、対象データセットと DB 認証情報を確定した後、上記コマンドを同じ条件で実行する。次回記録では、アプリ設定（JVM ヒープ、Tomcat 設定、Hikari 設定）、MySQL の CPU コア/メモリ/max connections、主要テーブル件数、warm/cold 条件、各段階の実測結果を追記する。

## 改善判断（実測前）

実測前に接続プール、Tomcat スレッド、Redis、キャッシュを変更する提案はしない。C0後は、SQL本数、プール待ち、DB CPU/IO、JVM GC のどれが最初に SLO を外すかを確認し、原因に対応する最小の C1 以降を別タスクとして再計測する。
