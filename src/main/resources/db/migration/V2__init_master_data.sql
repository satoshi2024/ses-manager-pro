-- ============================================================
-- SES Manager Pro - 初期マスタデータ投入
-- MySQL 8.0+
-- ファイル: 002_init_master_data.sql
-- 説明: システム初期データの投入スクリプト
-- ============================================================


-- ============================================================
-- 1. sys_user - 管理者ユーザー
-- ============================================================
INSERT INTO sys_user (username, password, real_name, role, email, status)
VALUES (
  'admin',
  'admin123',
  'システム管理者',
  '管理者',
  'admin@ses.local',
  1
);


-- ============================================================
-- 1b. m_menu / t_role_menu - メニュー権限マスタ
-- ============================================================
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
  ('dashboard', 'ダッシュボード',       '/dashboard',      '/api/dashboard',       1),
  ('engineer',  '要員管理',             '/engineer',       '/api/engineers',       2),
  ('customer',  '顧客管理',             '/customer',       '/api/customers',       3),
  ('project',   '案件管理',             '/project',        '/api/projects',        4),
  ('proposal',  '提案管理',             '/proposal',       '/api/proposals',       5),
  ('contract',  '契約管理',             '/contract',       '/api/contracts',       6),
  ('ai',        'AI機能',               '/ai',             '/api/ai',              7),
  ('email',     'メールテンプレート',   '/email/template', '/api/email-templates', 8),
  ('user',      'ユーザー管理',         '/user',           '/api/users',           9);

-- 管理者: 全メニュー
INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu;

-- 営業/HR/マネージャー: ユーザー管理以外の全メニュー
INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM m_menu m
CROSS JOIN (SELECT '営業' AS role UNION ALL SELECT 'HR' UNION ALL SELECT 'マネージャー') r
WHERE m.menu_key <> 'user';


-- ============================================================
-- 2. m_skill_tag - スキルタグマスタ
-- ============================================================

-- ---------- 言語 ----------
INSERT INTO m_skill_tag (skill_name, category) VALUES
  ('Java',       '言語'),
  ('Python',     '言語'),
  ('JavaScript', '言語'),
  ('TypeScript', '言語'),
  ('C#',         '言語'),
  ('PHP',        '言語'),
  ('Go',         '言語'),
  ('Kotlin',     '言語'),
  ('Swift',      '言語'),
  ('Ruby',       '言語'),
  ('C++',        '言語'),
  ('Scala',      '言語'),
  ('SQL',        '言語');

-- ---------- FW ----------
INSERT INTO m_skill_tag (skill_name, category) VALUES
  ('Spring Boot', 'FW'),
  ('React',       'FW'),
  ('Vue.js',      'FW'),
  ('Angular',     'FW'),
  ('Django',      'FW'),
  ('Flask',       'FW'),
  ('.NET',        'FW'),
  ('Next.js',     'FW'),
  ('Express.js',  'FW'),
  ('Laravel',     'FW'),
  ('MyBatis',     'FW'),
  ('Hibernate',   'FW');

-- ---------- DB ----------
INSERT INTO m_skill_tag (skill_name, category) VALUES
  ('MySQL',      'DB'),
  ('PostgreSQL', 'DB'),
  ('Oracle',     'DB'),
  ('SQL Server', 'DB'),
  ('MongoDB',    'DB'),
  ('Redis',      'DB'),
  ('DynamoDB',   'DB');

-- ---------- クラウド ----------
INSERT INTO m_skill_tag (skill_name, category) VALUES
  ('AWS',        'クラウド'),
  ('Azure',      'クラウド'),
  ('GCP',        'クラウド'),
  ('Docker',     'クラウド'),
  ('Kubernetes', 'クラウド');

-- ---------- OS ----------
INSERT INTO m_skill_tag (skill_name, category) VALUES
  ('Linux',          'OS'),
  ('Windows Server', 'OS'),
  ('macOS',          'OS');

-- ---------- ツール ----------
INSERT INTO m_skill_tag (skill_name, category) VALUES
  ('Git',        'ツール'),
  ('Jenkins',    'ツール'),
  ('Jira',       'ツール'),
  ('Confluence', 'ツール'),
  ('Slack',      'ツール'),
  ('Teams',      'ツール'),
  ('SVN',        'ツール'),
  ('Maven',      'ツール'),
  ('Gradle',     'ツール');


-- ============================================================
-- 3. m_email_template - メールテンプレート
-- ============================================================

-- Template 1: 提案メール
INSERT INTO m_email_template (template_name, subject_template, body_template, template_type)
VALUES (
  '要員提案メール',
  '【ご提案】{engineer_initial}様（{main_skill}エンジニア）のご紹介',
  '株式会社{customer_name}\n{contact_person}様\n\nいつもお世話になっております。\n{company_name}の{sender_name}でございます。\n\n貴社にてご検討中の{project_name}案件について、\n下記の要員をご提案させていただきたく、ご連絡いたしました。\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n■ 要員情報\n━━━━━━━━━━━━━━━━━━━━━━━━\n【氏名】{engineer_initial}（{gender}）\n【年齢】{age}歳\n【経験年数】{experience_years}年\n【得意技術】{main_skills}\n【日本語レベル】{japanese_level}\n【稼動可能日】{available_date}\n【希望単価】{expected_price}万円\n\n■ 経歴概要\n{resume_summary}\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n\nスキルシートを添付しておりますので、\nご確認いただけますと幸いです。\n\nご興味をお持ちいただけましたら、\n面談の機会をいただけますよう、何卒よろしくお願い申し上げます。\n\nご不明な点がございましたら、お気軽にお問い合わせください。\n\n何卒ご検討のほどよろしくお願いいたします。\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n{company_name}\n{sender_name}\nTEL: {sender_phone}\nEmail: {sender_email}\n━━━━━━━━━━━━━━━━━━━━━━━━',
  '提案'
);

-- Template 2: 面接依頼メール
INSERT INTO m_email_template (template_name, subject_template, body_template, template_type)
VALUES (
  '面接日程調整メール',
  '【面接日程のご相談】{project_name}案件について',
  '株式会社{customer_name}\n{contact_person}様\n\nいつもお世話になっております。\n{company_name}の{sender_name}でございます。\n\n先日ご提案させていただきました{engineer_initial}様について、\nご検討いただきありがとうございます。\n\nつきましては、面接（面談）の日程について\nご相談させていただければと存じます。\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n■ 面談候補日時\n━━━━━━━━━━━━━━━━━━━━━━━━\n第1希望：{interview_date_1}\n第2希望：{interview_date_2}\n第3希望：{interview_date_3}\n\n■ 面談形式：{interview_format}\n■ 所要時間：{interview_duration}\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n\n上記日程でご都合が合わない場合は、\nご希望の日時をお知らせいただけますと幸いです。\n\nお忙しいところ恐れ入りますが、\nご確認のほどよろしくお願いいたします。\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n{company_name}\n{sender_name}\nTEL: {sender_phone}\nEmail: {sender_email}\n━━━━━━━━━━━━━━━━━━━━━━━━',
  '面接依頼'
);

-- Template 3: お礼メール
INSERT INTO m_email_template (template_name, subject_template, body_template, template_type)
VALUES (
  '面接お礼メール',
  '【御礼】面接のお礼（{engineer_initial}様の件）',
  '株式会社{customer_name}\n{contact_person}様\n\nいつもお世話になっております。\n{company_name}の{sender_name}でございます。\n\n本日はお忙しい中、{engineer_initial}様の面接（面談）の\nお時間をいただき、誠にありがとうございました。\n\n{engineer_initial}様も貴社の{project_name}案件に\n大変興味を持っており、前向きに参画を希望しております。\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n■ 面談実施日：{interview_date}\n■ 案件名：{project_name}\n■ 対象者：{engineer_initial}様\n━━━━━━━━━━━━━━━━━━━━━━━━\n\n選考結果につきましては、\nご都合のよろしいタイミングでお知らせいただけますと幸いです。\n\nなお、ご質問やご確認事項等がございましたら、\nお気軽にお申し付けください。\n\n引き続き、何卒よろしくお願い申し上げます。\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n{company_name}\n{sender_name}\nTEL: {sender_phone}\nEmail: {sender_email}\n━━━━━━━━━━━━━━━━━━━━━━━━',
  'お礼'
);

-- Template 4: フォローアップメール
INSERT INTO m_email_template (template_name, subject_template, body_template, template_type)
VALUES (
  '選考状況確認メール',
  '【ご確認】{project_name}案件の選考状況について',
  '株式会社{customer_name}\n{contact_person}様\n\nいつもお世話になっております。\n{company_name}の{sender_name}でございます。\n\n先日ご提案（面談実施）させていただきました\n{engineer_initial}様の{project_name}案件について、\n選考状況のご確認をさせていただきたく、ご連絡いたしました。\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n■ 案件名：{project_name}\n■ 対象者：{engineer_initial}様\n■ ご提案日：{proposal_date}\n■ 面談実施日：{interview_date}\n━━━━━━━━━━━━━━━━━━━━━━━━\n\n現在の選考状況や、今後のスケジュール等について\nお教えいただけますと幸いです。\n\nまた、追加の情報や資料等が必要でしたら、\nお気軽にお申し付けください。\n\nお忙しいところ恐縮ですが、\nご確認のほどよろしくお願いいたします。\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n{company_name}\n{sender_name}\nTEL: {sender_phone}\nEmail: {sender_email}\n━━━━━━━━━━━━━━━━━━━━━━━━',
  'フォローアップ'
);


-- Template 5: 契約条件合意メール
INSERT INTO m_email_template (template_name, subject_template, body_template, template_type)
VALUES (
  '契約条件合意メール',
  '【ご確認】{project_name}案件 契約条件に関する合意の件',
  '株式会社{customer_name}\n{contact_person}様\n\nいつもお世話になっております。\n{company_name}の{sender_name}でございます。\n\n先日は{engineer_initial}様の面接のお時間をいただき、\nまた、参画内定のご連絡を賜り誠にありがとうございます。\n\n本件の契約条件につきまして、以下の通り合意とさせていただきたく存じます。\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n■ 案件名：{project_name}\n■ 対象者：{engineer_initial}様\n■ 契約形態：準委任契約\n■ 稼動開始日：{available_date}\n■ ご請求単価：{expected_price}万円（税別）\n■ 精算幅：{settlement_min}h - {settlement_max}h\n━━━━━━━━━━━━━━━━━━━━━━━━\n\n上記内容にて問題ございませんでしたら、\n追って契約書および個別契約書を送付させていただきます。\n\nご確認のほど、何卒よろしくお願い申し上げます。\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n{company_name}\n{sender_name}\nTEL: {sender_phone}\nEmail: {sender_email}\n━━━━━━━━━━━━━━━━━━━━━━━━',
  'その他'
);

-- Template 6: 見送り連絡メール
INSERT INTO m_email_template (template_name, subject_template, body_template, template_type)
VALUES (
  '見送り連絡メール',
  '【ご連絡】{project_name}案件の選考結果につきまして',
  '株式会社{customer_name}\n{contact_person}様\n\nいつもお世話になっております。\n{company_name}の{sender_name}でございます。\n\n先日ご提案（面談実施）させていただきました\n{engineer_initial}様の{project_name}案件につきまして、\n誠に残念ではございますが、今回は見送りとしたくご連絡いたしました。\n\nせっかくのお時間を頂戴し、また前向きにご検討いただいたにもかかわらず、\nこのような結果となりましたこと、深くお詫び申し上げます。\n\nまた別の機会にて、貴社のお力になれるご提案ができればと存じますので、\n今後とも何卒よろしくお願い申し上げます。\n\n━━━━━━━━━━━━━━━━━━━━━━━━\n{company_name}\n{sender_name}\nTEL: {sender_phone}\nEmail: {sender_email}\n━━━━━━━━━━━━━━━━━━━━━━━━',
  'その他'
);


-- ============================================================
-- 4. m_system_config - システム設定
-- ============================================================
INSERT INTO m_system_config (config_key, config_value, description) VALUES
  ('company_name',           'SES Manager Pro',  '会社名'),
  ('company_email',          'info@ses.local',    '会社メールアドレス'),
  ('default_settlement_min', '140',               '精算下限デフォルト値(時間)'),
  ('default_settlement_max', '180',               '精算上限デフォルト値(時間)'),
  ('ai_enabled',             'false',             'AI機能有効フラグ');


-- ============================================================
-- 5. m_customer - 顧客マスタ
-- ============================================================
INSERT INTO m_customer (company_name, commercial_flow, trust_level, contact_person) VALUES
('株式会社アルファテクノロジー', '元請け', 'A', '佐藤 一郎'),
('ベータシステムズ株式会社', '二次請け', 'B', '鈴木 二郎'),
('ガンマソリューションズ', 'エンド直', 'S', '高橋 三郎');


-- ============================================================
-- 6. t_project - 案件テーブル
-- ============================================================
INSERT INTO t_project (project_name, customer_id, commercial_flow, unit_price_min, unit_price_max, remote_type, status, created_by) VALUES
('金融系基盤システム移行', 1, '元請け', 800000, 1000000, 'フルリモート', '募集中', 1),
('BtoB向けECサイトリプレイス', 2, '二次請け', 600000, 800000, 'ハイブリッド', '選考中', 1),
('AI自動応答チャットボット開発', 3, 'エンド直', 900000, 1200000, 'フルリモート', '募集中', 1);


-- ============================================================
-- 7. t_engineer - 要員テーブル
-- ============================================================
INSERT INTO t_engineer (full_name, full_name_kana, initial_name, gender, employment_type, status, expected_unit_price, experience_years, resume_summary, created_by) VALUES
('田中 太郎', 'タナカ タロウ', 'T.T', '男性', '正社員', '稼動中', 800000, 5, 'Javaバックエンド開発を中心に、Spring Bootを用いたAPI設計・実装経験が豊富です。直近ではクラウド（AWS）を活用した基盤構築にも携わっています。', 1),
('山田 花子', 'ヤマダ ハナコ', 'Y.H', '女性', '契約社員', '提案中', 700000, 3, 'フロントエンド開発を得意とし、ReactやVue.jsを用いたSPAの開発経験があります。UI/UXを意識した実装を心がけています。', 1),
('伊藤 健太', 'イトウ ケンタ', 'I.K', '男性', 'BP', 'Bench', 600000, 2, 'Pythonを用いたデータ分析スクリプトの作成や、Djangoでの簡単なWebアプリケーション構築経験があります。現在は新しい技術の習得に意欲的です。', 1);


-- ============================================================
-- 8. t_proposal - 提案テーブル
-- ============================================================
INSERT INTO t_proposal (engineer_id, project_id, proposed_unit_price, status, proposed_by) VALUES
(2, 1, 750000, '書類選考中', 1),
(3, 2, 600000, '一次面接', 1);


-- ============================================================
-- 9. t_contract - 契約テーブル
-- ============================================================
INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, start_date, end_date, selling_price, cost_price, status, created_by) VALUES
('CON-2026-0001', 1, 3, 3, '2026-06-01', '2026-08-31', 950000, 800000, '稼動中', 1);


-- ============================================================
-- 初期データ投入完了
-- ============================================================
