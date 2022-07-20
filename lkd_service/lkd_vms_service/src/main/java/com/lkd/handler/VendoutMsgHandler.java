package com.lkd.handler;

import com.google.common.base.Strings;
import com.lkd.business.MsgHandler;
import com.lkd.config.TopicConfig;
import com.lkd.contract.VendoutContract;
import com.lkd.emq.Topic;
import com.lkd.service.VendingMachineService;
import com.lkd.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * @description: 接收订单微服务发来的出货请求
 * @ClassName: VendoutMsgHandler
 * @author: Zle
 * @date: 2022-07-19 18:44
 * @version 1.0
*/
@Component
@Topic(TopicConfig.VMS_VENDOUT_TOPIC)
public class VendoutMsgHandler implements MsgHandler {

    @Autowired
    private VendingMachineService vendingMachineService;

    @Override
    public void process(String jsonMsg) throws IOException {
        VendoutContract vendoutContract = JsonUtil.getByJson(jsonMsg, VendoutContract.class);
        if (Strings.isNullOrEmpty(vendoutContract.getInnerCode())){
            return;
        }
        vendingMachineService.vendout(vendoutContract);
    }
}
