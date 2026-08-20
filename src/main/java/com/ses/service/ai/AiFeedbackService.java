package com.ses.service.ai;

import com.ses.entity.AiFeedback;

public interface AiFeedbackService {

    AiFeedback record(Long itemId, String decision, String reasonCode, String comment);
}
