-- R3R-19: 督促（リマインド）専用のメールテンプレートをシードする。
-- 変数は他シードと同じ一重波括弧 snake_case 記法。TemplateRenderer が camelCase パラメータへ解決する。
-- Flyway は各マイグレーションをDBごとに1回だけ適用し、本テンプレートは新規追加のため既存DBにも重複しない。
-- よって冪等ガード（WHERE NOT EXISTS）は不要で、V2の他シードと同じ素の INSERT ... VALUES とする
-- （MySQLはINSERT対象テーブルを自身のサブクエリで参照するとerror 1093になるため、その形は避ける）。
INSERT INTO m_email_template (template_name, subject_template, body_template, template_type)
VALUES (
  '請求督促メール',
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
);
