package com.ses.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Customer;
import com.ses.entity.CustomerCsat;
import com.ses.entity.CustomerHealthSnapshot;
import com.ses.entity.CustomerQbr;
import com.ses.entity.CustomerQbrAction;
import com.ses.entity.ServiceComment;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.entity.ServiceSlaPolicy;
import com.ses.mapper.CustomerCsatMapper;
import com.ses.mapper.CustomerHealthSnapshotMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.CustomerQbrActionMapper;
import com.ses.mapper.CustomerQbrMapper;
import com.ses.mapper.ServiceAttachmentLinkMapper;
import com.ses.mapper.ServiceCommentMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.mapper.ServiceSlaPolicyMapper;
import com.ses.mapper.ServiceStateEventMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class ServiceDeskEntityMapperTest {

    @Autowired
    private ServiceSlaPolicyMapper slaPolicyMapper;
    @Autowired
    private ServiceRequestMapper serviceRequestMapper;
    @Autowired
    private ServiceSlaClockMapper slaClockMapper;
    @Autowired
    private ServiceCommentMapper commentMapper;
    @Autowired
    private ServiceAttachmentLinkMapper attachmentLinkMapper;
    @Autowired
    private ServiceStateEventMapper stateEventMapper;
    @Autowired
    private CustomerCsatMapper csatMapper;
    @Autowired
    private CustomerQbrMapper qbrMapper;
    @Autowired
    private CustomerQbrActionMapper qbrActionMapper;
    @Autowired
    private CustomerHealthSnapshotMapper healthSnapshotMapper;
    @Autowired
    private CustomerMapper customerMapper;

    @Test
    @DisplayName("SLAポリシーの取得と新規追加ができること")
    void testSlaPolicyMapper() {
        List<ServiceSlaPolicy> policies = slaPolicyMapper.selectList(null);
        assertNotNull(policies);
        assertTrue(policies.size() >= 4, "初期ポリシー4件(P0-P3)が存在すること");

        ServiceSlaPolicy p0 = slaPolicyMapper.selectOne(
                new LambdaQueryWrapper<ServiceSlaPolicy>().eq(ServiceSlaPolicy::getPriority, "P0")
        );
        assertNotNull(p0);
        assertEquals(1, p0.getResponseTimeHours());
        assertEquals(4, p0.getResolveTimeHours());
    }

    @Test
    @DisplayName("サービスリクエストおよび関連エンティティのCRUDができること")
    void testServiceRequestAndRelatedEntities() {
        // 顧客作成
        Customer customer = new Customer();
        customer.setCompanyName("テスト顧客CS");
        customerMapper.insert(customer);
        assertNotNull(customer.getId());

        // サービスリクエスト作成
        ServiceRequest req = ServiceRequest.builder()
                .requestNo("REQ-202608-0001")
                .customerId(customer.getId())
                .category("CONTRACT")
                .priority("P1")
                .channel("PORTAL")
                .subject("契約更新に関するご相談")
                .description("来期の契約期間について相談したい")
                .status("RECEIVED")
                .version(0)
                .build();
        serviceRequestMapper.insert(req);
        assertNotNull(req.getId());

        // SLA Clock作成
        ServiceSlaClock clock = ServiceSlaClock.builder()
                .serviceRequestId(req.getId())
                .roundNo(1)
                .policyId(1L)
                .responseDeadline(LocalDateTime.now().plusHours(2))
                .resolveDeadline(LocalDateTime.now().plusHours(8))
                .responseBreached(false)
                .resolveBreached(false)
                .totalPauseMinutes(0)
                .status("RUNNING")
                .version(0)
                .build();
        slaClockMapper.insert(clock);
        assertNotNull(clock.getId());

        // コメント作成 (INTERNAL & PORTAL_VISIBLE)
        ServiceComment internalComment = ServiceComment.builder()
                .serviceRequestId(req.getId())
                .authorType("INTERNAL_USER")
                .authorId(100L)
                .authorName("営業担当A")
                .visibility("INTERNAL")
                .commentText("内部メモ: 粗利目標を考慮して回答する")
                .build();
        commentMapper.insert(internalComment);

        ServiceComment publicComment = ServiceComment.builder()
                .serviceRequestId(req.getId())
                .authorType("INTERNAL_USER")
                .authorId(100L)
                .authorName("営業担当A")
                .visibility("PORTAL_VISIBLE")
                .commentText("お問い合わせありがとうございます。確認の上ご連絡いたします。")
                .build();
        commentMapper.insert(publicComment);

        List<ServiceComment> portalComments = commentMapper.selectList(
                new LambdaQueryWrapper<ServiceComment>()
                        .eq(ServiceComment::getServiceRequestId, req.getId())
                        .eq(ServiceComment::getVisibility, "PORTAL_VISIBLE")
        );
        assertEquals(1, portalComments.size());
        assertEquals("PORTAL_VISIBLE", portalComments.get(0).getVisibility());

        // CSAT回答作成
        CustomerCsat csat = CustomerCsat.builder()
                .serviceRequestId(req.getId())
                .customerId(customer.getId())
                .portalUserId(200L)
                .score(5)
                .feedbackComment("迅速な対応に感謝します。")
                .answeredAt(LocalDateTime.now())
                .build();
        csatMapper.insert(csat);
        assertNotNull(csat.getId());

        // QBR & Action作成
        CustomerQbr qbr = CustomerQbr.builder()
                .customerId(customer.getId())
                .title("2026年Q3定例会")
                .meetingDate(LocalDate.now())
                .agenda("稼働状況レビューおよび次期要員計画")
                .minutes("概ね良好")
                .actionItems("来月1名増員")
                .build();
        qbrMapper.insert(qbr);
        assertNotNull(qbr.getId());

        CustomerQbrAction action = CustomerQbrAction.builder()
                .qbrId(qbr.getId())
                .title("増員案件のスキル要件確認")
                .ownerUserId(100L)
                .dueDate(LocalDate.now().plusWeeks(1))
                .status("OPEN")
                .version(0)
                .build();
        qbrActionMapper.insert(action);
        assertNotNull(action.getId());

        // 顧客ヘルススナップショット作成
        CustomerHealthSnapshot health = CustomerHealthSnapshot.builder()
                .customerId(customer.getId())
                .snapshotDate(LocalDate.now())
                .healthStatus("HEALTHY")
                .totalScore(95)
                .openCriticalIssuesCount(0)
                .slaBreachCount30d(0)
                .avgCsatScore(new BigDecimal("5.00"))
                .arOverdueFlag(false)
                .missingInputsJson("[]")
                .factorsExplanation("未解決重要課題なし、CSAT高評価")
                .versionNo(1)
                .snapshotHash("dummy-hash-1234567890abcdef")
                .isCurrent(true)
                .actorType("SYSTEM")
                .createdAt(LocalDateTime.now())
                .build();
        healthSnapshotMapper.insert(health);
        assertNotNull(health.getId());
    }
}
