package com.ses.service;

import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.impl.AssetAssignmentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** F-9 / §3: 免除の承認申請は資産・貸与より前にロックする。 */
@ExtendWith(MockitoExtension.class)
class AssetAssignmentWaiverLockOrderTest {
    @Mock AssetAssignmentMapper assignments;
    @Mock AssetMapper assets;
    @Mock AssetEventService events;
    @Mock DocumentLinkMapper documents;
    @Mock ApprovalRequestMapper approvals;
    @Mock LifecycleTaskMapper tasks;
    @Mock LifecycleCaseMapper cases;
    @InjectMocks AssetAssignmentServiceImpl service;

    @Test void 承認申請から資産と貸与の順でロックする() {
        AssetAssignment assignment = AssetAssignment.builder().assetId(7L).assigneeType("ENGINEER").assigneeId(4L).status("ACTIVE").version(0).build(); assignment.setId(5L);
        Asset asset = Asset.builder().status("ASSIGNED").version(0).build(); asset.setId(7L);
        ApprovalRequest approval = ApprovalRequest.builder().requestType("LIFECYCLE_EXCEPTION").status("APPROVED").targetType("ASSET_ASSIGNMENT").targetId(5L).build(); approval.setId(9L);
        when(approvals.selectByIdForUpdate(9L)).thenReturn(approval);
        when(assignments.selectById(5L)).thenReturn(assignment);
        when(assets.selectByIdForUpdate(7L)).thenReturn(asset);
        when(assignments.selectByIdForUpdate(5L)).thenReturn(assignment);
        when(assets.updateStatusWithCas(7L, "ASSIGNED", "IN_STOCK", 0)).thenReturn(1);
        when(assignments.markWaivedWithCas(eq(5L), any(), any(), eq(0))).thenReturn(1);
        service.waiveAssignment(5L, "承認済み", 9L, 1L);
        var order = inOrder(approvals, assets, assignments);
        order.verify(approvals).selectByIdForUpdate(9L);
        order.verify(assignments).selectById(5L);
        order.verify(assets).selectByIdForUpdate(7L);
        order.verify(assignments).selectByIdForUpdate(5L);
    }
}
