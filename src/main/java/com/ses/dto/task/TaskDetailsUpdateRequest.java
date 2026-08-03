package com.ses.dto.task;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public class TaskDetailsUpdateRequest {

    private Long assigneeUserId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDate;

    private Boolean clearDueDate;

    private String priority;

    public Long getAssigneeUserId() {
        return assigneeUserId;
    }

    public void setAssigneeUserId(Long assigneeUserId) {
        this.assigneeUserId = assigneeUserId;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public Boolean getClearDueDate() {
        return clearDueDate;
    }

    public void setClearDueDate(Boolean clearDueDate) {
        this.clearDueDate = clearDueDate;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }
}
