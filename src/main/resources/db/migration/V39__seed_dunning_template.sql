-- R3R-19: 督促（リマインド）専用のメールテンプレートをシードする。
-- 変数は他シードと同じ一重波括弧 snake_case 記法。TemplateRenderer が camelCase パラメータへ解決する。
INSERT INTO m_email_template (template_name, subject_template, body_template, template_type)
SELECT '請求督促メール',
       '【お支払いのご確認】請求書 {invoice_no} の件',
       CONCAT(
         '{customer_name} 御中\n\n',
         'いつもお世話になっております。\n',
         '標記の請求書につきまして、お支払期限を過ぎてもご入金の確認が取れておりません。\n\n',
         '━━━━━━━━━━━━━━━━━━━━━━━━\n',
         '■ 請求番号：{invoice_no}\n',
         '■ 請求金額：{total} 円\n',
         '■ 未回収残高：{balance} 円\n',
         '■ お支払期限：{due_date}\n',
         '■ 経過日数：{overdue_days} 日\n',
         '━━━━━━━━━━━━━━━━━━━━━━━━\n\n',
         '恐れ入りますが、ご入金状況をご確認いただけますようお願い申し上げます。\n',
         '本メールと行き違いにご入金済みの場合は、何卒ご容赦ください。\n'
       ),
       'その他'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM m_email_template WHERE template_name = '請求督促メール');
