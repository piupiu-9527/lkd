package com.lkd.handler;

import com.lkd.business.MsgHandler;
import com.lkd.common.VMSystem;
import com.lkd.config.TopicConfig;
import com.lkd.contract.VmStatusContract;
import com.lkd.emq.Topic;
import com.lkd.exception.LogicException;
import com.lkd.feign.VMService;
import com.lkd.http.vo.TaskViewModel;
import com.lkd.service.TaskService;
import com.lkd.utils.JsonUtil;
import com.lkd.vo.VmVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.io.IOException;

/**
 * @description: 售货机状态消息的接收处理类
 * @ClassName: VmStatusHandle
 * @author: Zle
 * @date: 2022-07-14 15:33
 * @version 1.0
*/
@Component
@Topic(TopicConfig.VMS_STATUS_TOPIC)
@Slf4j
public class VmStatusHandle implements MsgHandler {

    @Autowired
    private TaskService taskService;

    @Autowired
    private VMService vmService;

    @Override
    public void process(String jsonMsg) throws IOException {
        var vmStatusContract = JsonUtil.getByJson(jsonMsg, VmStatusContract.class);
        if (vmStatusContract == null  || CollectionUtils.isEmpty(vmStatusContract.getStatusInfo())) {
            return;
        }

        //非正常状态，创建维修工单
        //通过stream流，的anymatch方法找到是否有部件的状态为false，有一个false，anymatch就会返回true
        if (vmStatusContract.getStatusInfo().stream().anyMatch(s ->s.isStatus() ==false)) {
            try {
                //根据收获节白鸥，查询售货机（获取regionId）
                var vmInfo = vmService.getVMInfo(vmStatusContract.getInnerCode());
                if (vmInfo == null) {
                    return;
                }
                var userId = taskService.getLeastUser(vmInfo.getRegionId(), true);
                if (userId != 0){
                    var task = new TaskViewModel();
                    task.setUserId(userId);
                    task.setInnerCode(vmInfo.getInnerCode());
                    task.setProductType(VMSystem.TASK_TYPE_REPAIR);
                    task.setCreateType(0);
                    task.setDesc(jsonMsg);
                    task.setAssignorId(0);
                    taskService.createTask(task);

                }
            } catch (LogicException e) {
                log.error("创建自动维修工单失败，msg is: ",jsonMsg);
            }

        }

    }
}
