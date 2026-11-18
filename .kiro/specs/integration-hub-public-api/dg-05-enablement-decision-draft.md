# DG-05 production開放決定（草案・未署名）

> **状態: DRAFT（未署名・効力なし）**。本書はW3-06aで起草した決定書の雛形であり、署名欄がすべて埋まるまで
> production開放の根拠にならない。AIによる起草は、Owner・Privacy・Legal・Security・SRE・DBA・Financeの
> 判断と署名を代替しない。

## 0. 位置付けと現行決定との関係

| 項目 | 内容 |
|---|---|
| 現行決定 | `approval-decision.md` の DG-05-F1-APPROVAL-20260830-01 と DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02 |
| 現行決定の効力 | 開発・test環境での実装、mock/stub provider、loopback test serverまで。**production enablement、実顧客credential、実provider送信は禁止** |
| 本決定の効力（署名後） | 現行決定の「production enablement禁止」条項だけを、**方向ごとに・本書の承認値の範囲内でだけ**解除する追加決定。F1〜Mの実装承認、契約値（認証方式、rate、SLA、公開field、retention）は現行決定をそのまま引き継ぎ、取り消さない |
| 方向の独立 | inbound（`integration.hub.public-api`：read API GET 11 paths + inbound webhook）とoutbound（`integration.hub.external-transport`：outbound webhook送信）は別の決定IDで別々に署名する（本番リリース決定 Q-12）。署名が揃った方向から開放する |
| 迂回の禁止 | outboundを開けるためにpublic-apiをtrueにすることは認めない。validatorは方向ごとに自分の決定IDとallow-listだけを検査するため、inbound承認なしにpublic-apiをtrueにすると起動を拒否する |
| 未署名時の挙動 | production既定値は両方向OFF・`provider.mode=MOCK`。承認値が無い・形式不正・片方向の値だけの場合、起動時validator（`IntegrationHubProductionEnablementPolicy`）がfail-closedで起動を止める |

本番リリース決定（2026-11-13）の B-NF05-1（inbound + outboundの両方を開く）、B-NF05-2（新しいDG-05決定で承認済み構成の下で開く。
outboundは実transport、送信先allow-list、DPA、顧客/providerの書面許可が必要）、B-NF05-3（#108 TLSを実transportの
基礎にする）を前提とする。

## 1. 決定IDの形式

| 方向 | 形式 | 例 | 設定key |
|---|---|---|---|
| inbound | `DG-05-IN-YYYYMMDD-NN` | `DG-05-IN-20261201-01` | `integration.hub.public-api.approval.decision-id`（既定の注入元 `INTEGRATION_HUB_INBOUND_DECISION_ID`） |
| outbound | `DG-05-OUT-YYYYMMDD-NN` | `DG-05-OUT-20261215-01` | `integration.hub.external-transport.approval.decision-id`（既定の注入元 `INTEGRATION_HUB_OUTBOUND_DECISION_ID`） |

- `YYYYMMDD` は署名完了日、`NN` は同日内の連番（01から）。
- 方向間でIDを流用しない（validatorは接頭辞で方向を判定し、取り違えると両方向とも拒否する）。
- 承認値（送信元CIDR、送信先、rate、予算等）を変えるときは新しいIDを発行し、旧IDの決定書へ失効日を記録する。
- 決定IDは秘密情報ではないが、配備設定と決定書の対応を監査で追えるよう、配備記録に決定IDと承認内容のdigest（§1.1）を残す。

### 1.1 承認内容の digest（決定IDへの束縛。RB-NF05、FINAL review b-13）

以前は決定IDの形式だけを検査しており、承認値を変えても決定IDを取り直さない設定をコードで検出できなかった（W6A-P3-5 / G3-P3-13）。NF06 の admission 承認・NF07 の決定ID束縛と同じく、方向ごとに承認内容の SHA-256 を決定書へ記載し、設定へ転記する。

| 方向 | 設定key | 既定の注入元 |
|---|---|---|
| inbound | `integration.hub.public-api.approval.approval-digest` | `INTEGRATION_HUB_INBOUND_APPROVAL_DIGEST` |
| outbound | `integration.hub.external-transport.approval.approval-digest` | `INTEGRATION_HUB_OUTBOUND_APPROVAL_DIGEST` |

- prod で開ける方向の digest が、未設定・形式外（小文字 hex 64 桁。大文字・前後の空白は直して照合する）・現行設定から再計算した値と不一致のいずれかなら、起動しない（`IntegrationHubProductionEnablementPolicy`）。違反の文言に設定値・digest は出さない。開けない方向の digest は見ない。
- digest は鍵を持たない完全性の束縛で、署名ではない。設定を変えられる者は正規形から値を作り直せる。そのため、変更の承認は「決定書への記載」と「二人による再計算」（下記）が担う。
- **コードで検出できないこと（RB-NF05 review P3-4）**: digest には鍵も有効期間（署名日・失効日）も無い。そのため、**撤回・置換された旧決定（旧決定ID＋旧 digest）と、それに合わせた旧い承認値をもう一度設定しても、起動検査は通る**。§1 の「旧IDの決定書へ失効日を記録する」は紙の上の記録で、コードは失効を知らない。防ぐのは配備記録（決定IDと digest の対応）と変更管理（承認された変更だけを配備する）である。
  - NF06 の admission 承認・NF07 の決定ID束縛のように、署名日・失効日を正規形へ入れて起動検査で失効を拒否する（正規形 v2。決定書と試験の golden vector を取り直す）かは、**Security/Owner の判断（未取得）**とする。
- **運用負担として受理が要ること（Owner/SRE、未取得）**: 次はどれも承認内容の変更になり、新しい DG-05 決定（新しい決定ID・digest・署名）が無いと起動しない。
  - truststore file の byte が変わること（証明書の追加・削除・入替えだけでなく、同じ証明書で作り直した場合も）。
  - `external-transport.batch-size`・`fixed-delay-ms`（OUT-7）、`public-api.unauthenticated-audit-max-per-minute`（IN-7）の変更。
- truststore は起動時の照合で1回だけ読み、digest を計算した byte 列そのものを HTTPS transport が読み込む（照合の後に file を差し替えても、信頼するのは決定書の digest と一致した内容だけ。RB-NF05 review P3-6）。truststore を替えるときは、新しい決定の後に全停止で再起動する。

**正規形**（UTF-8、各行を LF で終える。最終行も LF。1行目は方向ごとの版で、以降は次の順の `key=value`）

| inbound（`nf05-integration-hub-inbound-approval-v1`） | outbound（`nf05-integration-hub-outbound-approval-v1`） |
|---|---|
| `decision-id`（DG-05-IN の決定ID） | `decision-id`（DG-05-OUT の決定ID） |
| `bound-tenant-id`（`topology.bound-tenant-id`） | `bound-tenant-id` |
| `allowed-client-cidrs`（IN-4） | `provider-mode`（`HTTPS`。OUT-9） |
| `trusted-proxies`（IN-5。`security.trusted-proxies`） | `allowed-destinations`（OUT-1） |
| `forwarded-header`（IN-5。`X_FORWARDED_FOR`） | `truststore-type`（OUT-4。`PKCS12` / `JKS`） |
| `approved-inbound-providers`（IN-1） | `truststore-sha256`（OUT-4。truststore file の内容の SHA-256、小文字 hex） |
| `unauthenticated-audit-max-per-minute`（IN-7） | `batch-size`・`fixed-delay-ms`（OUT-7） |

- 一覧の値は、各要素の前後の空白を除き、空の要素と重複を除いて、**ASCII（文字コード）の昇順**にカンマで連結する（shell では `LC_ALL=C sort`。locale 依存の並べ方では一致しない）。CIDR と送信先は小文字にしてから並べる（IPv6 の16進。IPv4 と数字は変わらない）。inbound provider 名は大文字小文字を区別して照合されるため、小文字にしない。
- 決定ID・tenant は前後の空白だけを除く。tenant は英数字で始まる `A-Z a-z 0-9 . _ : -` の100文字以内でなければならない（改行で正規形の行を偽造させない）。
- truststore は file の byte 列の SHA-256（`sha256sum truststore.p12`）。証明書を入れ替えた・作り直した（中身が同じでも byte が変われば）ときは、新しい決定IDと digest が要る。file を読めない・空・1 MiB 超は起動しない。
- 含めないもの: 秘密値（public-id-key、`integration.hub.crypto.*` の鍵と credential hash の pepper、truststore password）と、承認内容ではない調整値（timeout・lease・purge 間隔等）。秘密値の注入は IN-6・OUT-6 の secret store 記録で確かめる。

**二人による再計算**（転記の前に必ず行う）
1. 署名者（Security）と転記者（SRE）が、決定書の承認値からそれぞれ正規形を作る（下の `printf` の形。一覧は上の規則で並べる）。outbound は、配備する truststore file そのものの `sha256sum` を使う。
2. 各自 `sha256sum` で計算し、決定書に記載された digest と一致することを確かめる。一致しなければ転記しない（決定書か設定のどちらかが違う）。
3. 手順そのものの確認に下の golden vector を使い、二人とも同じ値が出ることを先に確かめる。
4. 全 instance に同じ値を設定し、配備記録に決定IDと digest を残す。

**golden vector**（合成値。`IntegrationHubApprovalDigestTest` の固定値と同じ）

```sh
# inbound: 送信元CIDR に " 203.0.113.0/24"・"198.51.100.0/24"・"2001:DB8:10::/48"・"203.0.113.0/24"、
#          trusted proxy に "10.0.1.0/24"・"10.0.0.0/24"、provider に "provider-b"・"Provider-A" を設定した場合
printf 'nf05-integration-hub-inbound-approval-v1\ndecision-id=DG-05-IN-20261201-01\nbound-tenant-id=default\nallowed-client-cidrs=198.51.100.0/24,2001:db8:10::/48,203.0.113.0/24\ntrusted-proxies=10.0.0.0/24,10.0.1.0/24\nforwarded-header=X_FORWARDED_FOR\napproved-inbound-providers=Provider-A,provider-b\nunauthenticated-audit-max-per-minute=60\n' | sha256sum
# 1499134c96fcaf899cc94440a70a3718b2955bd5c952d787537223b74eeed0f6

# outbound: truststore の中身が bytes「nf05-synthetic-truststore」（改行なし）の合成 file の場合
printf 'nf05-synthetic-truststore' | sha256sum
# 7bdcc7524ab989c6b6df0d4c5444c3ee1e3be50d93eb1f264ebb1ed271aea5a8
printf 'nf05-integration-hub-outbound-approval-v1\ndecision-id=DG-05-OUT-20261215-01\nbound-tenant-id=default\nprovider-mode=HTTPS\nallowed-destinations=api.partner-a.example:8443,hooks.partner-b.example:443\ntruststore-type=PKCS12\ntruststore-sha256=7bdcc7524ab989c6b6df0d4c5444c3ee1e3be50d93eb1f264ebb1ed271aea5a8\nbatch-size=32\nfixed-delay-ms=1000\n' | sha256sum
# b0959706d605aedf98f7aad0c2b31e0054869bbc2a84118cc8bbf8b9eec509a8
```

- 正規形を変える場合は版（1行目）を上げ、本節の例と試験の固定値と、署名済み決定書の digest を全て取り直す。

## 2. inbound の承認項目（DG-05-IN）

| # | 承認項目 | 承認する内容 | 設定・証跡 | 主担当 |
|---|---|---|---|---|
| IN-1 | 開放範囲 | read API（承認済みGET 11 paths、inventory allow-list）と、受け付けるinbound provider一覧。command/exportは引き続きdefault deny | `provider.approved-inbound-providers`（空ならwebhookは全拒否） | Owner |
| IN-2 | 取引先と書面許可 | 接続する取引先（B2B system client）ごとの利用許可書。顧客・providerの書面許可 | 決定書の添付。client登録の根拠番号 | Owner / Legal |
| IN-3 | DPA・個人情報 | 要員の稼働状況等はAPPIの個人情報に当たりうる。NF07の分類・保存期間の決定とDPA締結 | DPA番号、NF07決定ID | Privacy / Legal |
| IN-4 | 送信元CIDR allow-list | 取引先の送信元CIDR。IPv4は/16、IPv6は/32より広い範囲は不可。clientごとの`allowed_cidrs`はこの範囲内に限る | `public-api.approval.allowed-client-cidrs`（実行時にclientのallowed_cidrsと両方への一致を要求） | Security |
| IN-5 | 入口構成（D-1） | LB/WAFの経路、trusted proxy（LBのsubnet）、ALBの`routing.http.xff_header_processing.mode=append`と`routing.http.xff_client_port.enabled=false`、WAFによる認証前rate制限（N5-P3-4） | `security.trusted-proxies`（CIDR必須）、`security.forwarded-header=X_FORWARDED_FOR`、runbook §0 | SRE / Security |
| IN-6 | credential | client・credential・scopeの発行手順と保管、keyring（`integration.hub.crypto.*`）とcredential hashのpepper（`integration.hub.crypto.credential-hash-pepper`。暗号鍵と別の32 byte。N5-P3-2）、`public-api.public-id-key`（32 byte以上）、`topology.bound-tenant-id` | secret storeの登録記録。G3管理者APIの発行応答で一度だけ表示。安全な配布・rotationとRunner証跡が必要 | Security / SRE |
| IN-7 | rate / quota | client×scope×tenant×route templateで60 req/min、burst 20、1日50,000（現行決定値）。未認証rejectの監査行上限 | `public-api.unauthenticated-audit-max-per-minute`（node単位、既定60） | SRE |
| IN-8 | 予算 | 監視・DB容量・WAF費用の上限。監査1年保存の容量見積り | 決定書に金額と見積り根拠 | Finance |
| IN-9 | 監査保存 | `t_external_api_audit` の1年保存とpurge。V192でUTC一年expiry、EXTERNAL_AUDIT hold/checkpoint付きbounded purgeを追加。MySQL Runner証跡が必要 | DDL lane完了の証跡 | DBA / Privacy |
| IN-10 | 法人readiness | 法人backfill未解決行が0であること（起動時に検査） | `LegalEntityReadinessStartupValidator` | DBA |
| IN-11 | 処理範囲 | inbound processorは現在Noop。開放後も「受信して記帳するだけ」であることへの合意 | 決定書に明記 | Owner |

## 3. outbound の承認項目（DG-05-OUT）

| # | 承認項目 | 承認する内容 | 設定・証跡 | 主担当 |
|---|---|---|---|---|
| OUT-1 | 送信先allow-list | 送信先の`host:port`（小文字FQDN、IP literal・`localhost`系は不可） | `external-transport.approval.allowed-destinations` | Security / Owner |
| OUT-2 | 書面許可 | 送信先の顧客・providerからの受信許可書 | 決定書の添付 | Owner / Legal |
| OUT-3 | DPA・個人情報 | payload（`ExternalDtoSnapshot.OUTBOUND_FIELDS`）の個人情報分類、保存期間、DPA。`X-Correlation-ID`の出所にPIIが無いことの確認 | DPA番号、NF07決定ID | Privacy / Legal |
| OUT-4 | truststore・TLS方針 | trustの出所（JDK cacertsか専用truststore）、TLSv1.3/1.2、hostname検証、失効確認とpinningの方針 | G3専用PKCS12/JKS、TLS1.3/1.2とAEAD cipher、public suffix対応hostname検証。OCSP/CRL強制取得・leaf/SPKI pinning無しの判断を明示署名 | Security |
| OUT-5 | egress制御 | host allow-list、期限付きDNS snapshot、全解決addressの`NonGlobalAddressPolicy`判定、実peer照合 | G3実装。管理DNS・egress firewall・metadata拒否と実ネットワーク証跡をSREが用意する | Security |
| OUT-6 | credential | webhook署名secretの発行・保管・rotation、keyringとcredential hashのpepper、`public-api.public-id-key`（outbound payloadのbindingにも使う）、`topology.bound-tenant-id` | secret storeの登録記録 | Security / SRE |
| OUT-7 | rate | 送信先ごとの送信上限、`external-transport.batch-size` / `fixed-delay-ms`、retry最大8回とbackoff | 決定書に数値 | SRE / Owner |
| OUT-8 | 予算 | 月間送信件数と外部費用（送信先側の課金を含む）の上限 | 決定書に金額 | Finance |
| OUT-9 | 実装前提 | 本番用transport mode（実HTTPS）、業務eventのenqueue producer、subscription登録（登録時にもOUT-1で検証）、outbound replay入口、kill switch時のhold/DLQ | G3実装。HTTPSのみ可能。service外更新・bulk/import・削除は対象外。snapshot通知契約とv2 audience署名の受信側互換性をOwnerが承認する | 実装担当 |
| OUT-10 | retention | delivery payload 30/90日、replay監査1年のpurge（outboundだけの開放でもscheduler生成済み） | `IntegrationHubRetentionPurgeCondition` | DBA |
| OUT-11 | 法人readiness | outboundだけの開放でも起動時に検査 | `LegalEntityReadinessStartupValidator` | DBA |

## 4. 開放手順

### 4.1 inbound

1. 本書のinbound欄（IN-1〜IN-11）を埋め、§5の署名を揃える。決定IDを採番する。
2. 前提を確認する: DDL lane（IN-9）完了、production用credential発行経路（IN-6）実装済み、WAFの認証前rate制限（IN-5）設定済み。
3. secret store / 環境へ注入する: `public-api.enabled=true`、`public-api.approval.decision-id`、
   `public-api.approval.allowed-client-cidrs`、`public-api.approval.approval-digest`（§1.1。二人で再計算した値）、
   `public-api.public-id-key`、`topology.bound-tenant-id`、`security.trusted-proxies`（LBのsubnet）、
   `integration.hub.crypto.*`（現行 key version・鍵・`credential-hash-pepper`）。outbound側の値は触らない。
4. 全停止配備で起動し、validatorが通ることを確認する（不足時は起動しない。エラー文の項目を本書と照合する）。
5. LB/WAFの`/external-api/**`遮断（D-1）を、承認済み送信元だけを通すruleへ置き換える。
6. 承認済みclientからの疎通確認: 正常200、allow-list外からの401（`CLIENT_IP_NOT_ALLOWED`）、署名不正401。
7. runbook §1の監視を有効化し、初週は毎日`integration_hub_external_api_audit_suppressed_total`と監査件数を確認する。

### 4.2 outbound

1. 本書のoutbound欄（OUT-1〜OUT-11）を埋め、§5の署名を揃える。決定IDを採番する。
2. 前提を確認する: W3-06bの実HTTPS transport・egress制御・TLS方針（OUT-4/5/9）が独立Review PASS済み。
3. 注入する: `external-transport.enabled=true`、`external-transport.approval.decision-id`、
   `external-transport.approval.allowed-destinations`、`external-transport.approval.approval-digest`（§1.1。配備する
   truststore file の `sha256sum` を含めて二人で再計算した値）、`provider.mode=HTTPS`、専用truststore、`public-api.public-id-key`、
   `topology.bound-tenant-id`、`integration.hub.crypto.*`（`credential-hash-pepper` を含む）。**`public-api.enabled`はfalseのまま**（inboundを同時に開く場合はDG-05-INが別途必要）。
4. 全停止配備で起動し、validatorが通ることを確認する。
5. 単一送信先・単一subscriptionで送信を確認し、DLQ・retry・payload purgeの動作を確認してから対象を広げる。

## 5. 署名欄

| 役割 | 対象 | 署名日 | 状態 |
|---|---|---|---|
| Owner（PROJECT_OWNER） | IN-1/2/11、OUT-1/2/7/9の範囲 | | 未署名 |
| Privacy | IN-3/9、OUT-3/10 | | 未署名 |
| Legal | IN-2/3、OUT-2/3（DPA・書面許可） | | 未署名 |
| Security | IN-4/5/6、OUT-1/4/5/6 | | 未署名 |
| SRE / Operations | IN-5/6/7、OUT-6/7、runbook | | 未署名 |
| DBA | IN-9/10、OUT-10/11 | | 未署名 |
| Finance | IN-8、OUT-8（予算） | | 未署名 |

個人実名は記録しない（現行決定と同じくOwnerRef/OwnerTypeで表す）。

## 6. G3の実装状況と未充足の証跡（実装は開放承認ではない）

| 項目 | 方向 | 担当batch |
|---|---|---|
| V192のUTC変換・一年expiry・hold/purge | inbound | G3実装、MySQL Runner未実行 |
| client/credential/scope管理者API | inbound | G3実装、安全なsecret配布はSRE |
| 実HTTPS transport・SSRF・TLS分類 | outbound | G3実装、Security判断・実ネットワーク証跡は未充足 |
| 4 resource.changed producer・subscription・replay | outbound | G3実装、実経路Runnerと通知範囲判断が必要 |
| 有限status gauge（SQL監視を置換） | 両方向 | G3実装、監視基盤設定はSRE |
| 決定IDと承認内容の束縛（§1.1の digest） | 両方向 | RB-NF05実装。golden vector と不一致・未設定・truststore差替えの拒否は fast 試験（`IntegrationHubApprovalDigestTest`・`IntegrationHubProductionEnablementPolicyTest`） |
| credential hash の pepper（N5-P3-2） | 両方向 | RB-NF05実装。保存用 hash を pepper 付き HMAC-SHA256 にし、pepper を開放時の必須設定にした。secret は43文字以上の base64url だけを発行できる。V134 の列 comment（「平文secretのSHA-256 hex」）は適用済み migration のため直していない |
| 要求経路の lease 一括回収の間引き（N5-P3-7） | inbound | RB-NF05実装（node 単位で `inbound-lease-seconds` に1回）。同一 client の受信を client 行の `FOR UPDATE` で直列化する点は、失効との競合を防ぐ設計として残す（SRE 負荷実測で確かめる） |
| ownership の実MySQL試験（project・contract-status・invoice-status の分岐） | 両方向 | RB-NF05で `IntegrationHubV192AuditRetentionMySqlTest`（mysql shard 3）に追加。**MySQL Runner未実行** |
| 実wireのTLS（期限切れleaf・未信頼CA・名前の不一致・ClientHelloの版とcipher）と実connectorのXFF | outbound / inbound | RB-NF05で fast 試験に追加（`HttpsIntegrationHubWebhookTransportWireTlsTest`・`ExternalApiForwardedHeaderConnectorTest`）。JVM内のloopbackだけで、宛先guardはmock。実ネットワークpeer・ALB経由の証跡は代替しない（SREが用意する） |
| SRE の負荷実測（認証前のbody読取・AES-GCM・HMAC・rate行のFOR UPDATE・client行の直列化・5秒周期の状態lock・bulk検索） | 両方向 | **未取得**（人が行う。deploy-runbook §1.7 の NF05 行） |

## 7. 撤回と緊急停止

- 撤回は方向ごとに行う。対象方向の`enabled=false`で再起動し、inboundはLB/WAFの遮断（D-1）を戻す。
- 撤回してもretention purgeは他方向が有効なら継続する。両方向OFFでschedulerは生成されないため、
  撤回後に残るpayload・監査の扱いはDBAが別途判断する（手動purgeまたは一時的な再有効化）。
- 承認値の変更は新しい決定IDで行い、本書の版を更新する。

## G3rの追加署名条件（未署名）

OUT-9: P1-1/P1-2とP2-1〜3の正式証拠（実container commit/rollback、小数秒ClockのH2/MySQL往復、
再起動前の旧台帳隔離、TLS恒久障害DLQとreplay前のTLS確認）を最終SHAで取得する。
OUT-7（G3sで置換）: 永続OFFを確認したOFF→ONだけの隔離、ON→ONのキュー維持とat-least-once、結果不明CLAIMEDの照合を承認する。
未登録ON／状態DB・監査障害ではNF05だけを未準備とし、他機能の起動を継続する。4096件の起動拒否は採用しない。
OFF書込成功・同じbound-tenant fingerprintの確認をON変更の必須gateとする。未確認OFF経由は後続ONから識別できない。
128件の周期復旧とlease待ち、Prometheus→SRE当番／Ownerのalert経路・試験通知、条件付きbulk replayの部分成功・監査失敗時照合を署名条件に追加する。
24時間未送信期限はreplay auditのgeneration作成時刻から測る。送信lockは5分、開始予算120秒＋最後のnetwork一項でありDB待ちの上限ではない。実測とCASの重複防止を確認する。
OUT-6: outbound subscriptionを含む24時間overlapローテーション、新clientIdでの90日期限移行、用途分離canonical付きの共用secretを明示判断する。
P2-2: tenant欠損時は業務継続のためeventをskipする。WARN/counterと業務記録を照合する復旧手順、event欠落の許容をOwnerが署名する。
IN-5: 二つのtrusted-proxies設定の整合とALB subnetの/24分割をSRE/Securityが確認する。
外部接続禁止のローカル作業は実ネットワークpeerの証拠を代替しない。truststore、120秒pin grace、DNS、egress、
OCSP/CRL/pinning無しの判断、DPA/書面許可/予算と既存の全署名は引き続き必要。Production NO-GOを維持する。
