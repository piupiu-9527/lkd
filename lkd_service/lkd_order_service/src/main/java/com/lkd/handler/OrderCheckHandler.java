package com.lkd.handler;

import com.google.common.base.Strings;
import com.lkd.business.MsgHandler;
import com.lkd.common.VMSystem;
import com.lkd.config.TopicConfig;
import com.lkd.contract.OrderCheck;
import com.lkd.emq.Topic;
import com.lkd.entity.OrderEntity;
import com.lkd.service.OrderService;
import com.lkd.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * @description: 接收延迟消息
 * @ClassName: OrderCheckHandler
 * @author: Zle
 * @date: 2022-07-19 14:49
 * @version 1.0
*/
@Component
@Topic(TopicConfig.ORDER_CHECK_TOPIC)
@Slf4j
public class OrderCheckHandler implements MsgHandler {

    @Autowired
    private OrderService orderService;

    @Override
    public void process(String jsonMsg) throws IOException {
        OrderCheck orderCheck = JsonUtil.getByJson(jsonMsg, OrderCheck.class);
        if (orderCheck == null || Strings.isNullOrEmpty(orderCheck.getOrderNo())) {
            //查询订单
            OrderEntity orderEntity = orderService.getByOrderNo(orderCheck.getOrderNo());
            if (orderEntity == null){
                return;
            }
            //5分钟后订单未支付
            if (orderEntity.getStatus().equals(VMSystem.ORDER_STATUS_CREATE)){
                log.info("订单无效处理，订单号：{}",orderCheck.getOrderNo());
                orderEntity.setStatus(VMSystem.ORDER_STATUS_INVALID);//无效状态
                orderService.updateById(orderEntity);
            }
        }
    }
}
