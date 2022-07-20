package com.lkd.handler;

import com.google.common.base.Strings;
import com.lkd.business.MsgHandler;
import com.lkd.contract.VendoutResultContract;
import com.lkd.service.VendingMachineService;
import com.lkd.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;

/**
 * @description: 售货机处理出货结果消息
 * @ClassName: VendoutResultMsgHandler
 * @author: Zle
 * @date: 2022-07-19 18:53
 * @version 1.0
*/
public class VendoutResultMsgHandler implements MsgHandler {

    @Autowired
    private VendingMachineService vmService;

    @Override
    public void process(String jsonMsg) throws IOException {
        var vendoutResultContract = JsonUtil.getByJson(jsonMsg, VendoutResultContract.class);
        if(Strings.isNullOrEmpty(vendoutResultContract.getInnerCode())){
            return;
        }
        //处理出货逻辑
        vmService.vendoutResult(vendoutResultContract);
    }
}
