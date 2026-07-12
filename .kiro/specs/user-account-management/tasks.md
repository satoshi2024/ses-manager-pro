# Tasks

- [x] 1. `m_menu` / `t_role_menu` テーブルをDDL・シードデータに追加する
  - Objective: メニュー権限管理の基盤となるテーブルとロールごとの初期権限データを用意する
  - 実装: `sql/001_create_tables.sql` にテーブル定義、`sql/002_init_master_data.sql` にメニュー9件＋ロール別初期マッピングを追加
  - Test要件: なし（DDLのみ、`mvn test` が既存通り通ることを確認）
  - Demo: `sql/001_create_tables.sql` → `002_init_master_data.sql` をMySQLで実行し、`SELECT * FROM t_role_menu` で管理者が全メニュー、他ロールがuser以外を持つことを確認

- [x] 2. `Menu`/`RoleMenu` エンティティ・マッパー・サービスを実装する
  - Objective: メニュー権限データへのアクセス層を用意する
  - 実装: `entity/Menu.java`, `entity/RoleMenu.java`, `mapper/MenuMapper.java`, `mapper/RoleMenuMapper.java`（`selectMenuKeysByRole`）, `service/RoleMenuService`, `service/impl/RoleMenuServiceImpl.java`
  - Test要件: `mvn compile` が通ること
  - Demo: 該当なし（内部実装）

- [x] 3. ユーザーCRUD API・画面を実装する
  - Objective: 管理者がアプリ上でアカウントを増減・編集できるようにする
  - 実装: `service/SysUserService`, `service/impl/SysUserServiceImpl.java`, `controller/api/UserApiController.java`（CRUD、ステータス切替、自分自身操作ガード）、`controller/page/UserPageController.java`, `templates/user/list.html`, `static/js/modules/user.js`
  - Test要件: 手動で登録・編集・無効化・削除・自分自身操作拒否を確認
  - Demo: 管理者でログインし `/user/list` から新規アカウント（例: role=営業）を作成、編集、無効化、削除できることを確認。ログイン中の管理者自身を削除/無効化しようとするとエラーになることを確認

- [x] 4. ロール別メニュー権限APIと設定UIを実装する
  - Objective: 管理者がロールごとのメニュー可視性を設定できるようにする
  - 実装: `controller/api/RoleMenuApiController.java`（メニュー一覧、ロール別取得・更新）、`templates/user/list.html` の権限タブ、`static/js/modules/user.js` の該当関数
  - Test要件: 手動でロールを切り替えてチェックボックスの表示・保存を確認
  - Demo: 「ロール別メニュー権限」タブで「営業」を選択し、いくつかのメニューのチェックを外して保存。再度開き直して変更が反映されていることを確認

- [x] 5. サイドバー・SecurityConfig・MenuPermissionFilterでアクセス制御を反映する
  - Objective: メニュー非表示だけでなく、直接URLアクセスも防ぐ。ユーザー管理は管理者専用にする
  - 実装: `config/GlobalControllerAdvice.java`（`allowedMenus`注入）、`templates/layout/sidebar.html`（条件表示）、`config/MenuPermissionFilter.java`（新規）、`config/SecurityConfig.java`（管理者専用ルート、フィルタ登録、パスワードエンコーダーのprofile分岐）
  - Test要件: `mvn test` で既存テストが壊れないこと（`@WebMvcTest`スライス・`MobileResponsiveLayoutTest`含む）
  - Demo: 営業ロールのアカウントでログインし、サイドバーに「ユーザー管理」が出ないこと、`/user/list`に直接アクセスすると弾かれることを確認。管理者でログインした場合は正常にアクセスできることを確認
