package com.lkd.job;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lkd.common.VMSystem;
import com.lkd.entity.VendingMachineEntity;
import com.lkd.service.VendingMachineService;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.xxl.job.core.log.XxlJobLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @description:
 * @ClassName: VmSupplyJob
 * @author: Zle
 * @date: 2022-07-14 18:45
 * @version 1.0
*/
@Component
@Slf4j
public class VmSupplyJob {

    @Autowired
    private VendingMachineService vendingMachineService;

    /**
     * @description:
     * @author Zle
     * @date 2022/7/14 20:16
     * @param param 参数必须写这个，不管用不用
     * @return ReturnT<String>   返回值必须写这个，不管用不用
     */
    @XxlJob("supplyJobHandler")
    public ReturnT<String> supplyJobHandler(String param){
        //查询所有正在运行的售货机
        /*List<VendingMachineEntity> vendingMachineEntityList = vendingMachineService.list();

        List<VendingMachineEntity> vendingMachineEntityCollect = vendingMachineEntityList.stream()
                .filter(vm -> vm.getVmStatus().equals(VMSystem.VM_STATUS_RUNNING))
                .collect(Collectors.toList());*/
        QueryWrapper<VendingMachineEntity> queryWrapper = new QueryWrapper<VendingMachineEntity>();
        queryWrapper.lambda().eq(VendingMachineEntity::getVmStatus,VMSystem.VM_STATUS_RUNNING);
        List<VendingMachineEntity> vmList = vendingMachineService.list(queryWrapper);
        vmList.forEach(vm -> {
            XxlJobLogger.log("扫描售货机" + vm.getInnerCode());
            vendingMachineService.sendSupplyTask(vm.getInnerCode());
        });

        return ReturnT.SUCCESS;
    }
}
