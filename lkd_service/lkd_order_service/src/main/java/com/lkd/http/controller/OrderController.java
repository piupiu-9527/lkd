package com.lkd.http.controller;
import com.github.wxpay.plus.WXConfig;
import com.github.wxpay.plus.WxPayParam;
import com.github.wxpay.plus.WxPayTemplate;
import com.lkd.entity.OrderEntity;
import com.lkd.service.OrderService;
import com.lkd.vo.PayVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController
@RequestMapping("/order")
@Slf4j
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private WxPayTemplate wxPayTemplate;


    /**
     * 微信小程序支付
     * @param payVO
     * @return
     */
    @PostMapping("/weixinPay")
    public Map<String, String> weixinPay(@RequestBody PayVO payVO){
        var orderEntity = orderService.createOrder(payVO);//创建订单
        //封装支付请求对象调用支付
        var param=new WxPayParam();
        param.setBody(orderEntity.getSkuName());
        param.setOutTradeNo(orderEntity.getOrderNo());
        param.setTotalFee(orderEntity.getAmount().intValue());
        param.setOpenid(orderEntity.getOpenId());
        return wxPayTemplate.requestPay(param);
    }


}
