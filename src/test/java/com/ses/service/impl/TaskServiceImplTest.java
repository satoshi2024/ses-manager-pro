package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Task;
import com.ses.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TaskServiceImplTest {

    @Mock
    private TaskMapper taskMapper;

    @InjectMocks
    private TaskServiceImpl taskService;

    @BeforeEach
    void setUp() {
        // Set baseMapper for MyBatis-Plus ServiceImpl
        ReflectionTestUtils.setField(taskService, "baseMapper", taskMapper);
    }

    @Test
    void createTask_validInput_success() {
        Task task = new Task();
        task.setTitle("新規タスク");
        task.setAssigneeUserId(100L);

        Task created = taskService.createTask(task, 200L);
        assertNotNull(created);
        assertEquals("NOT_STARTED", created.getStatus());
        assertEquals("MEDIUM", created.getPriority());
        assertEquals(200L, created.getRequesterUserId());
    }

    @Test
    void createTask_missingTitle_throwsException() {
        Task task = new Task();
        task.setAssigneeUserId(100L);

        assertThrows(BusinessException.class, () -> taskService.createTask(task, 200L));
    }
}
