package com.github.kfcfans.oms.worker.persistence;


import com.github.kfcfans.common.RemoteConstant;
import com.github.kfcfans.common.utils.CommonUtils;
import com.github.kfcfans.common.utils.SupplierPlus;
import com.github.kfcfans.oms.worker.common.constants.TaskConstant;
import com.github.kfcfans.oms.worker.common.constants.TaskStatus;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务持久化服务
 *
 * @author tjq
 * @since 2020/3/17
 */
@Slf4j
public class TaskPersistenceService {

    // 默认重试参数
    private static final int RETRY_TIMES = 3;
    private static final long RETRY_INTERVAL_MS = 100;

    private static volatile boolean initialized = false;
    public static TaskPersistenceService INSTANCE = new TaskPersistenceService();

    private TaskPersistenceService() {
    }

    private TaskDAO taskDAO = new TaskDAOImpl();

    public void init() throws Exception {
        if (initialized) {
            return;
        }
        taskDAO.initTable();
        initialized = true;
    }

    public boolean save(TaskDO task) {

        try {
            return execute(() -> taskDAO.save(task));
        }catch (Exception e) {
            log.error("[TaskPersistenceService] save task{} failed.",  task);
        }
        return false;
    }

    public boolean batchSave(List<TaskDO> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return true;
        }
        try {
            return execute(() -> taskDAO.batchSave(tasks));
        }catch (Exception e) {
            log.error("[TaskPersistenceService] batchSave tasks failed.", e);
        }
        return false;
    }

    /**
     * 依靠主键更新 Task
     */
    public boolean updateTask(Long instanceId, String taskId, TaskDO updateEntity) {
        try {
            updateEntity.setLastModifiedTime(System.currentTimeMillis());
            SimpleTaskQuery query = genKeyQuery(instanceId, taskId);
            return execute(() -> taskDAO.simpleUpdate(query, updateEntity));
        }catch (Exception e) {
            log.error("[TaskPersistenceService] updateTask failed.", e);
        }
        return false;
    }

    /**
     * 更新被派发到已经失联的 ProcessorTracker 的任务，重新执行
     * update task_info
     * set address = 'N/A', status = 0
     * where address in () and status not in (5,6)
     */
    public boolean updateLostTasks(List<String> addressList) {

        TaskDO updateEntity = new TaskDO();
        updateEntity.setAddress(RemoteConstant.EMPTY_ADDRESS);
        updateEntity.setStatus(TaskStatus.WAITING_DISPATCH.getValue());
        updateEntity.setLastModifiedTime(System.currentTimeMillis());

        SimpleTaskQuery query = new SimpleTaskQuery();
        String queryConditionFormat = "address in %s and status not in (%d, %d)";
        String queryCondition = String.format(queryConditionFormat, CommonUtils.getInStringCondition(addressList), TaskStatus.WORKER_PROCESS_FAILED.getValue(), TaskStatus.WORKER_PROCESS_SUCCESS.getValue());
        query.setQueryCondition(queryCondition);

        try {
            return execute(() -> taskDAO.simpleUpdate(query, updateEntity));
        }catch (Exception e) {
            log.error("[TaskPersistenceService] updateLostTasks failed.", e);
        }
        return false;
    }

    /**
     * 获取 MapReduce 或 Broadcast 的最后一个任务
     */
    public Optional<TaskDO> getLastTask(Long instanceId) {

        try {
            SimpleTaskQuery query = new SimpleTaskQuery();
            query.setInstanceId(instanceId);
            query.setTaskName(TaskConstant.LAST_TASK_NAME);
            return execute(() -> {
                List<TaskDO> taskDOS = taskDAO.simpleQuery(query);
                if (CollectionUtils.isEmpty(taskDOS)) {
                    return Optional.empty();
                }
                return Optional.of(taskDOS.get(0));
            });
        }catch (Exception e) {
            log.error("[TaskPersistenceService] get last task for instance(id={}) failed.", instanceId, e);
        }

        return Optional.empty();
    }

    public List<TaskDO> getAllTask(Long instanceId) {
        try {
            SimpleTaskQuery query = new SimpleTaskQuery();
            query.setInstanceId(instanceId);
            return execute(() -> {
                return taskDAO.simpleQuery(query);
            });
        }catch (Exception e) {
            log.error("[TaskPersistenceService] getAllTask for instance(id={}) failed.", instanceId, e);
        }
        return Lists.newArrayList();
    }

    /**
     * 获取指定状态的Task
     */
    public List<TaskDO> getTaskByStatus(Long instanceId, TaskStatus status, int limit) {
        try {
            SimpleTaskQuery query = new SimpleTaskQuery();
            query.setInstanceId(instanceId);
            query.setStatus(status.getValue());
            query.setLimit(limit);
            return execute(() -> taskDAO.simpleQuery(query));
        }catch (Exception e) {
            log.error("[TaskPersistenceService] getTaskByStatus failed, params is instanceId={},status={}.", instanceId, status, e);
        }
        return Lists.newArrayList();
    }

    /**
     * 获取 TaskTracker 管理的子 task 状态统计信息
     * TaskStatus -> num
     */
    public Map<TaskStatus, Long> getTaskStatusStatistics(Long instanceId) {
        try {

            SimpleTaskQuery query = new SimpleTaskQuery();
            query.setInstanceId(instanceId);
            query.setQueryContent("status, count(*) as num");
            query.setOtherCondition("GROUP BY status");

            return execute(() -> {
                List<Map<String, Object>> dbRES = taskDAO.simpleQueryPlus(query);
                Map<TaskStatus, Long> result = Maps.newHashMap();
                dbRES.forEach(row -> {
                    // H2 数据库都是大写...
                    int status = Integer.parseInt(String.valueOf(row.get("STATUS")));
                    long num = Long.parseLong(String.valueOf(row.get("NUM")));
                    result.put(TaskStatus.of(status), num);
                });
                return result;
            });
        }catch (Exception e) {
            log.error("[TaskPersistenceService] getTaskStatusStatistics for instance(id={}) failed.", instanceId, e);
        }
        return Maps.newHashMap();
    }

    /**
     * 查询 taskId -> taskResult，reduce阶段或postProcess 阶段使用
     */
    public Map<String, String> getTaskId2ResultMap(Long instanceId) {
        try {
            return execute(() -> taskDAO.queryTaskId2TaskResult(instanceId));
        }catch (Exception e) {
            log.error("[TaskPersistenceService] getTaskId2ResultMap for instance(id={}) failed.", instanceId, e);
        }
        return Maps.newHashMap();
    }

    /**
     * 查询任务状态（只查询 status，节约 I/O 资源 -> 测试表明，效果惊人...磁盘I/O果然是重要瓶颈...）
     */
    public Optional<TaskStatus> getTaskStatus(Long instanceId, String taskId) {

        try {
            SimpleTaskQuery query = genKeyQuery(instanceId, taskId);
            query.setQueryContent("STATUS");
            return execute(() -> {
                List<Map<String, Object>> rows = taskDAO.simpleQueryPlus(query);
                return Optional.of(TaskStatus.of((int) rows.get(0).get("STATUS")));
            });
        }catch (Exception e) {
            log.error("[TaskPersistenceService] getTaskStatus failed, instanceId={},taskId={}.", instanceId, taskId, e);
        }
        return Optional.empty();
    }

    /**
     * 查询任务失败数量（只查询 failed_cnt，节约 I/O 资源）
     */
    public Optional<Integer> getTaskFailedCnt(Long instanceId, String taskId) {

        try {
            SimpleTaskQuery query = genKeyQuery(instanceId, taskId);
            query.setQueryContent("failed_cnt");
            return execute(() -> {
                List<Map<String, Object>> rows = taskDAO.simpleQueryPlus(query);
                // 查询成功不可能为空
                return Optional.of((Integer) rows.get(0).get("FAILED_CNT"));
            });
        }catch (Exception e) {
            log.error("[TaskPersistenceService] getTaskFailedCnt failed, instanceId={},taskId={}.", instanceId, taskId, e);
        }
        return Optional.empty();
    }


    /**
     * 批量更新 Task 状态
     */
    public boolean batchUpdateTaskStatus(Long instanceId, List<String> taskIds, TaskStatus status, String result) {
        try {
            return execute(() -> {

                SimpleTaskQuery query = new SimpleTaskQuery();
                query.setInstanceId(instanceId);
                query.setQueryCondition(String.format(" task_id in %s ", CommonUtils.getInStringCondition(taskIds)));

                TaskDO updateEntity = new TaskDO();
                updateEntity.setStatus(status.getValue());
                updateEntity.setResult(result);
                return taskDAO.simpleUpdate(query, updateEntity);
            });
        }catch (Exception e) {
            log.error("[TaskPersistenceService] updateTaskStatus failed, instanceId={},taskIds={},status={},result={}.",
                    instanceId, taskIds, status, result, e);
        }
        return false;
    }


    public boolean deleteAllTasks(Long instanceId) {
        try {
            SimpleTaskQuery condition = new SimpleTaskQuery();
            condition.setInstanceId(instanceId);
            return execute(() -> taskDAO.simpleDelete(condition));
        }catch (Exception e) {
            log.error("[TaskPersistenceService] deleteAllTasks failed, instanceId={}.", instanceId, e);
        }
        return false;
    }

    public List<TaskDO> listAll() {
        try {
            return execute(() -> {
                SimpleTaskQuery query = new SimpleTaskQuery();
                query.setQueryCondition("1 = 1");
                return taskDAO.simpleQuery(query);
            });
        }catch (Exception e) {
            log.error("[TaskPersistenceService] listAll failed.", e);
        }
        return Collections.emptyList();
    }

    private static SimpleTaskQuery genKeyQuery(Long instanceId, String taskId) {
        SimpleTaskQuery condition = new SimpleTaskQuery();
        condition.setInstanceId(instanceId);
        condition.setTaskId(taskId);
        return condition;
    }

    private static  <T> T execute(SupplierPlus<T> executor) throws Exception {
        return CommonUtils.executeWithRetry(executor, RETRY_TIMES, RETRY_INTERVAL_MS);
    }
}