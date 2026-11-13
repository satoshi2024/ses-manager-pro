-- R__remove_v2_sample_data.sql（prod専用 Repeatable）
--
-- V2__init_master_data.sql の業務sample（顧客3・案件3・要員3・提案2・契約1）と、後続migrationが
-- それらから派生させた行を本番から削除する（配備決定-1。V2本体は変更しない）。
-- dev/test は db/migration だけを使うため、この削除は本番のlocationでしか実行されない。
--
-- 削除する派生行（いずれも空DBへの新規導入でV2 sampleからだけ生じる）:
--   t_customer_contact               V73:178 / V74_1:55 / R__crm_contact_reconciliation:32（顧客担当者名の複製）
--   t_engineer_accounting_history    V61:60（要員の会計属性履歴の初版）
--   t_contract_acceptance_backfill   V80:162（契約1の行。sentinel行 contract_id=0 はV80の完了markerなので残す）
--   nf02_nf03_ownership_repair_queue V169/V177/V178（顧客3・要員3・案件3・契約1の計10行）
--   t_legal_entity_backfill_audit    V158（顧客・要員・案件・契約のUNRESOLVED監査行）
-- V60のLEGACY組織（既存データ移行組織）はV2 sampleの有無と無関係に作られるため残す。
-- 要員の所属付け（t_engineer.organization_id）は要員行と一緒に消える。
--
-- Repeatableを選んだ理由と安全条件:
--   db/migration-prod にversioned scriptを置くと db/migration と同じversion空間を共有し、
--   過去に2回（V10 / V41）衝突している。Repeatableはversionを持たないが、checksumが変わるたびに
--   全versioned migrationの後で再実行されるため、次の3条件を満たす場合だけ削除する。
--   (1) 一度だけ: 本scriptの成功履歴が flyway_schema_history に無い（初回の適用時だけ）。
--   (2) 新規導入直後だけ: 5表の行数がV2のsample件数と一致し、全行がV2の値（idと全投入列）と一致する。
--       実データが1行でもあれば何も削除しない（部分削除もしない）。
--   (3) 削除対象もV2の値・sampleのidで限定する。想定外の子行が外部キーで残っていればDELETEが失敗し、
--       DML全体がrollbackされる（実データは消えない）。
-- 本scriptはDMLだけで構成するため、1つのtransactionで適用される。

SET @ses_v2_sample_state = (
  SELECT CASE
    WHEN EXISTS (SELECT 1 FROM flyway_schema_history
                  WHERE script = 'R__remove_v2_sample_data.sql' AND success = 1)
      THEN 'ALREADY_APPLIED'
    WHEN (SELECT COUNT(*) FROM m_customer) = 3
     AND (SELECT COUNT(*) FROM m_customer
           WHERE (id = 1 AND company_name = '株式会社アルファテクノロジー' AND commercial_flow = '元請け'
                  AND trust_level = 'A' AND contact_person = '佐藤 一郎')
              OR (id = 2 AND company_name = 'ベータシステムズ株式会社' AND commercial_flow = '二次請け'
                  AND trust_level = 'B' AND contact_person = '鈴木 二郎')
              OR (id = 3 AND company_name = 'ガンマソリューションズ' AND commercial_flow = 'エンド直'
                  AND trust_level = 'S' AND contact_person = '高橋 三郎')) = 3
     AND (SELECT COUNT(*) FROM t_project) = 3
     AND (SELECT COUNT(*) FROM t_project
           WHERE (id = 1 AND project_name = '金融系基盤システム移行' AND customer_id = 1
                  AND commercial_flow = '元請け' AND unit_price_min = 800000 AND unit_price_max = 1000000
                  AND remote_type = 'フルリモート' AND status = '募集中' AND created_by = 1)
              OR (id = 2 AND project_name = 'BtoB向けECサイトリプレイス' AND customer_id = 2
                  AND commercial_flow = '二次請け' AND unit_price_min = 600000 AND unit_price_max = 800000
                  AND remote_type = 'ハイブリッド' AND status = '選考中' AND created_by = 1)
              OR (id = 3 AND project_name = 'AI自動応答チャットボット開発' AND customer_id = 3
                  AND commercial_flow = 'エンド直' AND unit_price_min = 900000 AND unit_price_max = 1200000
                  AND remote_type = 'フルリモート' AND status = '募集中' AND created_by = 1)) = 3
     AND (SELECT COUNT(*) FROM t_engineer) = 3
     AND (SELECT COUNT(*) FROM t_engineer
           WHERE (id = 1 AND full_name = '田中 太郎' AND full_name_kana = 'タナカ タロウ' AND initial_name = 'T.T'
                  AND gender = '男性' AND employment_type = '正社員' AND status = '稼動中'
                  AND expected_unit_price = 800000 AND experience_years = 5 AND created_by = 1
                  AND resume_summary = 'Javaバックエンド開発を中心に、Spring Bootを用いたAPI設計・実装経験が豊富です。直近ではクラウド（AWS）を活用した基盤構築にも携わっています。')
              OR (id = 2 AND full_name = '山田 花子' AND full_name_kana = 'ヤマダ ハナコ' AND initial_name = 'Y.H'
                  AND gender = '女性' AND employment_type = '契約社員' AND status = '提案中'
                  AND expected_unit_price = 700000 AND experience_years = 3 AND created_by = 1
                  AND resume_summary = 'フロントエンド開発を得意とし、ReactやVue.jsを用いたSPAの開発経験があります。UI/UXを意識した実装を心がけています。')
              OR (id = 3 AND full_name = '伊藤 健太' AND full_name_kana = 'イトウ ケンタ' AND initial_name = 'I.K'
                  AND gender = '男性' AND employment_type = 'BP' AND status = 'Bench'
                  AND expected_unit_price = 600000 AND experience_years = 2 AND created_by = 1
                  AND resume_summary = 'Pythonを用いたデータ分析スクリプトの作成や、Djangoでの簡単なWebアプリケーション構築経験があります。現在は新しい技術の習得に意欲的です。')) = 3
     AND (SELECT COUNT(*) FROM t_proposal) = 2
     AND (SELECT COUNT(*) FROM t_proposal
           WHERE (id = 1 AND engineer_id = 2 AND project_id = 1 AND proposed_unit_price = 750000
                  AND status = '書類選考中' AND proposed_by = 1)
              OR (id = 2 AND engineer_id = 3 AND project_id = 2 AND proposed_unit_price = 600000
                  AND status = '一次面接' AND proposed_by = 1)) = 2
     AND (SELECT COUNT(*) FROM t_contract) = 1
     AND (SELECT COUNT(*) FROM t_contract
           WHERE id = 1 AND contract_no = 'CON-2026-0001' AND engineer_id = 1 AND project_id = 3
             AND customer_id = 3 AND start_date = '2026-06-01' AND end_date = '2026-08-31'
             AND selling_price = 950000 AND cost_price = 800000 AND status = '稼動中' AND created_by = 1) = 1
      THEN 'FRESH'
    ELSE 'NOT_FRESH'
  END
);

-- ---- 派生行（子・監査・修復queue）を先に消す ----
DELETE FROM t_customer_contact
 WHERE @ses_v2_sample_state = 'FRESH'
   AND ((customer_id = 1 AND name = '佐藤 一郎')
     OR (customer_id = 2 AND name = '鈴木 二郎')
     OR (customer_id = 3 AND name = '高橋 三郎'));

DELETE FROM t_engineer_accounting_history
 WHERE @ses_v2_sample_state = 'FRESH'
   AND engineer_id IN (1, 2, 3);

DELETE FROM t_contract_acceptance_backfill
 WHERE @ses_v2_sample_state = 'FRESH'
   AND contract_id = 1;

DELETE FROM nf02_nf03_ownership_repair_queue
 WHERE @ses_v2_sample_state = 'FRESH'
   AND ((entity_type IN ('CUSTOMER', 'ENGINEER', 'PROJECT') AND entity_id IN (1, 2, 3))
     OR (entity_type = 'CONTRACT' AND entity_id = 1));

DELETE FROM t_legal_entity_backfill_audit
 WHERE @ses_v2_sample_state = 'FRESH'
   AND ((entity_type IN ('CUSTOMER', 'ENGINEER', 'PROJECT') AND entity_id IN (1, 2, 3))
     OR (entity_type = 'CONTRACT' AND entity_id = 1));

-- ---- sample本体（外部キーの子から順に消す） ----
DELETE FROM t_proposal
 WHERE @ses_v2_sample_state = 'FRESH'
   AND ((id = 1 AND engineer_id = 2 AND project_id = 1 AND proposed_unit_price = 750000
         AND status = '書類選考中' AND proposed_by = 1)
     OR (id = 2 AND engineer_id = 3 AND project_id = 2 AND proposed_unit_price = 600000
         AND status = '一次面接' AND proposed_by = 1));

DELETE FROM t_contract
 WHERE @ses_v2_sample_state = 'FRESH'
   AND id = 1 AND contract_no = 'CON-2026-0001' AND engineer_id = 1 AND project_id = 3
   AND customer_id = 3 AND start_date = '2026-06-01' AND end_date = '2026-08-31'
   AND selling_price = 950000 AND cost_price = 800000 AND status = '稼動中' AND created_by = 1;

DELETE FROM t_project
 WHERE @ses_v2_sample_state = 'FRESH'
   AND ((id = 1 AND project_name = '金融系基盤システム移行' AND customer_id = 1
         AND commercial_flow = '元請け' AND unit_price_min = 800000 AND unit_price_max = 1000000
         AND remote_type = 'フルリモート' AND status = '募集中' AND created_by = 1)
     OR (id = 2 AND project_name = 'BtoB向けECサイトリプレイス' AND customer_id = 2
         AND commercial_flow = '二次請け' AND unit_price_min = 600000 AND unit_price_max = 800000
         AND remote_type = 'ハイブリッド' AND status = '選考中' AND created_by = 1)
     OR (id = 3 AND project_name = 'AI自動応答チャットボット開発' AND customer_id = 3
         AND commercial_flow = 'エンド直' AND unit_price_min = 900000 AND unit_price_max = 1200000
         AND remote_type = 'フルリモート' AND status = '募集中' AND created_by = 1));

DELETE FROM t_engineer
 WHERE @ses_v2_sample_state = 'FRESH'
   AND ((id = 1 AND full_name = '田中 太郎' AND full_name_kana = 'タナカ タロウ' AND initial_name = 'T.T'
         AND gender = '男性' AND employment_type = '正社員' AND status = '稼動中'
         AND expected_unit_price = 800000 AND experience_years = 5 AND created_by = 1)
     OR (id = 2 AND full_name = '山田 花子' AND full_name_kana = 'ヤマダ ハナコ' AND initial_name = 'Y.H'
         AND gender = '女性' AND employment_type = '契約社員' AND status = '提案中'
         AND expected_unit_price = 700000 AND experience_years = 3 AND created_by = 1)
     OR (id = 3 AND full_name = '伊藤 健太' AND full_name_kana = 'イトウ ケンタ' AND initial_name = 'I.K'
         AND gender = '男性' AND employment_type = 'BP' AND status = 'Bench'
         AND expected_unit_price = 600000 AND experience_years = 2 AND created_by = 1));

DELETE FROM m_customer
 WHERE @ses_v2_sample_state = 'FRESH'
   AND ((id = 1 AND company_name = '株式会社アルファテクノロジー' AND commercial_flow = '元請け'
         AND trust_level = 'A' AND contact_person = '佐藤 一郎')
     OR (id = 2 AND company_name = 'ベータシステムズ株式会社' AND commercial_flow = '二次請け'
         AND trust_level = 'B' AND contact_person = '鈴木 二郎')
     OR (id = 3 AND company_name = 'ガンマソリューションズ' AND commercial_flow = 'エンド直'
         AND trust_level = 'S' AND contact_person = '高橋 三郎'));

SET @ses_v2_sample_state = NULL;
