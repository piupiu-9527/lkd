package com.lkd.feign;

import com.lkd.entity.OrderEntity;
import com.lkd.feign.fallback.OrderServiceFallbackFactory;
import com.lkd.feign.fallback.VmServiceFallbackFactory;
import com.lkd.vo.PayVO;
import com.lkd.vo.VmVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * @description: 远程调用微信支付下单
 * @ClassName: OrderService
 * @author: Zle
 * @date: 2022-07-16 16:01
 * @version 1.0
*/
@FeignClient(value = "order-service",fallbackFactory = OrderServiceFallbackFactory.class)
public interface OrderService {

    //@PostMapping("/order/weixinPay")
    //public String PlaceOrder(@RequestBody PayVO payVO);
    /**
     * 微信支付下单
     * @param payVO
     * @return
     */
    @PostMapping("/order/weixinPay")
    Map<String,String> weixinPay(@RequestBody PayVO payVO);
}
