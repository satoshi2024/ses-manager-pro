# 本番配備runbookへの追記候補（W3-07 HYG1）

- 状態: **草案（DOC-FINALで `deploy-runbook.md` へ統合する）**。本書は手順の正本ではない。正本は `deploy-runbook.md` で、本書の内容は統合されるまで運用手順として扱わない。
- 出典: D1独立レビュー（`637a9c6a`）のN-2・N-4・N-9・N-11（いずれもP3、本番投入前にrunbookへ補うよう勧められた項目）。あわせて、同レビューN-1を本batchでコード修正したことに伴う運用上の注意を§1に記す。
- 節番号（§5.5等）は、本書作成時点の `deploy-runbook.md` のもの。統合時に番号がずれていれば読み替える。
- `deploy-runbook.md` の版数・件数行はDDL laneが保持するため、本batchでは同書を変更していない。

## 1. break-glass usernameは大文字小文字まで一致させる（N-1。§5.5の1・2、§5.7の手順6へ追記）

- 実装の変更（本batch）: `ProductionSecurityConfigurationValidator` は、`BREAK_GLASS_USERNAMES` の各値が `sys_user.username` と大文字小文字まで完全一致することを要求する。一致しなければ起動しない（`break-glass usernameは有効な管理者ユーザーのusername（大文字小文字も一致）でなければなりません: <設定値>`）。
- 理由: `sys_user` の照合順序（`utf8mb4_unicode_ci`）は大文字小文字を区別しないため、従来の検査は `Admin` のような表記違いも通していた。一方、実行時のbreak-glass判定は区別する。表記違いのまま起動すると、DBの表記で入力したlocal loginは拒否されてbreak-glassが使えず（IdP障害時に初めて判明する）、設定の表記で入力するとincidentに束縛されずMFAも要求されない通常の管理者sessionになり得た。
- runbookへの追記案: 「`BREAK_GLASS_USERNAMES` には `sys_user.username` をそのままの表記で設定する。設定前に次で確認する」
  ```sql
  -- 設定する2件がそのままの表記で存在すること（2行）。照合順序を区別ありに切り替えて比較する
  SELECT username, role, status FROM sys_user
   WHERE username COLLATE utf8mb4_bin IN ('admin', 'bg-second') AND deleted_flag = 0;
  ```

## 2. OIDC管理者の人数の事前検知（N-2。§5.6へ追記）

- 問題: break-glass username以外でOIDCでログインできる有効な管理者が3名未満になっても（退職・無効化・role変更・外部identity linkの隔離・providerの無効化）、検知されるのは**次の再起動の起動時検査**だけである。計画外の再起動（OOM・host障害）で初めて判明すると、break-glass incidentを起票・承認できず、復旧は「再開ticket→承認→SRE」（§5.7）かDBAになる。
- 追記案（運用、必須）: 次の集計を**毎日**と、計画した再起動の前、管理者の無効化・削除・role変更・link隔離・providerの変更の後に実行し、3未満ならwarningのアラートを出す。条件は起動時検査（`ProductionSecurityConfigurationValidator.validateOidcAdministrators`、`UserExternalIdentityMapper.selectApprovedActiveUsernamesByRole`、`IdentityProviderMapper.selectEnabledByTenantAndIssuer`）と同じにしてある。
  ```sql
  -- <tenant> は OIDC_TENANT_ID、<issuer> は OIDC_ISSUER_URI、<bg1>/<bg2> は BREAK_GLASS_USERNAMES の2件
  SELECT COUNT(DISTINCT u.username) AS oidc_admins
    FROM t_user_external_identity x
    JOIN sys_user u ON u.id = x.user_id
    JOIN m_identity_provider p ON p.id = x.provider_id
   WHERE p.tenant_id = '<tenant>' AND p.issuer_uri = '<issuer>'
     AND p.enabled = 1 AND p.deleted_flag = 0 AND p.provider_type = 'OIDC'
     AND x.tenant_id = '<tenant>' AND x.review_status = 'APPROVED' AND x.deleted_flag = 0
     AND u.deleted_flag = 0 AND u.status = 1 AND u.role = '管理者'
     AND u.username NOT IN ('<bg1>', '<bg2>');   -- 3以上であること
  ```
  - 起動時検査は `sys_user.tenant_id` を見ない（D1 review N-6）ため、上のSQLも見ていない。多tenantのDBでは、`InternalTenantContextFilter` により実際には使えない管理者まで数え得る。単一tenant（`OIDC_TENANT_ID=default`）の初回本番では差は出ない。
  - break-glass usernameの除外は、§1の修正後は設定値とDBの表記が一致しているため、SQLの照合順序の違い（区別なし）による差は出ない。
- 追記案（運用）: §5.6の「3名未満にしない」の手順に「無効化・role変更の前に上のSQLで後任を含めて4名以上いることを確かめる」を加える。
- 将来のコード対策（本batchでは実装しない）: ユーザー管理画面で管理者の無効化・削除・role変更により3名未満になる場合のWARNまたはguard。

## 3. `BOOTSTRAP_REOPENED` は「再開の試行」を表す（N-4。§5.6・§5.7の手順4へ追記）

- 事実: `BOOTSTRAP_REOPENED` の必須監査と、WARN log `[bootstrap] 完了済みのbootstrapを承認ticket ... で再開します` および `[bootstrap] 期限付きbootstrap modeが有効です（期限: ...）` は、いずれも `ProductionBootstrapMode` の初期化時（bean生成中）に出る。起動時schema guard（`ProductionSchemaVersionGuard`、全singleton生成後）と `ProductionSecurityConfigurationValidator`（起動完了直前）は**その後**に動く。
- 帰結: ticketが正しくても、その後に起動が失敗すると「再開」の監査とlogだけが残り、アプリは起動していない。例:
  - DBがartifactとずれていてschema guardが起動を止めた。
  - `BREAK_GLASS_USERNAMES` を空にし忘れ、validatorが `bootstrap中はbreak-glass usernameを設定しないでください` で止めた。
  - 監査表の列がartifactのentityより少なく、監査のINSERT自体が失敗した。この場合の拒否文言は `bootstrap再開の必須監査（BOOTSTRAP_REOPENED）を永続化できないため、bootstrap modeは起動しません` になり、真の原因（schemaの不一致）はcause chainにしか出ない。
- 追記案: 「`BOOTSTRAP_REOPENED` とbootstrapのWARN logは再開の**試行**を示す。実際に再開したかは、同じ起動でreadinessがUPになり、起動失敗（`APPLICATION FAILED TO START`、schema guard・validatorの例外）が出ていないことで判断する。WARN logだけでは判断しない」。アラートの扱い（承認済みticketが無ければsecurity incident）は変えない。
- 追記案: 「拒否文言が『監査を永続化できない』だった場合は、先に§3.4の適用結果（`flyway_schema_history` とschema guardのlog）を確認する」。

## 4. `admin` 口座と、break-glass保持者の交代（N-9。§5.0・§5.6・§5.8へ追記）

### 4.1 `admin` を削除・改名・無効化・role変更しない

- 事実: bootstrapのlocal login（`ProductionBootstrapMode.isBootstrapLoginAllowed`）と起動時検査（`validateBootstrap`）は、username `admin`・role `管理者`・有効（`status = 1`）の口座だけを初期管理者として認める。
- 帰結: `admin` を論理削除・改名・無効化・role変更すると、bootstrapの再開（§5.7）でも誰もlocal loginできない。break-glassも使えない状態でこれが起きると、復旧はDBAによる口座の復元だけになる。ユーザー管理の既存guardは本人による自分自身の削除・無効化・role変更だけを止め、他の管理者による操作は止めない。
- 追記案（§5.0・§5.6）: 「`admin` は削除・改名・無効化・role変更しない（break-glass管理者Aとして使い続ける）」。定期確認のSQL:
  ```sql
  SELECT username, role, status, deleted_flag FROM sys_user
   WHERE username COLLATE utf8mb4_bin = 'admin';   -- 管理者 / 1 / 0 の1行
  ```
- 将来のコード対策（本batchでは実装しない）: `admin` に対する削除・改名・無効化・role変更をserver側で拒否するguard。

### 4.2 break-glass保持者の交代（例: Bの退職）

承認済みの変更ticket（§5.7の承認flow）の下で行う。既存の手順の組み合わせで、新しいcode経路は使わない。案Aを推奨する。どちらの案もOwner/Securityの確認待ちの草案である。

- **案A（後任用の新しい口座を作り、bootstrap再開の作業枠で準備する）**: §5.3〜§5.5と同じ経路を使う。
  1. 通常運用中に、OIDC管理者（C〜E）のsessionで後任の口座（例 `bg-third`、role `管理者`）を作成する。初期パスワードはSecurityが生成して後任だけに渡す。続けて後任のIdPの `sub` で外部identity linkを登録・承認する（§5.3の4と同じAPI）。
  2. §5.7の承認flowでbootstrapを再開する（`BREAK_GLASS_USERNAMES` を空にする）。
  3. 後任がOIDCでログインし、`/mfa/setup` でTOTPを登録し、local passwordを本人が変更する（§5.4の1・2）。
  4. OIDC管理者のsessionで旧Bの口座を無効化し、`POST /api/security/mfa/<旧Bのsys_user.id>/reset` でTOTPとsessionを解除する。旧Bの外部identity linkは、DBAが §5.8 の `review_status = 'QUARANTINED'` のSQLで隔離する。
  5. Securityが§5.4の3のSQLで、TOTPの登録者が `admin` と後任だけになったことを確かめる。
  6. `SECURITY_BOOTSTRAP_*` を外し、`BREAK_GLASS_USERNAMES=admin,bg-third`（§1のとおりDBの表記どおり）で再起動し、§5.5の3の完全な起動検査が通ることを確かめる。
  7. Securityが§5.6の監査SQLで、期間中の操作がticketの範囲と一致することを確かめてticketを閉じる。
- **案B（同じ口座を後任へ引き継ぐ）**: §5.8「bootstrap終了後にbreak-glass管理者がTOTPとrecovery codeを両方失った場合」と同じ経路を使う。
  1. OIDC管理者のsessionで、ユーザー管理画面から旧Bの口座（`bg-second`）に新しいパスワード（Securityが生成し後任だけに渡す）を設定し、`POST /api/security/mfa/<id>/reset` でTOTPとsessionを解除する。
  2. 同じ作業枠でbreak-glass incident（起票C・承認D/E）を有効化し、後任がbreak-glass loginして `/mfa/setup` で登録し、local passwordを本人が変更する。resetから再登録までの間に再起動すると、起動時検査（MFA enrollment完了済み）で止まる。
  3. 注意: incidentの起票はIdP障害の確認（`idpOutageConfirmed`）を必須とする。IdP障害が無い交代でこの経路を使うのは手続上の例外であり、Owner/Securityが事前に認め、incidentの理由欄にその旨を記す。また、旧Bの外部identity linkが残る場合は案Aの4と同じく隔離する。

## 5. 完了markerの確認SQLの補足（N-11。§5.5の4へ追記）

- 事実: 完了markerは「必須監査 `BOOTSTRAP_COMPLETED` を残す → `m_system_config` へINSERTする」の順で書かれる（`ProductionBootstrapCompletionLatch.markCompletedIfAbsent`）。同時に2台をbootstrap無効で起動すると、両方が監査を残し、INSERTは片方だけが成功する（もう片方は一意制約違反として何もしない）。
- 追記案: §5.5の4の「`BOOTSTRAP_COMPLETED` … 1行」を「全停止・単一instance起動なら1行。同時に起動した場合は2行になり得る（markerが1行あれば問題ない）」に改める。
- 追記案: 監査とmarkerの対応を次で確かめる。
  ```sql
  SELECT (SELECT COUNT(*) FROM t_audit_log WHERE application_code = 'BOOTSTRAP_COMPLETED') AS completed_audits,
         (SELECT COUNT(*) FROM m_system_config WHERE config_key = 'security.bootstrap.completed-at') AS marker_rows;
  ```

  | completed_audits | marker_rows | 意味と対応 |
  |---|---|---|
  | 1以上 | 1 | 正常（2行以上は同時起動・再書込の痕跡） |
  | 1以上 | 0 | **書込の中断**（監査の後、INSERTの前に停止した）。bootstrapを無効にしたまま再起動してmarkerを書かせ、上のSQLで `marker_rows = 1` を確かめる。それまではbootstrapを有効にして起動しない（markerが無いため、承認ticket無しで初回bootstrapとして扱われる） |
  | 0 | 1 | 想定外（アプリ以外がmarkerを書いた疑い）。Securityが§5.7末尾のDB監査logを確認する |
  | 0 | 0 | 初回bootstrapは未完了 |

## 6. DOC-FINALへの申し送り（`deploy-runbook.md` 内の中国語由来の語）

`deploy-runbook.md` は本batchで変更できないため、統合時に次の語を日本語の表現へ改めることを提案する（X3-3と同じ方針）。

| 現在の語 | 箇所（本書作成時点の行） | 改め方の例 |
|---|---|---|
| 集成候補 | 4行目 | 統合候補 |
| 部署-1〜部署-3（決定ID） | 8〜10行目 | 配備-1〜配備-3（決定IDを変える場合は `launch-decisions` 側の表記と揃える） |
| 時区 | 11・40・220・221・252・453・454行目 | タイムゾーン |
| 改密 | 253・419行目 | パスワード変更 |
