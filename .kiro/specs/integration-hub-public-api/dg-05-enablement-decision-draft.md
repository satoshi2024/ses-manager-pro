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
- 決定IDは秘密情報ではないが、配備設定と決定書の対応を監査で追えるよう、配備記録に決定IDと設定のhashを残す。

## 2. inbound の承認項目（DG-05-IN）

| # | 承認項目 | 承認する内容 | 設定・証跡 | 主担当 |
|---|---|---|---|---|
| IN-1 | 開放範囲 | read API（承認済みGET 11 paths、inventory allow-list）と、受け付けるinbound provider一覧。command/exportは引き続きdefault deny | `provider.approved-inbound-providers`（空ならwebhookは全拒否） | Owner |
| IN-2 | 取引先と書面許可 | 接続する取引先（B2B system client）ごとの利用許可書。顧客・providerの書面許可 | 決定書の添付。client登録の根拠番号 | Owner / Legal |
| IN-3 | DPA・個人情報 | 要員の稼働状況等はAPPIの個人情報に当たりうる。NF07の分類・保存期間の決定とDPA締結 | DPA番号、NF07決定ID | Privacy / Legal |
| IN-4 | 送信元CIDR allow-list | 取引先の送信元CIDR。IPv4は/16、IPv6は/32より広い範囲は不可。clientごとの`allowed_cidrs`はこの範囲内に限る | `public-api.approval.allowed-client-cidrs`（実行時にclientのallowed_cidrsと両方への一致を要求） | Security |
| IN-5 | 入口構成（D-1） | LB/WAFの経路、trusted proxy（LBのsubnet）、ALBの`routing.http.xff_header_processing.mode=append`と`routing.http.xff_client_port.enabled=false`、WAFによる認証前rate制限（N5-P3-4） | `security.trusted-proxies`（CIDR必須）、`security.forwarded-header=X_FORWARDED_FOR`、runbook §0 | SRE / Security |
| IN-6 | credential | client・credential・scopeの発行手順と保管、keyring（`integration.hub.crypto.*`）、`public-api.public-id-key`（32 byte以上）、`topology.bound-tenant-id` | secret storeの登録記録。**production用の発行経路は未実装（N5-P2-4）** | Security / SRE |
| IN-7 | rate / quota | client×scope×tenant×route templateで60 req/min、burst 20、1日50,000（現行決定値）。未認証rejectの監査行上限 | `public-api.unauthenticated-audit-max-per-minute`（node単位、既定60） | SRE |
| IN-8 | 予算 | 監視・DB容量・WAF費用の上限。監査1年保存の容量見積り | 決定書に金額と見積り根拠 | Finance |
| IN-9 | 監査保存 | `t_external_api_audit` の1年保存とpurge。**保存期限列とpurgeは未実装（DDL lane）** | DDL lane完了の証跡 | DBA / Privacy |
| IN-10 | 法人readiness | 法人backfill未解決行が0であること（起動時に検査） | `LegalEntityReadinessStartupValidator` | DBA |
| IN-11 | 処理範囲 | inbound processorは現在Noop。開放後も「受信して記帳するだけ」であることへの合意 | 決定書に明記 | Owner |

## 3. outbound の承認項目（DG-05-OUT）

| # | 承認項目 | 承認する内容 | 設定・証跡 | 主担当 |
|---|---|---|---|---|
| OUT-1 | 送信先allow-list | 送信先の`host:port`（小文字FQDN、IP literal・`localhost`系は不可） | `external-transport.approval.allowed-destinations` | Security / Owner |
| OUT-2 | 書面許可 | 送信先の顧客・providerからの受信許可書 | 決定書の添付 | Owner / Legal |
| OUT-3 | DPA・個人情報 | payload（`ExternalDtoSnapshot.OUTBOUND_FIELDS`）の個人情報分類、保存期間、DPA。`X-Correlation-ID`の出所にPIIが無いことの確認 | DPA番号、NF07決定ID | Privacy / Legal |
| OUT-4 | truststore・TLS方針 | trustの出所（JDK cacertsか専用truststore）、TLSv1.3/1.2、hostname検証、失効確認とpinningの方針 | **実HTTPS transportとともにW3-06bで設定化**（TLS残存項目 P2-2） | Security |
| OUT-5 | egress制御 | host allow-list、期限付きDNS snapshot、全解決addressの`NonGlobalAddressPolicy`判定、実peer照合 | **W3-06b**（TLS残存項目 P2-1） | Security |
| OUT-6 | credential | webhook署名secretの発行・保管・rotation、keyring、`public-api.public-id-key`（outbound payloadのbindingにも使う）、`topology.bound-tenant-id` | secret storeの登録記録 | Security / SRE |
| OUT-7 | rate | 送信先ごとの送信上限、`external-transport.batch-size` / `fixed-delay-ms`、retry最大8回とbackoff | 決定書に数値 | SRE / Owner |
| OUT-8 | 予算 | 月間送信件数と外部費用（送信先側の課金を含む）の上限 | 決定書に金額 | Finance |
| OUT-9 | 実装前提 | 本番用transport mode（実HTTPS）、業務eventのenqueue producer、subscription登録（登録時にもOUT-1で検証）、outbound replay入口、kill switch時のhold/DLQ | **W3-06b / N5-P2-4**。現行modeはいずれも本番outbound不可としてvalidatorが拒否する | 実装担当 |
| OUT-10 | retention | delivery payload 30/90日、replay監査1年のpurge（outboundだけの開放でもscheduler生成済み） | `IntegrationHubRetentionPurgeCondition` | DBA |
| OUT-11 | 法人readiness | outboundだけの開放でも起動時に検査 | `LegalEntityReadinessStartupValidator` | DBA |

## 4. 開放手順

### 4.1 inbound

1. 本書のinbound欄（IN-1〜IN-11）を埋め、§5の署名を揃える。決定IDを採番する。
2. 前提を確認する: DDL lane（IN-9）完了、production用credential発行経路（IN-6）実装済み、WAFの認証前rate制限（IN-5）設定済み。
3. secret store / 環境へ注入する: `public-api.enabled=true`、`public-api.approval.decision-id`、
   `public-api.approval.allowed-client-cidrs`、`public-api.public-id-key`、`topology.bound-tenant-id`、
   `security.trusted-proxies`（LBのsubnet）、`integration.hub.crypto.*`。outbound側の値は触らない。
4. 全停止配備で起動し、validatorが通ることを確認する（不足時は起動しない。エラー文の項目を本書と照合する）。
5. LB/WAFの`/external-api/**`遮断（D-1）を、承認済み送信元だけを通すruleへ置き換える。
6. 承認済みclientからの疎通確認: 正常200、allow-list外からの401（`CLIENT_IP_NOT_ALLOWED`）、署名不正401。
7. runbook §1の監視を有効化し、初週は毎日`integration_hub_external_api_audit_suppressed_total`と監査件数を確認する。

### 4.2 outbound

1. 本書のoutbound欄（OUT-1〜OUT-11）を埋め、§5の署名を揃える。決定IDを採番する。
2. 前提を確認する: W3-06bの実HTTPS transport・egress制御・TLS方針（OUT-4/5/9）が独立Review PASS済み。
3. 注入する: `external-transport.enabled=true`、`external-transport.approval.decision-id`、
   `external-transport.approval.allowed-destinations`、本番用`provider.mode`、`public-api.public-id-key`、
   `topology.bound-tenant-id`、`integration.hub.crypto.*`。**`public-api.enabled`はfalseのまま**（inboundを同時に開く場合はDG-05-INが別途必要）。
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

## 6. 現時点で未充足の前提（署名しても開放できない事項）

| 項目 | 方向 | 担当batch |
|---|---|---|
| `t_external_api_audit` の保存期限列・purge（N5-P2-1の残り） | inbound | DDL lane |
| production用のclient/credential/scope発行経路（N5-P2-4） | inbound | 後続batch |
| 実HTTPS transport、egress制御、TLS方針（TLS残存項目 P2-1/P2-2/P3-2/P3-N2） | outbound | W3-06b |
| enqueue producer、subscription登録、replay入口（N5-P2-4） | outbound | W3-06b |
| runbook §1のdelivery/inbound状態metric（現状はSQL確認で代替） | 両方向 | W3-06b |

## 7. 撤回と緊急停止

- 撤回は方向ごとに行う。対象方向の`enabled=false`で再起動し、inboundはLB/WAFの遮断（D-1）を戻す。
- 撤回してもretention purgeは他方向が有効なら継続する。両方向OFFでschedulerは生成されないため、
  撤回後に残るpayload・監査の扱いはDBAが別途判断する（手動purgeまたは一時的な再有効化）。
- 承認値の変更は新しい決定IDで行い、本書の版を更新する。
