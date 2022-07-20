package com.lkd.http.controller;
import com.github.wxpay.plus.WXConfig;
import com.github.wxpay.plus.WxPayParam;
import com.github.wxpay.plus.WxPayTemplate;
import com.lkd.common.VMSystem;
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


    /**
     * 微信支付回调接口
     * @param request
     * @return
     */
    @RequestMapping("/payNotify")
    @ResponseBody
    public void payNotify(HttpServletRequest request, HttpServletResponse response){

        try {
            Map<String, String> result = wxPayTemplate.validPay(request.getInputStream());
            if("SUCCESS".equals( result.get("code") )){  //返回码成功
                String orderSn= result.get("order_sn");//获取订单号
                log.info("支付成功，修改订单状态和支付状态，订单号：{}",orderSn);
                //根据订单 编号获取订单信息
                OrderEntity orderEntity = orderService.getByOrderNo(orderSn);
                //修改订单状态和支付状态
                if (orderEntity != null) {
                    orderEntity.setStatus(VMSystem.ORDER_STATUS_PAYED);
                    orderEntity.setPayStatus(VMSystem.PAY_STATUS_PAYED);
                    orderService.updateById(orderEntity);
                }

                //发货
                orderService.vendout(orderSn,orderEntity.getSkuId(),orderEntity.getInnerCode());

            }
            //给微信支付一个成功的响应
            response.setContentType("text/xml");
            response.getWriter().write(WXConfig.RESULT);
        }catch (Exception e){
            log.error("支付回调处理失败",e);
        }
    }
}
