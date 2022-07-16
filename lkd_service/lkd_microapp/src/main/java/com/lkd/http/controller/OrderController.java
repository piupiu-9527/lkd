package com.lkd.http.controller;
import com.lkd.config.WXConfig;
import com.lkd.feign.OrderService;
import com.lkd.utils.OpenIDUtil;
import com.lkd.vo.PayVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/order")
@Slf4j
public class OrderController {

    @Autowired
    private WXConfig wxConfig;

    @Autowired
    private OrderService orderService;

    /**
     * 获取openId
     * @param jsCode
     * @return
     */
    @GetMapping("/openid/{jsCode}")
    public String getOpenid(@PathVariable("jsCode")  String jsCode){
        return OpenIDUtil.getOpenId( wxConfig.getAppId(),wxConfig.getAppSecret(),jsCode );
    }

    /**
     * 小程序请求支付
     * @param payVO
     * @return
     */
    @PostMapping("/requestPay")
    public Map<String,String> requestPay(@RequestBody PayVO payVO){
        return orderService.weixinPay(payVO);
    }



}
