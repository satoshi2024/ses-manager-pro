package com.ses.service.lifecycle;

import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.LifecycleTemplateTask;
import com.ses.entity.LifecycleTemplateTaskDep;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ライフサイクルタスク有向非巡回グラフ (DAG) 検証・評価コンポーネント
 */
@Component
public class LifecycleDagValidator {

    /**
     * テンプレートタスク定義と依存関係の巡回を検証する (Kahn's algorithm)。
     */
    public void validateTemplateDag(List<LifecycleTemplateTask> tasks, List<LifecycleTemplateTaskDep> deps) {
        if (tasks == null || tasks.isEmpty() || deps == null || deps.isEmpty()) {
            return;
        }

        Set<String> taskCodes = new HashSet<>();
        for (LifecycleTemplateTask task : tasks) {
            taskCodes.add(task.getTaskCode());
        }

        Map<String, Set<String>> graph = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (String code : taskCodes) {
            graph.put(code, new HashSet<>());
            inDegree.put(code, 0);
        }

        for (LifecycleTemplateTaskDep dep : deps) {
            String pred = dep.getPredecessorTaskCode();
            String succ = dep.getSuccessorTaskCode();

            if (!taskCodes.contains(pred) || !taskCodes.contains(succ)) {
                throw BusinessException.of("error.lifecycle.invalidDependencyTask",
                        "依存関係に存在しないタスクコードが含まれています: " + pred + " -> " + succ);
            }

            if (pred.equals(succ)) {
                throw BusinessException.of("error.lifecycle.cyclicDependency",
                        "自己依存が検出されました: " + pred);
            }

            if (graph.get(pred).add(succ)) {
                inDegree.put(succ, inDegree.get(succ) + 1);
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        int visitedCount = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visitedCount++;

            for (String neighbor : graph.get(current)) {
                int newDegree = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newDegree);
                if (newDegree == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (visitedCount < taskCodes.size()) {
            throw BusinessException.of("error.lifecycle.cyclicDependency",
                    "タスク依存関係に循環（デッドロック）が検出されました");
        }
    }

    /**
     * DTOベースのDAG検証
     */
    public void validateDtoDag(List<LifecycleTemplateTaskDto> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        Set<String> taskCodes = new HashSet<>();
        for (LifecycleTemplateTaskDto task : tasks) {
            if (task.getTaskCode() == null || task.getTaskCode().isBlank()) {
                throw BusinessException.of("error.lifecycle.blankTaskCode");
            }
            if (!taskCodes.add(task.getTaskCode())) {
                throw BusinessException.of("error.lifecycle.duplicateTaskCode", task.getTaskCode());
            }
        }

        Map<String, Set<String>> graph = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (String code : taskCodes) {
            graph.put(code, new HashSet<>());
            inDegree.put(code, 0);
        }

        for (LifecycleTemplateTaskDto task : tasks) {
            if (task.getPredecessorTaskCodes() != null) {
                for (String pred : task.getPredecessorTaskCodes()) {
                    if (!taskCodes.contains(pred)) {
                        throw BusinessException.of("error.lifecycle.invalidDependencyTask", pred);
                    }
                    if (pred.equals(task.getTaskCode())) {
                        throw BusinessException.of("error.lifecycle.cyclicDependency", pred);
                    }
                    if (graph.get(pred).add(task.getTaskCode())) {
                        inDegree.put(task.getTaskCode(), inDegree.get(task.getTaskCode()) + 1);
                    }
                }
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        int visitedCount = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visitedCount++;

            for (String neighbor : graph.get(current)) {
                int newDegree = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newDegree);
                if (newDegree == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (visitedCount < taskCodes.size()) {
            throw BusinessException.of("error.lifecycle.cyclicDependency",
                    "タスク依存関係に循環（デッドロック）が検出されました");
        }
    }
}
