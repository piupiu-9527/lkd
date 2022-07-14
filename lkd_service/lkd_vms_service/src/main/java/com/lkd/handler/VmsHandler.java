package com.lkd.handler;

import com.google.common.base.Strings;
import com.lkd.business.MsgHandler;
import com.lkd.common.VMSystem;
import com.lkd.config.TopicConfig;
import com.lkd.contract.TaskCompleteContract;
import com.lkd.emq.Topic;
import com.lkd.service.VendingMachineService;
import com.lkd.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * @description:
 * @ClassName: ProductHandler
 * @author: Zle
 * @date: 2022-07-12 14:50
 * @version 1.0
*/

@Component
@Topic(TopicConfig.VMS_COMPLETED_TOPIC)
public class VmsHandler implements MsgHandler {

    @Autowired
    private VendingMachineService vendingMachineService;

    @Override
    public void process(String jsonMsg) throws IOException {

        TaskCompleteContract taskCompleteContract = JsonUtil.getByJson(jsonMsg, TaskCompleteContract.class);
        if (taskCompleteContract == null || Strings.isNullOrEmpty(taskCompleteContract.getInnerCode())){
            return;
        }

        //投放工单，售货机改为运营状态
        if (taskCompleteContract.getTaskType() == VMSystem.TASK_TYPE_DEPLOY){
            vendingMachineService.updateStatus(taskCompleteContract.getInnerCode(),VMSystem.VM_STATUS_RUNNING);
        }

        //撤机工单，售货机改为撤机
        if (taskCompleteContract.getTaskType() == VMSystem.TASK_TYPE_REVOKE){
            vendingMachineService.updateStatus(taskCompleteContract.getInnerCode(),VMSystem.VM_STATUS_REVOKE);
        }


    }
}
