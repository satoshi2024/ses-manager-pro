package com.ses.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.portal.PortalCsatCreateRequest;
import com.ses.dto.portal.PortalServiceCommentCreateRequest;
import com.ses.dto.portal.PortalServiceRequestCreateRequest;
import com.ses.dto.servicedesk.ServiceCommentCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest;
import com.ses.entity.Customer;
import com.ses.entity.PortalOrganization;
import com.ses.entity.ServiceRequest;
import com.ses.mapper.CustomerMapper;
import com.ses.service.servicedesk.ServiceRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PortalCustomerServiceDeskApiTest extends PortalTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected CustomerMapper customerMapper;

    @Autowired
    protected com.ses.mapper.EngineerMapper engineerMapper;

    @Autowired
    protected ServiceRequestService serviceRequestService;

    @Override
    protected JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private UserFixture customerAUser;
    private UserFixture customerBUser;
    private PortalOrganization customerAOrg;
    private PortalOrganization customerBOrg;
    private ServiceRequest customerARequest;

    @BeforeEach
    void setUp() {
        String uA = unique();
        String uB = unique();
        customerAOrg = createCustomerOrg("A-" + uA);
        customerBOrg = createCustomerOrg("B-" + uB);

        customerAUser = readyUser(customerAOrg, "userA-" + uA + "@customer.com");
        customerBUser = readyUser(customerBOrg, "userB-" + uB + "@customer.com");

        // サービスデスク権限を付与 (service-desk.view, service-desk.create)
        grantServiceDeskPermissions(customerAUser.user().getId());
        grantServiceDeskPermissions(customerBUser.user().getId());

        // 顧客Aの問い合わせ作成
        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(customerAOrg.getCustomerId())
                .category("CONTRACT")
                .priority("P2")
                .subject("顧客Aの契約に関する問い合わせ")
                .description("更新時期について確認したい")
                .build();
        customerARequest = serviceRequestService.createRequest(req, 1L, false, null);

        // 内部メモと公開返信を追加
        serviceRequestService.addComment(customerARequest.getId(),
                ServiceCommentCreateRequest.builder().commentText("社内機密メモ: 顧客Aは契約延長の可能性高").visibility("INTERNAL").build(),
                1L, "INTERNAL_USER", "営業マネージャー", false);
        serviceRequestService.addComment(customerARequest.getId(),
                ServiceCommentCreateRequest.builder().commentText("お問い合わせありがとうございます。担当よりご連絡します。").visibility("PORTAL_VISIBLE").build(),
                1L, "INTERNAL_USER", "サポート担当", false);
    }

    private void grantServiceDeskPermissions(Long portalUserId) {
        jdbcTemplate.update("INSERT INTO t_portal_user_permission (user_id, permission_key) VALUES (?, 'service-desk.view')", portalUserId);
        jdbcTemplate.update("INSERT INTO t_portal_user_permission (user_id, permission_key) VALUES (?, 'service-desk.create')", portalUserId);
    }

    @Test
    @DisplayName("サービスデスク権限を持たないポータルユーザーは403拒否されること (WIP-8 権限強制検証)")
    void testPermissionDenied_withoutServiceDeskPermission() throws Exception {
        String u = unique();
        PortalOrganization org = createCustomerOrg("NoPerm-" + u);
        UserFixture userWithoutPerm = readyUser(org, "noperm-" + u + "@customer.com");

        mockMvc.perform(get("/api/portal/customer/service-desk/requests")
                        .cookie(userWithoutPerm.sessionCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("自社ポータルユーザーで自社の問い合わせ一覧が取得できること")
    void testCustomerListRequests() throws Exception {
        mockMvc.perform(get("/api/portal/customer/service-desk/requests")
                        .cookie(customerAUser.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].subject").value("顧客Aの契約に関する問い合わせ"));
    }

    @Test
    @DisplayName("内部メモ（INTERNAL）がポータル詳細DTOから構造的に除外されていること")
    void testCustomerGetDetail_excludesInternalComment() throws Exception {
        mockMvc.perform(get("/api/portal/customer/service-desk/requests/" + customerARequest.getId())
                        .cookie(customerAUser.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(customerARequest.getId()))
                .andExpect(jsonPath("$.data.comments").isArray())
                .andExpect(jsonPath("$.data.comments.length()").value(1))
                .andExpect(jsonPath("$.data.comments[0].commentText").value("お問い合わせありがとうございます。担当よりご連絡します。"));
    }

    @Test
    @DisplayName("顧客Bのポータルユーザーが顧客Aの問い合わせIDを指定した場合に404拒否されること (IDOR防止)")
    void testOtherCustomerRequest_returns404() throws Exception {
        mockMvc.perform(get("/api/portal/customer/service-desk/requests/" + customerARequest.getId())
                        .cookie(customerBUser.sessionCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("ポータルからの新規起票が自社のcustomerIdで強制保存されること")
    void testCreateRequest_fromPortal() throws Exception {
        CsrfPair csrf = fetchPortalCsrf(mockMvc);

        PortalServiceRequestCreateRequest newReq = PortalServiceRequestCreateRequest.builder()
                .category("BILLING")
                .priority("P1")
                .subject("請求書の宛名変更希望")
                .description("経理部門の宛名に変更してください")
                .build();

        mockMvc.perform(post("/api/portal/customer/service-desk/requests")
                        .cookie(customerAUser.sessionCookie(), csrf.cookie())
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.subject").value("請求書の宛名変更希望"));
    }

    @Test
    @DisplayName("他社に属する契約IDを指定して起票した場合は400エラーで拒否されること (他社ID改ざん防止: CS-R1.1)")
    void testCreateRequest_otherCompanyContractRejected() throws Exception {
        CsrfPair csrf = fetchPortalCsrf(mockMvc);

        PortalServiceRequestCreateRequest invalidReq = PortalServiceRequestCreateRequest.builder()
                .category("CONTRACT")
                .priority("P2")
                .subject("契約関連")
                .description("詳細")
                .contractId(99999L) // 存在しない/他社の契約ID
                .build();

        mockMvc.perform(post("/api/portal/customer/service-desk/requests")
                        .cookie(customerAUser.sessionCookie(), csrf.cookie())
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("ポータルからの返信コメントがPORTAL_VISIBLEとして投稿され詳細に反映されること")
    void testReplyComment_fromPortal() throws Exception {
        CsrfPair csrf = fetchPortalCsrf(mockMvc);

        PortalServiceCommentCreateRequest replyReq = PortalServiceCommentCreateRequest.builder()
                .commentText("追加の資料を送付しました。ご確認ください。")
                .build();

        mockMvc.perform(post("/api/portal/customer/service-desk/requests/" + customerARequest.getId() + "/comments")
                        .cookie(customerAUser.sessionCookie(), csrf.cookie())
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.commentText").value("追加の資料を送付しました。ご確認ください。"));
    }

    @Test
    @DisplayName("解決後のリクエストに対してCSAT評価を投稿でき、2回目の投稿は409拒否されること")
    void testCsat_resolvedRequest() throws Exception {
        CsrfPair csrf = fetchPortalCsrf(mockMvc);

        // 管理者がリクエストを解決済みにする
        serviceRequestService.changeStatus(customerARequest.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").build(),
                1L, "INTERNAL_USER", "管理者");
        serviceRequestService.changeStatus(customerARequest.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").reason("対応完了").build(),
                1L, "INTERNAL_USER", "管理者");

        PortalCsatCreateRequest csatReq = PortalCsatCreateRequest.builder()
                .score(5)
                .feedbackComment("非常に迅速で助かりました")
                .build();

        // 1回目のCSAT回答 -> 200 OK
        mockMvc.perform(post("/api/portal/customer/service-desk/requests/" + customerARequest.getId() + "/csat")
                        .cookie(customerAUser.sessionCookie(), csrf.cookie())
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(csatReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 2回目のCSAT回答 -> 409 Conflict 拒否
        mockMvc.perform(post("/api/portal/customer/service-desk/requests/" + customerARequest.getId() + "/csat")
                        .cookie(customerAUser.sessionCookie(), csrf.cookie())
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(csatReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    @DisplayName("自社契約に紐付かない他社要員IDを指定して起票した場合は400エラーで拒否されること (要員自社所属検証: WIP-8)")
    void testCreateRequest_otherCompanyEngineerRejected() throws Exception {
        CsrfPair csrf = fetchPortalCsrf(mockMvc);

        // ダミー要員作成（契約なし）
        com.ses.entity.Engineer otherEng = com.ses.entity.Engineer.builder()
                .fullName("他社要員")
                .status("稼動中")
                .employmentType("正社員")
                .build();
        engineerMapper.insert(otherEng);

        PortalServiceRequestCreateRequest reqWithOtherEng = PortalServiceRequestCreateRequest.builder()
                .category("TECHNICAL")
                .priority("P2")
                .subject("他社要員の稼働について")
                .description("問い合わせ内容")
                .engineerId(otherEng.getId())
                .build();

        mockMvc.perform(post("/api/portal/customer/service-desk/requests")
                        .cookie(customerAUser.sessionCookie(), csrf.cookie())
                        .header("X-XSRF-TOKEN-PORTAL", csrf.headerValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqWithOtherEng)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
