# SES Manager Pro (SES業務管理システム)

## 📌 プロジェクト概要 (Project Overview)
**SES Manager Pro**は、日本のSES（システムエンジニアリングサービス）企業向けに特化して開発された、モダンで直感的な統合業務管理システムです。

要員（エンジニア）のスキル・稼働状況の管理から、顧客・案件の管理、提案活動の進捗パイプライン（カンバン方式）、そして契約の予実管理や全社の売上・稼働率といった経営指標のダッシュボード可視化まで、SES営業およびバックオフィス業務に必要な一連のフローをフルスタックでサポートします。

本システムは、圧倒的な高級感と没入感を演出する「ダークモード×Glassmorphism（すりガラス効果）」を基調としたプレミアムUIを採用しており、日々の単調な管理業務を快適かつスタイリッシュな体験へと昇華させます。

---

## 🛠️ 技術スタック (Tech Stack)

### バックエンド (Backend)
- **Java 17+**
- **Spring Boot 3.3.x**: アプリケーションフレームワーク
- **Spring Security**: ログイン認証およびロール（権限）ベースのアクセス制御
- **MyBatis-Plus**: O/Rマッパー（ページネーション、自動タイムスタンプ機能活用）
- **H2 Database / MySQL 8.0**: リレーショナルデータベース

### フロントエンド (Frontend)
- **HTML5 / CSS3 / Vanilla JavaScript**: ピュアで高速なフロントエンドロジック（jQuery/AJAXベース）
- **Thymeleaf**: サーバーサイドテンプレートエンジン（Layout Dialectによる共通フラグメント化）
- **Bootstrap 5**: レスポンシブUIコンポーネント基盤
- **Chart.js**: ダッシュボードの売上・ステータス分析グラフ描画
- **SortableJS**: 提案管理画面でのドラッグ＆ドロップ（カンバンUI）の実装
- **Bootstrap Icons (bi)**: 一貫性のあるアイコンシステム

---

## ✨ 主な機能 (Key Features)

1. **📊 経営分析ダッシュボード (Dashboard)**
   - 全社稼働率、Bench（待機）要員数、当月予想売上、粗利率などの重要KPIをリアルタイムに集計。
   - 契約終了が30日以内に迫っている「退場予定者アラートリスト」を自動生成し、営業機会の損失を防ぎます。
   - 売上推移や要員ステータス分布をリッチなグラフ（Chart.js）で視覚化。

2. **🧑‍💻 要員（エンジニア）管理 (Engineer Management)**
   - エンジニアの基本情報、スキルセット、希望単価、稼働状況（稼動中・Bench・提案中など）の一元管理。
   - 検索・絞り込み機能と、AIマッチングの拡張を見据えたUI基盤。

3. **🏢 顧客・案件管理 (Customer & Project Management)**
   - 顧客情報および、顧客が募集している案件の管理。
   - 単価幅、リモート区分、募集ステータスなどの要件定義をデータベースで正確にリレーション管理。

4. **🚀 提案パイプライン・カンバンUI (Proposal Kanban)**
   - 「書類選考中」「一次面接」「成約」「見送り」などの提案状況を、直感的な**ドラッグ＆ドロップ可能なカンバンボード**で管理。
   - 状態を変更した際は、非同期通信（AJAX）で瞬時にデータベースへ反映。

5. **📄 契約・アサイン管理 (Contract Management)**
   - 案件に対して要員をアサインし、売上単価・原価単価・期間などの契約情報を記録。
   - ダッシュボードのKPI算出の基盤となる重要データを管理。

---

## 🎨 UI/UXのこだわり (Design Aesthetics)
- **Glassmorphism**: モーダル、ドロップダウン、カードUIにすりガラスのような美しい透け感とブラー効果を取り入れ、モダンSaaSとしての圧倒的な質感を表現。
- **Fluid Animations**: モーダル起動時のバウンス効果（cubic-bezier）や、ホバー時のマイクロインタラクションによる「触って気持ちいい」操作感。
- **Dark Theme First**: 長時間の業務でも目が疲れにくい、洗練されたダークテーマをベースに構築。

---

## 🚀 起動方法 (How to Run)

本プロジェクトにはMavenラッパー (`mvnw`) が同梱されています。ローカル環境で起動する場合は以下の手順を実行してください。

1. **環境の準備**: Java 17以上がインストールされており、`JAVA_HOME` が正しく設定されていることを確認してください。

2. **データベースの準備 (MySQL)**:
   アプリ本体はMySQLに接続します（`application.yml` の `jdbc:mysql://localhost:3306/ses_manager_db`）。**起動前に**以下を実施してください。DBが起動していないと、ログインやCRUDが「認証エラー」のように見える現象が起こるため注意してください。
   1. MySQLを起動する（未インストールの場合は導入、またはDockerで `mysql:8` を起動）。
   2. データベースを作成する: `CREATE DATABASE ses_manager_db;`
   3. 初期スキーマとマスタデータを投入する（**この順番で**実行）:
      ```sql
      -- 例: mysql -u root ses_manager_db < sql/001_create_tables.sql
      sql/001_create_tables.sql   -- テーブル定義
      sql/002_init_master_data.sql -- 初期管理者(admin/admin123)等のマスタデータ
      ```

   > 💡 **テスト実行時はMySQL不要**です。`mvn test` は `src/test/resources/application-test.yml` によりH2インメモリDB（MySQL互換モード）を使用します。

3. **プロジェクトのビルドと起動**:
   プロジェクトのルートディレクトリで以下のコマンドを実行します。
   
   **Windows (PowerShell/CMD):**
   ```cmd
   .\apache-maven-3.9.6\bin\mvn spring-boot:run
   ```
   *(※環境によっては `.\mvnw.cmd spring-boot:run` が利用可能です)*

4. **アクセス**:
   アプリケーション起動後、ブラウザで以下にアクセスしてください。
   - URL: `http://localhost:8080/`
   - ログインページ: `http://localhost:8080/login`
   - 初期ログイン情報: `admin` / `admin123`（`sql/002_init_master_data.sql` の初期データに準拠）

---

*Developed with Google Antigravity Advanced Agentic Coding.*