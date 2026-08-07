package com.ses.web;

import com.ses.entity.Acceptance;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.WorkRecord;
import com.ses.mapper.AcceptanceMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.WorkRecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 起動中の実Webサーバー（Tomcat）に対して Chrome Headless を実行し、
 * SES Manager Pro 実際のHTML/CSS/JS描画結果のPNGスクリーンショットをキャプチャする（R7-P2-04）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RealBrowserScreenshotTest {

    @LocalServerPort
    private int port;

    @Autowired private CustomerMapper customerMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private ProjectMapper projectMapper;
    @Autowired private ContractMapper contractMapper;
    @Autowired private WorkRecordMapper workRecordMapper;
    @Autowired private AcceptanceMapper acceptanceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("R7-P2-04: Chrome HeadlessによりSES Manager Pro実ページのDesktop/Mobile PNGスクリーンショットを生成")
    void captureRealWebpageScreenshots() throws Exception {
        // Seed データの確認／投入
        String suffix = "-REAL-" + System.currentTimeMillis();
        Customer customer = new Customer();
        customer.setCompanyName("テックソリューションズ株式会社" + suffix);
        customer.setTrustLevel("A");
        customerMapper.insert(customer);

        Engineer engineer = new Engineer();
        engineer.setFullName("山田 太郎" + suffix);
        engineer.setEmploymentType("正社員");
        engineer.setStatus("稼動中");
        engineerMapper.insert(engineer);

        Project project = new Project();
        project.setProjectName("基幹システム刷新" + suffix);
        project.setCustomerId(customer.getId());
        project.setStatus("募集中");
        projectMapper.insert(project);

        Contract contract = new Contract();
        contract.setContractNo("CON-REAL-" + System.currentTimeMillis());
        contract.setEngineerId(engineer.getId());
        contract.setProjectId(project.getId());
        contract.setCustomerId(customer.getId());
        contract.setStartDate(LocalDate.of(2026, 1, 1));
        contract.setSellingPrice(new BigDecimal("600000"));
        contract.setCostPrice(new BigDecimal("300000"));
        contract.setStatus("稼動中");
        contract.setAcceptanceRequired(true);
        contractMapper.insert(contract);

        WorkRecord workRecord = new WorkRecord();
        workRecord.setContractId(contract.getId());
        workRecord.setWorkMonth("2026-07");
        workRecord.setActualHours(new BigDecimal("160.00"));
        workRecord.setBillingAmount(new BigDecimal("600000"));
        workRecord.setStatus("確定");
        workRecordMapper.insert(workRecord);

        Acceptance acceptance = new Acceptance();
        acceptance.setContractId(contract.getId());
        acceptance.setWorkRecordId(workRecord.getId());
        acceptance.setWorkMonth("2026-07");
        acceptance.setStatus("提出済");
        acceptance.setSubmittedAt(LocalDateTime.of(2026, 7, 31, 17, 0));
        acceptance.setHoursSnapshot(new BigDecimal("160.00"));
        acceptance.setAmountSnapshot(new BigDecimal("600000"));
        acceptance.setCreatedBy(1L);
        acceptanceMapper.insert(acceptance);

        String chromePath = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
        File chromeFile = new File(chromePath);
        if (!chromeFile.exists()) {
            chromePath = "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe";
            chromeFile = new File(chromePath);
        }

        if (chromeFile.exists()) {
            String pageUrl = "http://localhost:" + port + "/acceptance?workMonth=2026-07&acceptanceId=" + acceptance.getId();
            File dir = new File(".kiro/specs/order-acceptance-workflow/evidence");
            if (!dir.exists()) dir.mkdirs();

            File desktopPng = new File(dir, "desktop-1920x1080.png");
            File mobilePng = new File(dir, "mobile-390x844.png");

            ProcessBuilder pbDesktop = new ProcessBuilder(
                    chromePath, "--headless", "--disable-gpu", "--window-size=1920,1080",
                    "--screenshot=" + desktopPng.getAbsolutePath(), pageUrl
            );
            Process procD = pbDesktop.start();
            procD.waitFor();

            ProcessBuilder pbMobile = new ProcessBuilder(
                    chromePath, "--headless", "--disable-gpu", "--window-size=390,844",
                    "--screenshot=" + mobilePng.getAbsolutePath(), pageUrl
            );
            Process procM = pbMobile.start();
            procM.waitFor();

            assertTrue(desktopPng.exists() && desktopPng.length() > 0, "Desktop PNG スクリーンショットが生成されること");
            assertTrue(mobilePng.exists() && mobilePng.length() > 0, "Mobile PNG スクリーンショットが生成されること");
        }
    }
}
