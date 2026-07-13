package com.ses.mapper;

import com.ses.entity.Engineer;
import com.ses.entity.Proposal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 孤児ファイル清掃用の軽量プロジェクションSQLをH2上で検証する（P8フォローアップ・提案10）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class FileReferenceProjectionMapperIntegrationTest {

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private ProposalMapper proposalMapper;

    @Test
    void selectAllPhotoUrls_null以外の写真URLのみ取得する() {
        Engineer withPhoto = new Engineer();
        withPhoto.setFullName("写真あり");
        withPhoto.setEmploymentType("正社員");
        withPhoto.setStatus("Bench");
        withPhoto.setPhotoUrl("abc123.png");
        engineerMapper.insert(withPhoto);

        Engineer withoutPhoto = new Engineer();
        withoutPhoto.setFullName("写真なし");
        withoutPhoto.setEmploymentType("正社員");
        withoutPhoto.setStatus("Bench");
        engineerMapper.insert(withoutPhoto);

        List<String> urls = engineerMapper.selectAllPhotoUrls();

        assertTrue(urls.contains("abc123.png"));
        assertTrue(urls.stream().noneMatch(u -> u == null));
    }

    @Test
    void selectAllSkillSheetPaths_null以外のスキルシートパスのみ取得する() {
        Proposal withSheet = new Proposal();
        withSheet.setEngineerId(1L);
        withSheet.setProjectId(1L);
        withSheet.setStatus("書類選考中");
        withSheet.setSkillSheetPath("sheet123.pdf");
        proposalMapper.insert(withSheet);

        Proposal withoutSheet = new Proposal();
        withoutSheet.setEngineerId(1L);
        withoutSheet.setProjectId(1L);
        withoutSheet.setStatus("書類選考中");
        proposalMapper.insert(withoutSheet);

        List<String> paths = proposalMapper.selectAllSkillSheetPaths();

        assertTrue(paths.contains("sheet123.pdf"));
        assertTrue(paths.stream().noneMatch(p -> p == null));
    }
}
