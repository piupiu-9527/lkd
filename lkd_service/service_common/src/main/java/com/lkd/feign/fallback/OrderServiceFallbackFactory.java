package com.lkd.feign.fallback;

import com.google.common.collect.Maps;
import com.lkd.entity.OrderEntity;
import com.lkd.feign.OrderService;
import com.lkd.vo.PayVO;
import feign.hystrix.FallbackFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * @description:
 * @ClassName: IOrderClientFallback
 * @author: Zle
 * @date: 2022-07-16 16:02
 * @version 1.0
*/
@Component
@Slf4j
public class OrderServiceFallbackFactory implements FallbackFactory<OrderService> {
    @Override
    public OrderService create(Throwable throwable) {
        return new OrderService() {
            @Override
            public Map<String, String> weixinPay(PayVO payVO) {
                HashMap<String, String> result = Maps.newHashMap();
                result.put("msg",  "系统繁忙");  //错误描述
                result.put("code","RESULT_FAIL");//失败
                return result;
            }
        };
    }
}
