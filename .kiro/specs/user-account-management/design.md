# Design Document

## Overview

既存の `Engineer`/`Customer` CRUD パターン（Page Controller + REST API Controller + `IService` のみのService + `BaseMapper` のみのMapper + Thymeleaf一覧画面 + jQueryモジュールJS）を踏襲し、`sys_user` に対するCRUD機能と、新設する `m_menu` / `t_role_menu` によるロール別メニュー権限機能を追加する。

## Data Model

- `m_menu` (新規): メニュー識別子 (`menu_key`)、表示名、画面アクセス制御対象のURLプレフィックス (`path_prefix`)、APIアクセス制御対象のURLプレフィックス (`api_prefix`)、表示順を保持するマスタテーブル。`path_prefix`（例 `/engineer`）と `api_prefix`（例 `/api/engineers`）の両方を持つことで、画面遷移だけでなく対応するAJAX API呼び出しも同一メニュー権限で制御する。`/api/skills`・`/api/notifications` のような複数画面から共有されるエンドポイントは、単一メニューに紐づかないため意図的に未登録（＝メニュー権限による遮断対象外）とする。
- `t_role_menu` (新規): `role` × `menu_id` の多対多を表す中間テーブル（`sys_user.role` の値をそのまま `role` 列に保持し、正規化はしない — 既存の `sys_user.role` ENUM設計に合わせる）。

## Components

### バックエンド

- `entity/Menu.java`, `entity/RoleMenu.java`: 上記テーブルに対応するエンティティ。
- `mapper/MenuMapper.java`: `BaseMapper<Menu>` のみ。
- `mapper/RoleMenuMapper.java`: `BaseMapper<RoleMenu>` + `selectMenuKeysByRole(role)`（ロールが許可されたメニューキー一覧を1クエリで取得）。
- `service/SysUserService` + `impl/SysUserServiceImpl`: `IService<SysUser>` のみ（既存の `EngineerService` と同型）。
- `service/RoleMenuService` + `impl/RoleMenuServiceImpl`: `IService<RoleMenu>` + `getMenuKeysByRole(role)`。
- `controller/api/UserApiController` (`/api/users`): CRUD + ステータス切替 (`PUT /{id}/status`)。パスワードは `PasswordEncoder` 経由でエンコードしてから保存。登録・更新時にログインIDの重複を事前チェック。`Authentication` から現在ユーザーを引き、自分自身の削除/無効化、および自分自身のロール変更（自己降格によるロックアウト防止）をガードする。
- `controller/api/RoleMenuApiController` (`/api/role-menus`): 全メニュー一覧取得、ロール別許可メニュー取得・更新（置き換え方式）。
- `controller/page/UserPageController` (`/user/list`): ビュー名を返すのみ。
- `config/GlobalControllerAdvice`: 既存の `currentUri` に加え、ログイン中ユーザーのロールから `allowedMenus`（許可メニューキー一覧）を全ページのモデルに注入。`RoleMenuService` は `ObjectProvider` 経由の任意依存とし、テストスライスでBeanが存在しない場合は空リストにフォールバックする。
- `config/MenuPermissionFilter`: `OncePerRequestFilter`。リクエストURIと `m_menu.path_prefix` / `api_prefix` の前方一致（一致長が最長のメニューを採用）で対象メニューを特定し、ログインユーザーのロールが許可されていなければ403を返す。`管理者` ロールは全メニューにアクセス可能な特権ロールとして常に素通しし、権限設定で自ロールのメニューを外しても管理画面から締め出されない（ロックアウト防止）。`MenuMapper`/`RoleMenuService` も `ObjectProvider` 経由の任意依存とし、取得できない場合・クエリ失敗時はアクセスを許可してフォールバックする（テストスライスでの巻き添え破壊を防止）。Servletコンテナへの自動フィルタ登録は無効化し、`SecurityConfig` 内で `addFilterAfter(..., UsernamePasswordAuthenticationFilter.class)` により明示的にSpring Securityのチェーンへ組み込む。
- `config/SecurityConfig`:
  - `/user/**`, `/api/users/**`, `/api/role-menus/**` を `hasRole("管理者")` に限定。
  - `passwordEncoder()` を `@Profile("!prod")`（`NoOpPasswordEncoder`）と `@Profile("prod")`（`BCryptPasswordEncoder`）の2Beanに分割。

### フロントエンド

- `templates/user/list.html`: 「アカウント」「ロール別メニュー権限」の2タブ構成。アカウントタブは既存の `customer/list.html` と同型（検索フォーム＋テーブル＋登録モーダル）。権限タブはロール選択セレクト＋メニューのチェックボックス一覧。
- `static/js/modules/user.js`: `customer.js` と同型のCRUD一式に加え、`loadMenus()`/`loadRoleMenus()`/`saveRoleMenus()` を追加。
- `templates/layout/sidebar.html`: 各メニュー項目の `<li>` に `th:if="${allowedMenus.contains('xxx')}"` を追加。「ユーザー管理」メニューを `user` キーで新設。

## シードデータ

`002_init_master_data.sql` に `m_menu` の9件（dashboard, engineer, customer, project, proposal, contract, ai, email, user）と、管理者=全メニュー、営業/HR/マネージャー=user以外全メニュー、という初期 `t_role_menu` を投入する。

## テスト方針

既存のテストスイート（特に `@WebMvcTest` スライスと `MobileResponsiveLayoutTest` のような `@SpringBootTest` を用いたページレンダリングテスト）は、`m_menu`/`t_role_menu` を持たないH2スキーマで動くため、新設Beanへの依存は全て `ObjectProvider` 経由の任意依存＋例外握りつぶしでフォールバックさせ、既存テストを壊さないことを最優先とする。
