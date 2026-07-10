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

INSERT INTO m_customer (company_name, commercial_flow, trust_level, contact_person) VALUES
('株式会社アルファテクノロジー', '元請け', 'A', '佐藤 一郎'),
('ベータシステムズ株式会社', '二次請け', 'B', '鈴木 二郎'),
('ガンマソリューションズ', 'エンド直', 'S', '高橋 三郎');

INSERT INTO t_project (project_name, customer_id, commercial_flow, unit_price_min, unit_price_max, remote_type, status, created_by) VALUES
('金融系基盤システム移行', 1, '元請け', 800000, 1000000, 'フルリモート', '募集中', 1),
('BtoB向けECサイトリプレイス', 2, '二次請け', 600000, 800000, 'ハイブリッド', '選考中', 1),
('AI自動応答チャットボット開発', 3, 'エンド直', 900000, 1200000, 'フルリモート', '募集中', 1);

INSERT INTO t_engineer (full_name, full_name_kana, initial_name, gender, employment_type, status, expected_unit_price, experience_years, resume_summary, created_by) VALUES
('田中 太郎', 'タナカ タロウ', 'T.T', '男性', '正社員', '稼動中', 800000, 5, 'Javaバックエンド開発を中心に、Spring Bootを用いたAPI設計・実装経験が豊富です。直近ではクラウド（AWS）を活用した基盤構築にも携わっています。', 1),
('山田 花子', 'ヤマダ ハナコ', 'Y.H', '女性', '契約社員', '提案中', 700000, 3, 'フロントエンド開発を得意とし、ReactやVue.jsを用いたSPAの開発経験があります。UI/UXを意識した実装を心がけています。', 1),
('伊藤 健太', 'イトウ ケンタ', 'I.K', '男性', 'BP', 'Bench', 600000, 2, 'Pythonを用いたデータ分析スクリプトの作成や、Djangoでの簡単なWebアプリケーション構築経験があります。現在は新しい技術の習得に意欲的です。', 1);

INSERT INTO t_proposal (engineer_id, project_id, proposed_unit_price, status, proposed_by) VALUES
(2, 1, 750000, '書類選考中', 1),
(3, 2, 600000, '一次面接', 1);

INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, start_date, end_date, selling_price, cost_price, status, created_by) VALUES
('CON-2026-0001', 1, 3, 3, '2026-06-01', '2026-08-31', 950000, 800000, '稼動中', 1);
