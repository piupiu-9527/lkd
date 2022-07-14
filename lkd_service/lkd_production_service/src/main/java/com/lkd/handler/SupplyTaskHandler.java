package com.lkd.handler;

import com.lkd.business.MsgHandler;
import com.lkd.common.VMSystem;
import com.lkd.config.TopicConfig;
import com.lkd.contract.SupplyContract;
import com.lkd.emq.Topic;
import com.lkd.feign.VMService;
import com.lkd.http.vo.TaskDetailsViewModel;
import com.lkd.http.vo.TaskViewModel;
import com.lkd.service.TaskService;
import com.lkd.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * @description:
 * @ClassName: SupplyTaskHandler
 * @author: Zle
 * @date: 2022-07-14 20:27
 * @version 1.0
*/
@Component
@Topic(TopicConfig.TASK_SUPPLY_TOPIC)
@Slf4j
public class SupplyTaskHandler implements MsgHandler {

    @Autowired
    private VMService vmService;

    @Autowired
    private TaskService taskService;

    @Override
    public void process(String jsonMsg) throws IOException {

        try {
            //解析协议
            SupplyContract supplyContract = JsonUtil.getByJson(jsonMsg, SupplyContract.class);
            if (supplyContract == null) {
                return;
            }
            //查询区域
            var vm = vmService.getVMInfo(supplyContract.getInnerCode());
            if (vm == null) {
                return;
            }
            //查询该区域工单最少
            var userId = taskService.getLeastUser(vm.getRegionId(),false);
            if (userId == 0) {
                return;
            }

            //创建补货id
            TaskViewModel taskViewModel = new TaskViewModel();
            taskViewModel.setUserId(userId);
            taskViewModel.setCreateType(0);
            taskViewModel.setProductType(VMSystem.TASK_TYPE_SUPPLY);
            taskViewModel.setInnerCode(supplyContract.getInnerCode());
            taskViewModel.setAssignorId(0);//自动创建，无创建人
            taskViewModel.setDesc(jsonMsg);

            taskViewModel.setDetails(supplyContract.getSupplyData().stream().map(c -> {
                TaskDetailsViewModel taskDetailsViewModel = new TaskDetailsViewModel();
                taskDetailsViewModel.setChannelCode(c.getChannelId());
                //先设置期待补货的容量，等工单完成后在更新到当前容量中
                taskDetailsViewModel.setExpectCapacity(c.getCapacity());
                taskDetailsViewModel.setSkuId(c.getSkuId());
                taskDetailsViewModel.setSkuName(c.getSkuName());
                taskDetailsViewModel.setSkuImage(c.getSkuImage());
                return taskDetailsViewModel;
            }).collect(Collectors.toList()));

            taskService.createTask(taskViewModel);

        } catch (IOException e) {
            e.printStackTrace();
            log.error("创建自动补货工单出错" + e.getMessage());
        }

    }
}
