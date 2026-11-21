# 転送ヘッダー清浄化（FWD-SANITIZE）の運用前提

- 対象: 既存P1 PRE-P1-1（`X-Forwarded-Prefix` によるmenu・action権限の迂回、書込操作の監査漏れ、Portal rate limitの無効化）と P2 PRE-P2-1（remote addressの偽装）。出典は `w3-06a-nf05-35add3ac-review.md` §3。r2では独立復審 `fwd-sanitize-362b765d-review.md` の指摘（P2-1・P2-2・P3）を反映した。
- 位置づけ: **本番公開前に必須**。DDLなし。`deploy-runbook.md` 本体は変更せず、SREの前提条件をこの文書にまとめる。
- 実装: `com.ses.config.forwarded`（`ForwardedHeaderSanitizingFilter` / `ForwardedHeaderConfiguration` / `ForwardedHeaderProductionValidator`）、`com.ses.common.util.ClientIpResolver` / `RequestPaths` / `IpCidrMatcher`、`SecurityConfig.PLAIN_HTTP_HEALTH_PROBES`。

## 1. 何が起きていたか

`server.forward-headers-strategy: framework` でBootが登録するSpringの `ForwardedHeaderFilter` は、TCP peerを確認せずに受信した `Forwarded` / `X-Forwarded-*` を反映する。ALBは `X-Forwarded-For` / `X-Forwarded-Proto` / `X-Forwarded-Port` 以外をそのまま透過する。

- clientが `X-Forwarded-Prefix: /x` を送ると `getRequestURI()` が `/x/api/...` になる。Spring SecurityのmatcherとMVCはprefixを除いたpathで照合するのでcontrollerには到達するが、`getRequestURI()` で判定していた自前filterは判定を外していた。
  - `MenuPermissionFilter`: 営業・HR・マネージャーがm_menuとaction denyを迂回（例: 管理者・マネージャー限定の `/api/compliance/**`）。
  - `ApiAuditFilter`: `POST/PUT/DELETE` が `t_audit_log` に残らない。
  - `PortalRateLimitFilter`: Portal login・招待受諾のIP rate limitが無効。
  - 修正時に追加で確認したもの: `X-Forwarded-Prefix: /mfa` や `/css` を付けると `MfaEnforcementFilter` のallow-list（`/mfa/` 前方一致・静的資源判定）に誤一致し、MFA未完了のsessionがAPIへ到達できた。初回パスワード変更・bootstrap・break-glass・永続session・Portalの規約同意待ちの判定も同じ形で外れていた。
- **転送ヘッダーが無くても同じ迂回ができた（既存P1級。本batchで同時に封止）**: `getRequestURI()` は%エンコードも未復号のため、営業・HRが `/%61pi/<resource>/...` のように先頭segmentを符号化して送るだけで、m_menu・action deny（`payroll.view`・`contract.cost.view`・`*.delete` 等）を素通りし、更新系の監査も残らなかった。Spring Securityのfirewallは `%61` 等の非予約文字の符号化を通し、MVCは復号後のpathでcontrollerへ振り分ける。SecurityConfigの静的 `hasRole` と、service層で再認可している経路は対象外だった。判定を `RequestPaths`（復号後のアプリ内path）へ統一したことで封止し、chain級の回帰試験（`ForwardedHeaderSecurityChainTest`）と、`config/**`・`service/security/**` で `getRequestURI()` を判定に使わせないソース検査（`RequestUriDecisionGuardTest`）で再発を止める。
- `getRemoteAddr()` は `X-Forwarded-For` の最左（clientが書ける値）または `Forwarded: for=` になり、Portal loginのIP rate limit、Portal監査・Portal session・永続sessionのIP hash、MFA試行の送信元scopeを偽装できた。

## 2. アプリ側の対策（このbatchで実装済み）

| 項目 | 内容 |
|---|---|
| 転送ヘッダーの清浄化 | Boot標準のForwardedHeaderFilter登録を `ForwardedHeaderSanitizingFilter` に置き換えた（同じorder `HIGHEST_PRECEDENCE`、REQUEST/ASYNC/ERROR）。`X-Forwarded-Prefix`・`X-Forwarded-Host`・`X-Forwarded-Ssl`・`Forwarded` は送信元にかかわらず常に削除する。`X-Forwarded-For`・`X-Forwarded-Proto`・`X-Forwarded-Port` は TCP peer が `TRUSTED_PROXIES` に含まれるときだけ、右端1要素に絞って残す（複数行・不正値は削除）。header名はSpringと同じ `equalsIgnoreCase` で照合する |
| 判定pathの統一 | 自前filter・break-glass判定は `getRequestURI()` ではなく `RequestPaths.pathWithinApplication`（context path除去・%復号・`;` 除去・連続スラッシュ正規化）で判定する。清浄化が無くてもprefix・%エンコードで判定が外れない（多層防御） |
| 監査・logの表記 | 監査（`t_audit_log.uri`）と `API操作監査` 等のlogへは、復号で現れた制御文字・書式文字（ESC、bidi制御等）を `%XX` へ戻した表記（`RequestPaths.forAudit`）で書く。判定には使わない |
| client IPの統一 | `ClientIpResolver` は「信頼プロキシが追記したXFF右端1 hop」だけを採用し、それ以外はTCP peerを返す。Portal rate limit・Portal監査・Portal session・永続sessionのIP hash・MFA試行の送信元scopeは全てこの結果を使う。Portal login・招待受諾のIP単位rate limitは、IPv6を `/64` へ集約する |
| health probeのHTTP許可 | `/actuator/health`・`/actuator/health/liveness`・`/actuator/health/readiness` のGETだけは、HTTPS強制（`requiresSecure`）の対象外とした。ECSのcontainer health check（task内の127.0.0.1から）とALBのtarget group health checkは `X-Forwarded-Proto` を持たないため、HTTPS強制のままだと302になる。応答は `show-details/show-components: never` によりUP/DOWNだけ。他のactuator・`/actuator/health/**` の他のpath・画面とAPIは従来どおりHTTPSへredirectする |
| 本番起動時の検査 | `ForwardedHeaderProductionValidator`（prod profile）が次の場合に起動を拒否する: `server.forward-headers-strategy` がframework以外、`TRUSTED_PROXIES` 未設定、IPv4で `/16` より広い・IPv6で `/48` より広い範囲、IPv4がprivate address（RFC 1918の `10/8`・`172.16/12`・`192.168/16`）またはshared address（RFC 6598の `100.64/10`）に収まらない範囲（公開IPv4・`127.0.0.0/8` 等）、IPv6でloopback（`::1`）・未指定（`::`）・link-local（`fe80::/10`）・multicast（`ff00::/8`）と重なる範囲、host部にbitがあるCIDR（`10.0.1.5/24` 等）。構文不正のCIDRは全profileで起動時に拒否する |
| 判定に生のpathを使わせない検査 | `RequestUriDecisionGuardTest` が `src/main/java` 全体で `getRequestURI()`・`getServletPath()`・`getRequestURL()` の呼出しとメソッド参照を数え、許可表（表示・log・ledger用、context path考慮済み等の理由と件数付き）以外を失敗にする |

`forward-headers-strategy` を `native` に切り替える案は採らない。TomcatのRemoteIpValveは信頼プロキシ経由のrequestについてclientが送った `X-Forwarded-Host` を反映し、内部proxyの指定もCIDRではなく正規表現になる。またNF-05公開APIのTomcat valveとの順序（W6A-P3-6）を変えることになる。

health checkのために `127.0.0.1/32` を `TRUSTED_PROXIES` へ入れる案も採らない。同じtask内の任意のprocessがHTTPS判定とclient IPを偽装できるようになり、validatorもloopbackを拒否する。

## 3. SREの前提条件（本番公開前に必須）

### 3.1 即時措置: WAFで4種の転送ヘッダーを持つrequestを拒否する

アプリ側の清浄化とは独立に、ALB前段のWAFで次のheaderを**1つでも持つ**requestを拒否（403）する。正規の経路（ブラウザ → ALB → アプリ）ではこれらのheaderは付かない。

- `X-Forwarded-Prefix`
- `X-Forwarded-Host`
- `Forwarded`
- `X-Forwarded-Ssl`

- 値ではなく**headerの存在**で一致させる（大文字小文字を区別しない）。値の内容で判定すると表記を変えて迂回される。
- **最初はCOUNT modeで入れる**。日本企業のforward proxyは `Forwarded` や `X-Forwarded-Host` を付けることがある。正規の利用者のrequestが一致しないことをWAF logで確認してからBLOCKへ切り替える。アプリ側が既にこれらを削除するため、BLOCKは多層防御であり、公開の必須条件ではない。
- 確認（公開FQDNに対して作業端末から）: BLOCK切替後、次の4本がいずれもWAFの403になり、headerを付けない同じrequestは従来どおり応答することを確認する。

```
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Forwarded-Prefix: /x' https://<公開FQDN>/login
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Forwarded-Host: evil.example' https://<公開FQDN>/login
curl -s -o /dev/null -w '%{http_code}\n' -H 'Forwarded: for=192.0.2.1' https://<公開FQDN>/login
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Forwarded-Ssl: on' https://<公開FQDN>/login
```

- dual-stackでIPv6を受ける場合は、WAFのrate-based ruleも入れる。アプリのPortal login rate limitはIPv6を `/64` 単位で数えるが、複数の `/64` を持つ攻撃者には効かない。

### 3.2 ALBの設定

- `X-Forwarded-For` の処理modeは **append**（ALBの既定 `routing.http.xff_header_processing.mode=append`）。`preserve` にするとclientが書いた値がそのまま右端になり、`remove` にするとclient IPが常にALBになる（Portal loginのIP rate limitが全利用者で1つのkeyになる）。
- `routing.http.xff_client_port.enabled` は **false**（既定）。trueにすると右端が `IP:port` になり、アプリは不正値としてALBのIPを使う。
- `routing.http.drop_invalid_header_fields.enabled=true` の有効化を検討する（token外の文字を含むheader名を落とす）。
- HTTPS listenerで終端し、targetへの転送時に `X-Forwarded-Proto: https` と `X-Forwarded-Port: 443` が付くこと（ALBの既定動作）。staging等で、HTTPS listenerへclientから `X-Forwarded-Proto: http` / `X-Forwarded-Port: 80` を付けて送り、ALBがclientの値を上書きすることを実測する（上書きされない場合はアプリの右端1要素がclientの値になる）。
- **HTTP:80 listenerはHTTPSへのredirectだけにし、targetへforwardしない**。
- **listener ruleは公開FQDNのHost headerだけをforwardし、既定動作は固定の404にする**。アプリは `X-Forwarded-Host` を読まないため、OIDCのredirect URI（`{baseUrl}/login/oauth2/code/{registrationId}`）・redirect先URLのhostはHost headerだけで決まる。ブラウザが送ったHostをtargetへ渡すこと（ALBの既定動作）。CDN等をALBの前に置いてHostを書き換える構成にする場合は、このbatchの前提が変わるため事前に相談する。
- ALBは専用subnetに置き、アプリのtask（ECS等）のsecurity groupはALBのsecurity groupからのinboundだけを許可する。`TRUSTED_PROXIES` の範囲内にALB以外の送信元があると、その送信元はclient IPとHTTPS判定を偽装できる。
- target groupのhealth checkは `/actuator/health/readiness`・`matcher=200`（`infra/ecs/task-definition.prod.json` の `_offlineAcceptanceNotes`）。health probe 3pathはHTTPのまま200/503を返すため、ALBのhealth checkが `X-Forwarded-Proto` を送らなくてもtargetはhealthyになれる（本batch以前は302で、`matcher=200` ではhealthyにならなかった）。

### 3.3 `TRUSTED_PROXIES` の設定方法

| 環境変数（property） | 値 |
|---|---|
| `TRUSTED_PROXIES`（`app.security.trusted-proxies`） | ALBを置いたsubnetのCIDRをカンマ区切りで指定する（例 `10.0.1.0/24,10.0.2.0/24`。dual-stackならIPv6 subnetの `/64` も追加）。単一IPも指定できるが、ALBのnode IPは変わるためsubnet単位にする |

- task定義（`infra/ecs/task-definition.prod.json`）には `TRUSTED_PROXIES=ALB_SUBNET_CIDRS_PLACEHOLDER` を置いてある。**置換しないまま配備するとapplicationは起動しない**（構文検査で拒否）。
- 既定値は無い。**未設定・空では本番は起動しない**（`本番の転送ヘッダー設定が不正なため起動できません: app.security.trusted-proxies（環境変数TRUSTED_PROXIES）にALBのsubnet CIDRを設定してください`）。dev/testの未設定は「転送ヘッダーを一切信頼しない」（fail-closed）として動く。
- IPv4で `/16` より広い（`10.0.0.0/8`、`0.0.0.0/0` 等）、IPv6で `/48` より広い指定は起動を拒否する（`...の範囲が広すぎます`）。VPC全体（`/16`）は許可されるが、ALBのsubnetだけに絞ること。
- IPv4はprivate address（`10/8`・`172.16/12`・`192.168/16`）またはshared address（`100.64/10`）だけを許す（`...private address（10/8・172.16/12・192.168/16）またはshared address（100.64/10）のALB subnetに限ります`）。ALBのnodeはsubnetのIPv4からtargetへ接続するため、ALB subnetがこの範囲にあれば公開IPv4や `127.0.0.1` を信頼プロキシに入れる理由は無い。
- **前提: 本番のVPCとALB subnetはRFC 1918または `100.64/10` で設計する**。AWSはVPCに公開routableなCIDRや `198.19.0.0/16` 等（secondary CIDRを含む）も使わせるが、それらにALB subnetを置く構成は**本batchの対象外**で、validatorが起動を拒否する。採用する場合はvalidator（`ForwardedHeaderProductionValidator.ALLOWED_IPV4_BLOCKS`）の変更とその復審が必要になる。配備前に §3.4 の0でVPC CIDRを確認する。
- IPv6はVPCに割り当たる範囲自体がglobal unicastのため、許可する種別では絞らない。ただしIPv4と同じく、ALBになり得ないloopback（`::1`）・未指定（`::`）・link-local（`fe80::/10`）・multicast（`ff00::/8`）と重なる範囲は拒否する（`...のIPv6にloopback（::1）・未指定（::）・link-local（fe80::/10）・multicast（ff00::/8）は指定できません`）。
- host部にbitがあるCIDR（`10.0.1.5/24` 等）は拒否する（`...network address（host部が0）のCIDRで指定してください`）。
- 構文不正（`10.0.0/24`、host名、空要素、末尾のカンマ、zone ID、空白区切り、`/33` 等）は**全profile**で起動時に拒否する（`app.security.trusted-proxiesはIPアドレスまたはCIDR形式で指定してください: ...`）。host名はDNS解決しない。本batchを入れる前に、dev・staging・CIで既に設定している値を確認すること。
- **範囲がALBの実IPを含まない場合**: 起動は成功するが、全requestの `X-Forwarded-Proto` が捨てられHTTPとして扱われるため、HTTPS強制のredirectが終わらず（`https://` へ302し続ける）、OIDCのredirect URIも `http://` になる。deploy直後に §3.4 の確認を必ず行う。
- **prod以外のprofileでもALBの後ろに置く環境**（staging・demo等）は `TRUSTED_PROXIES` を必ず設定する。これらの環境にはvalidatorが無いため、未設定でも起動し、HTTPS判定とOIDCの `redirect_uri`（`http://` になる）が黙って壊れる。
- 本番では `spring.main.lazy-initialization=true` にしない。validatorは `@PostConstruct` で検査するため、lazy初期化では検査されない（他の起動時validatorと同じ前提）。
- NF-05公開APIの `integration.hub.security.trusted-proxies` は別の設定である（本番では公開APIはOFF）。将来公開APIを有効にする場合は両方にALBのsubnetを入れる。W3-06a（`35add3ac`）の統合後は、NF-05のTomcat valveがこの清浄化より前に転送ヘッダーの原文を捕捉するため、NF-05のsource IP判定はこの清浄化の影響を受けない。W3-06a統合前のNF-05 resolverはservletの `getRemoteAddr()` を読むため、清浄化後の値（信頼プロキシ経由なら右端1 hop、それ以外はTCP peer）を使う（従来の最左より安全側）。

### 3.4 deploy前・deploy後の確認

0. **配備前: VPC CIDRとALB subnetの確認**。本番VPCのprimary・secondary CIDRと、ALBを置くsubnetのIPv4 CIDRが全てRFC 1918（`10/8`・`172.16/12`・`192.168/16`）または `100.64/10` に収まること、`TRUSTED_PROXIES` に入れる値がそのALB subnetのnetwork address（host部が0）であることを確認する。収まらない場合は配備を止め、§3.3の前提の見直し（validatorの変更）を依頼する。
1. ブラウザで公開FQDNの `/login` を開き、redirectが繰り返されずにlogin画面が出ること（`X-Forwarded-Proto` が信頼されている）。
2. OIDC loginを開始し、IdPへのredirectの `redirect_uri` が `https://<公開FQDN>/login/oauth2/code/<registrationId>` であること。client registration は起動設定から作られるため redirect の URL 自体は deploy 直後でも見られるが、login を最後まで通すには IdP の登録と外部 identity link が要る。初回配備は bootstrap 中のため、`deploy-runbook.md` §5.3 の5の最初の OIDC login でまとめて確かめる。
3. §3.1 のcurl 4本がWAFの403になること（BLOCK切替後）。
4. **ECSのcontainer health check**: ECS Exec（または `docker exec`）でtask内から `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health/liveness` を実行し、`200` であること（`302` ならHTTPS強制の例外が効いていない）。task定義のhealth checkは `%{http_code}` を200と比較する形で、`curl -f`（302を成功と扱う）や `X-Forwarded-Proto` 付きの書き方に戻さない（`ContainerHealthCheckCommandGuardTest` が検査する）。
5. ALBのtarget groupでtargetがhealthyになること（readiness・`matcher=200`）。
（6・7 は staging で行う項目で、本番の配備日には行わない。）

6. staging等でPortalを公開する場合: 同じ端末から `X-Forwarded-For` の値だけを毎回変えて `POST /api/portal/auth/login` を11回送り、11回目が429になること（ALBが追記した右端の送信元でrate limitされている）。
7. **staging等で複数行のXFFを実測する**: `X-Forwarded-For` を2行に分けて送り、Portal監査等で解決されたIPを確認する。通常の利用者のrequestでALBが複数行を生成する場合は全利用者がALBのIPを共有するkeyになるため、方針を見直す。

### 3.5 bootstrap中のアクセス元制限との関係

`deploy-runbook.md` §4.2 のbootstrap中のアクセス元制限は、LB/ingress（ALBのlistener rule・security group・WAF）で**TCPの送信元**に対して行う。アプリはこの制限を実装しておらず、このbatchでも変えていない。`X-Forwarded-For` の値で許可する方式は、ALBより内側では偽装を防げないため使わない。

### 3.6 VPC内からのPrometheus scrape

`/actuator/prometheus` はHTTPS強制のままで、task（ECS）を直接HTTPでscrapeすると302になる。scrape経路は次のどちらかに決める: ALB経由のHTTPSでscrapeする、または同じtask内のsidecarからscrapeする（この場合も `127.0.0.1` を `TRUSTED_PROXIES` には入れない。sidecar経路の扱いは別途決める）。

## 4. 残るリスク・既知の制約

- 採用するのは右端1 hopだけである。ALBの前にCDN・別のproxyを足すと右端はそのproxyのIPになり、client IPの意味が変わる（Portal rate limitがproxy単位になる）。構成を変える場合は事前に見直す。
- `X-Forwarded-For` が複数行のrequestは信頼しない（client IPはALBのIPになる）。自分で2行以上を送るrequestだけがALBのIPのkeyを共有し、通常の利用者は影響を受けない。W3-06aのNF-05 resolver（`ExternalApiSourceIpResolver`）はRFC 7230に従い複数行をカンマで連結して右端を採るため、**両者の方針は異なる**。ALBが複数行をどう扱うかは未実測（W6A-P2-1と同じ論点、§3.4の7）。
- WAFのruleが入るまでの間も、アプリの清浄化と判定pathの統一で権限迂回・監査漏れ・rate limit無効化は防がれる。WAFは多層防御と、不正requestの可視化（WAF log）のために入れる。
- 監査に記録するURIは、prefix・context pathを含まず%復号したアプリ内path（制御文字・書式文字は `%XX` へ戻す）になった（従来は `getRequestURI()` の原文）。
- Portal login rate limit（既定10回/分）は、これまでの偽装可能な値ではなく実際の出口IP（IPv6は `/64`）で数える。NATを共有する取引先では正規の利用者でも429になり得るため、問い合わせ対応の手順に記載し、必要なら `app.portal.rate-limit.login-per-minute` を調整する。
- `config/integrationhub/**` のNF-05 filterは `getRequestURI()` で外部APIか判定している（W3-06a laneで扱い本batchでは変更しない）。prefixは清浄化で除去済みで、%エンコード変体はchainのsecurityMatcherと `authenticated/denyAll` で401になる（fail-closed）。
- ALBのtarget group health checkは、health probe 3pathのHTTP許可で200を受け取れるようになった。health probe以外のactuatorは従来どおりHTTPS強制である（§3.6）。
