# 転送ヘッダー清浄化（FWD-SANITIZE）の運用前提

- 対象: 既存P1 PRE-P1-1（`X-Forwarded-Prefix` によるmenu・action権限の迂回、書込操作の監査漏れ、Portal rate limitの無効化）と P2 PRE-P2-1（remote addressの偽装）。出典は `w3-06a-nf05-35add3ac-review.md` §3。
- 位置づけ: **本番公開前に必須**。DDLなし。`deploy-runbook.md` 本体は変更せず、SREの前提条件をこの文書にまとめる。
- 実装: `com.ses.config.forwarded`（`ForwardedHeaderSanitizingFilter` / `ForwardedHeaderConfiguration` / `ForwardedHeaderProductionValidator`）、`com.ses.common.util.ClientIpResolver` / `RequestPaths` / `IpCidrMatcher`。

## 1. 何が起きていたか

`server.forward-headers-strategy: framework` でBootが登録するSpringの `ForwardedHeaderFilter` は、TCP peerを確認せずに受信した `Forwarded` / `X-Forwarded-*` を反映する。ALBは `X-Forwarded-For` / `X-Forwarded-Proto` / `X-Forwarded-Port` 以外をそのまま透過する。

- clientが `X-Forwarded-Prefix: /x` を送ると `getRequestURI()` が `/x/api/...` になる。Spring SecurityのmatcherとMVCはprefixを除いたpathで照合するのでcontrollerには到達するが、`getRequestURI()` で判定していた自前filterは判定を外していた。
  - `MenuPermissionFilter`: 営業・HR・マネージャーがm_menuとaction denyを迂回（例: 管理者・マネージャー限定の `/api/compliance/**`）。
  - `ApiAuditFilter`: `POST/PUT/DELETE` が `t_audit_log` に残らない。
  - `PortalRateLimitFilter`: Portal login・招待受諾のIP rate limitが無効。
  - 修正時に追加で確認したもの: `X-Forwarded-Prefix: /mfa` や `/css` を付けると `MfaEnforcementFilter` のallow-list（`/mfa/` 前方一致・静的資源判定）に誤一致し、MFA未完了のsessionがAPIへ到達できた。初回パスワード変更・bootstrap・break-glass・永続sessionの静的資源判定も同じ形で外れていた。
- `getRemoteAddr()` は `X-Forwarded-For` の最左（clientが書ける値）または `Forwarded: for=` になり、Portal loginのIP rate limit、Portal監査・Portal session・永続sessionのIP hash、MFA試行の送信元scopeを偽装できた。

## 2. アプリ側の対策（このbatchで実装済み）

| 項目 | 内容 |
|---|---|
| 転送ヘッダーの清浄化 | Boot標準のForwardedHeaderFilter登録を `ForwardedHeaderSanitizingFilter` に置き換えた（同じorder `HIGHEST_PRECEDENCE`、REQUEST/ASYNC/ERROR）。`X-Forwarded-Prefix`・`X-Forwarded-Host`・`X-Forwarded-Ssl`・`Forwarded` は送信元にかかわらず常に削除する。`X-Forwarded-For`・`X-Forwarded-Proto`・`X-Forwarded-Port` は TCP peer が `TRUSTED_PROXIES` に含まれるときだけ、右端1要素に絞って残す（複数行・不正値は削除） |
| 判定pathの統一 | 自前filter・break-glass判定は `getRequestURI()` ではなく `RequestPaths.pathWithinApplication`（context path除去・%復号・`;` 除去・連続スラッシュ正規化）で判定する。清浄化が無くてもprefixで判定が外れない（多層防御） |
| client IPの統一 | `ClientIpResolver` は「信頼プロキシが追記したXFF右端1 hop」だけを採用し、それ以外はTCP peerを返す。Portal rate limit・Portal監査・Portal session・永続sessionのIP hash・MFA試行の送信元scopeは全てこの結果を使う |
| 本番起動時の検査 | `ForwardedHeaderProductionValidator`（prod profile）が次の場合に起動を拒否する: `server.forward-headers-strategy` がframework以外、`TRUSTED_PROXIES` 未設定、IPv4で `/16` より広い・IPv6で `/48` より広い範囲。構文不正のCIDRは全profileで起動時に拒否する |

`forward-headers-strategy` を `native` に切り替える案は採らない。TomcatのRemoteIpValveは信頼プロキシ経由のrequestについてclientが送った `X-Forwarded-Host` を反映し、内部proxyの指定もCIDRではなく正規表現になる。またNF-05公開APIのTomcat valveとの順序（W6A-P3-6）を変えることになる。

## 3. SREの前提条件（本番公開前に必須）

### 3.1 即時措置: WAFで4種の転送ヘッダーを持つrequestを拒否する

アプリ側の清浄化とは独立に、ALB前段のWAFで次のheaderを**1つでも持つ**requestを拒否（403）する。正規の経路（ブラウザ → ALB → アプリ）ではこれらのheaderは付かない。

- `X-Forwarded-Prefix`
- `X-Forwarded-Host`
- `Forwarded`
- `X-Forwarded-Ssl`

- 値ではなく**headerの存在**で一致させる（大文字小文字を区別しない）。値の内容で判定すると表記を変えて迂回される。
- ALB自身はheaderを削除できないため、WAFが入るまではアプリ側の清浄化だけが防御になる。
- 確認（公開FQDNに対して作業端末から）: 次の4本がいずれもWAFの403になり、headerを付けない同じrequestは従来どおり応答することを確認する。

```
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Forwarded-Prefix: /x' https://<公開FQDN>/login
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Forwarded-Host: evil.example' https://<公開FQDN>/login
curl -s -o /dev/null -w '%{http_code}\n' -H 'Forwarded: for=192.0.2.1' https://<公開FQDN>/login
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Forwarded-Ssl: on' https://<公開FQDN>/login
```

### 3.2 ALBの設定

- `X-Forwarded-For` の処理modeは **append**（ALBの既定 `routing.http.xff_header_processing.mode=append`）。`preserve` にするとclientが書いた値がそのまま右端になり、`remove` にするとclient IPが常にALBになる（Portal loginのIP rate limitが全利用者で1つのkeyになる）。
- `routing.http.xff_client_port.enabled` は **false**（既定）。trueにすると右端が `IP:port` になり、アプリは不正値としてALBのIPを使う。
- HTTPS listenerで終端し、targetへの転送時に `X-Forwarded-Proto: https` と `X-Forwarded-Port: 443` が付くこと（ALBの既定動作）。
- ブラウザが送ったHostをtargetへ渡すこと（ALBの既定動作）。アプリは `X-Forwarded-Host` を読まないので、OIDCのredirect URI（`{baseUrl}/login/oauth2/code/{registrationId}`）・redirect先URLのhostはHost headerで決まる。CDN等をALBの前に置いてHostを書き換える構成にする場合は、このbatchの前提が変わるため事前に相談する。
- ALBは専用subnetに置き、アプリのtask（ECS等）のsecurity groupはALBのsecurity groupからのinboundだけを許可する。`TRUSTED_PROXIES` の範囲内にALB以外の送信元があると、その送信元はclient IPとHTTPS判定を偽装できる。

### 3.3 `TRUSTED_PROXIES` の設定方法

| 環境変数（property） | 値 |
|---|---|
| `TRUSTED_PROXIES`（`app.security.trusted-proxies`） | ALBを置いたsubnetのCIDRをカンマ区切りで指定する（例 `10.0.1.0/24,10.0.2.0/24`。dual-stackならIPv6 subnetの `/64` も追加）。単一IPも指定できるが、ALBのnode IPは変わるためsubnet単位にする |

- 既定値は無い。**未設定・空では本番は起動しない**（`本番の転送ヘッダー設定が不正なため起動できません: app.security.trusted-proxies（環境変数TRUSTED_PROXIES）にALBのsubnet CIDRを設定してください`）。dev/testの未設定は「転送ヘッダーを一切信頼しない」（fail-closed）として動く。
- IPv4で `/16` より広い（`10.0.0.0/8`、`0.0.0.0/0` 等）、IPv6で `/48` より広い指定は起動を拒否する（`...の範囲が広すぎます`）。VPC全体（`/16`）は許可されるが、ALBのsubnetだけに絞ること。
- 構文不正（`10.0.0/24`、host名、空要素、`/33` 等）は全profileで起動時に拒否する（`app.security.trusted-proxiesはIPアドレスまたはCIDR形式で指定してください: ...`）。host名はDNS解決しない。
- **範囲がALBの実IPを含まない場合**: 起動は成功するが、全requestの `X-Forwarded-Proto` が捨てられHTTPとして扱われるため、HTTPS強制のredirectが終わらず（`https://` へ302し続ける）、OIDCのredirect URIも `http://` になる。deploy直後に §3.4 の確認を必ず行う。
- NF-05公開APIの `integration.hub.security.trusted-proxies` は別の設定である（本番では公開APIはOFF）。将来公開APIを有効にする場合は両方にALBのsubnetを入れる。NF-05のTomcat valveはこの清浄化より前に転送ヘッダーの原文を捕捉するため、NF-05のsource IP判定はこの清浄化の影響を受けない。

### 3.4 deploy後の確認

1. ブラウザで公開FQDNの `/login` を開き、redirectが繰り返されずにlogin画面が出ること（`X-Forwarded-Proto` が信頼されている）。
2. OIDC loginを開始し、IdPへのredirectの `redirect_uri` が `https://<公開FQDN>/login/oauth2/code/<registrationId>` であること。
3. §3.1 のcurl 4本がWAFの403になること。
4. staging等でPortalを公開する場合: 同じ端末から `X-Forwarded-For` の値だけを毎回変えて `POST /api/portal/auth/login` を11回送り、11回目が429になること（ALBが追記した右端の送信元でrate limitされている）。

### 3.5 bootstrap中のアクセス元制限との関係

`deploy-runbook.md` §4.2 のbootstrap中のアクセス元制限は、LB/ingress（ALBのlistener rule・security group・WAF）で**TCPの送信元**に対して行う。アプリはこの制限を実装しておらず、このbatchでも変えていない。`X-Forwarded-For` の値で許可する方式は、ALBより内側では偽装を防げないため使わない。

## 4. 残るリスク・既知の制約

- 採用するのは右端1 hopだけである。ALBの前にCDN・別のproxyを足すと右端はそのproxyのIPになり、client IPの意味が変わる（Portal rate limitがproxy単位になる）。構成を変える場合は事前に見直す。
- `X-Forwarded-For` が複数行のrequestは信頼しない（client IPはALBのIPになる）。ALBが複数行をどう扱うかは未実測（W6A-P2-1と同じ論点）。正規のブラウザ経路では起きない。
- WAFのruleが入るまでの間も、アプリの清浄化と判定pathの統一で権限迂回・監査漏れ・rate limit無効化は防がれる。WAFは多層防御と、不正requestの可視化（WAF log）のために入れる。
- 監査に記録するURIは、prefix・context pathを含まず%復号したアプリ内pathになった（従来は `getRequestURI()` の原文）。
- ALBのtarget group health checkとHTTPS強制（`requiresSecure`）の関係は従来どおりで、このbatchでは変えていない。
