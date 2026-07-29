package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.entity.BpAvailabilityIngestion;
import com.ses.entity.ProjectIngestion;
import com.ses.mapper.BpAvailabilityIngestionMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectIngestionMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.ResumeIngestionMapper;
import com.ses.service.MenuCacheService;
import com.ses.service.security.impl.FileScopeValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 取込機能の原本ファイル（氏名・連絡先を含むPII）が、対応するメニュー権限を持つロールにしか
 * ダウンロードできないことの検証。
 *
 * <p>{@link FileScopeValidationService} は「どのテーブルにも該当しなければ許可」というフォール
 * スルーを持つため、ファイルを保存する機能を追加した際に判定を足し忘れると、黙って認証済み
 * 全ユーザーへ公開されてしまう。FR-01(案件メール取込)・FR-08(要員空き状況取込)の原本が
 * まさにこの状態だったため、その回帰を防ぐ。
 */
@ExtendWith(MockitoExtension.class)
class FileScopeValidationServiceTest {

    @Mock private ResumeIngestionMapper resumeIngestionMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private ProposalMapper proposalMapper;
    @Mock private ProjectIngestionMapper projectIngestionMapper;
    @Mock private BpAvailabilityIngestionMapper bpAvailabilityIngestionMapper;
    @Mock private DataScopeService dataScopeService;
    @Mock private MenuCacheService menuCacheService;
    @Mock private ObjectProvider<MenuCacheService> menuCacheServiceProvider;

    @InjectMocks private FileScopeValidationService service;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String role) {
        // SecurityUtils.currentRole() は principal が LoginUser のときだけロールを返す
        com.ses.entity.SysUser user = new com.ses.entity.SysUser();
        user.setId(1L);
        user.setUsername("tester");
        user.setRole(role);
        com.ses.config.LoginUser principal = new com.ses.config.LoginUser(
                user, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    /** 先行する3判定（レジュメ/写真/スキルシート）に該当させないための共通スタブ。 */
    private void noMatchOnEarlierTables() {
        lenient().when(resumeIngestionMapper.selectOne(any())).thenReturn(null);
        lenient().when(engineerMapper.selectOne(any())).thenReturn(null);
        lenient().when(proposalMapper.selectOne(any())).thenReturn(null);
    }

    @Test
    void 案件メール取込の原本はproject_ingestionメニューを持たないロールに403() {
        noMatchOnEarlierTables();
        when(projectIngestionMapper.selectOne(any())).thenReturn(new ProjectIngestion());
        loginAs("HR");
        when(menuCacheServiceProvider.getIfAvailable()).thenReturn(menuCacheService);
        when(menuCacheService.getMenuKeysByRole("HR")).thenReturn(List.of("engineer"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("abc.eml"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 案件メール取込の原本はメニューを持つロールなら許可() {
        noMatchOnEarlierTables();
        when(projectIngestionMapper.selectOne(any())).thenReturn(new ProjectIngestion());
        loginAs("営業");
        when(menuCacheServiceProvider.getIfAvailable()).thenReturn(menuCacheService);
        when(menuCacheService.getMenuKeysByRole("営業")).thenReturn(List.of("project-ingestion"));

        assertDoesNotThrow(() -> service.assertDownloadAllowed("abc.eml"));
    }

    @Test
    void 要員空き状況取込の原本はbp_availability_ingestionメニューで判定する() {
        noMatchOnEarlierTables();
        when(projectIngestionMapper.selectOne(any())).thenReturn(null);
        when(bpAvailabilityIngestionMapper.selectOne(any())).thenReturn(new BpAvailabilityIngestion());
        loginAs("HR");
        when(menuCacheServiceProvider.getIfAvailable()).thenReturn(menuCacheService);
        when(menuCacheService.getMenuKeysByRole("HR")).thenReturn(List.of("engineer"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("abc.pdf"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 管理者はメニュー設定によらず取込原本を参照できる() {
        noMatchOnEarlierTables();
        when(projectIngestionMapper.selectOne(any())).thenReturn(new ProjectIngestion());
        loginAs("管理者");

        assertDoesNotThrow(() -> service.assertDownloadAllowed("abc.eml"));
    }

    @Test
    void 未登録のstoredNameはdefaultDeny() {
        noMatchOnEarlierTables();
        loginAs("営業");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDownloadAllowed("unknown.png"));
        assertEquals(403, ex.getCode());
    }
}
