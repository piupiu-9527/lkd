package com.lkd.handler;

import com.lkd.business.MsgHandler;
import com.lkd.config.TopicConfig;
import com.lkd.contract.SupplyContract;
import com.lkd.emq.Topic;
import com.lkd.service.VendingMachineService;
import com.lkd.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * @description: 补货消息处理
 * @ClassName: SupplyMsgHandler
 * @author: Zle
 * @date: 2022-07-12 20:34
 * @version 1.0
*/
@Component
@Topic(TopicConfig.VMS_SUPPLY_TOPIC)
public class SupplyMsgHandler implements MsgHandler {

    @Autowired
    private VendingMachineService vmService;

    @Override
    public void process(String jsonMsg) throws IOException {
        //解析补货协议
        SupplyContract supplyContract = JsonUtil.getByJson(jsonMsg, SupplyContract.class);
        //更新售货机库存
        vmService.supply(supplyContract);
    }
}
