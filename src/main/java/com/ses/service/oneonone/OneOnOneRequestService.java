package com.ses.service.oneonone;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDate;
import java.util.List;

/**
 * 1on1申請/実施記録サービス（T092 / engineer-self-service-portal-v2 B2）。
 * 状態機械: 申請→日程確定→実施済 / 取消（状態CAS。design §6.3）。
 * private_note_ref（confidential相談）はHR/管理者のみ可視。通常のDTOには出さない（design §5/§6.2）。
 */
public interface OneOnOneRequestService {

    String STATUS_REQUESTED = "申請";
    String STATUS_SCHEDULED = "日程確定";
    String STATUS_DONE = "実施済";
    String STATUS_CANCELLED = "取消";

    /** 本人（要員） */
    Page<OneOnOneDto> pageOwn(Long engineerId, String status, long current, long size);

    OneOnOneDto detailOwn(Long engineerId, Long id);

    OneOnOneDto create(Long engineerId, Long counterpartUserId, List<LocalDate> candidateDates);

    OneOnOneDto cancelOwn(Long engineerId, Long id);

    /** 管理 */
    Page<OneOnOneDto> pageManagement(String engineerName, String status, long current, long size);

    OneOnOneDto detailManagement(Long id);

    /** 日程確定（申請→日程確定。scheduled_atを固定）。 */
    OneOnOneDto schedule(Long id, LocalDate scheduledAt);

    /** 実施済（日程確定→実施済。本人公開noteを記録）。 */
    OneOnOneDto complete(Long id, String employeeVisibleNote);

    /** 取消（管理側。日程確定前後を問わず）。 */
    OneOnOneDto cancel(Long id, String reason);

    /** confidential相談の保存・更新（HR/管理者のみ）。private_note_refを文書台帳(PRIVATE_NOTE)へ固定する。 */
    OneOnOneDto savePrivateNote(Long id, String note);

    record OneOnOneDto(Long id, Long engineerId, String engineerName, Long counterpartUserId,
                       String counterpartName, List<LocalDate> candidateDates, LocalDate scheduledAt,
                       String status, String employeeVisibleNote, String privateNoteRef,
                       java.time.LocalDateTime createdAt) {
    }
}
