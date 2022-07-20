package com.lkd.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.lkd.common.VMSystem;
import com.lkd.config.ConsulConfig;
import com.lkd.config.TopicConfig;
import com.lkd.contract.OrderCheck;
import com.lkd.contract.VendoutContract;
import com.lkd.contract.VendoutData;
import com.lkd.dao.OrderDao;
import com.lkd.emq.MqttProducer;
import com.lkd.entity.OrderEntity;
import com.lkd.exception.LogicException;
import com.lkd.feign.UserService;
import com.lkd.feign.VMService;
import com.lkd.service.OrderService;
import com.lkd.utils.DistributedLock;
import com.lkd.utils.JsonUtil;
import com.lkd.utils.UUIDUtils;
import com.lkd.vo.*;
import lombok.extern.slf4j.Slf4j;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl extends ServiceImpl<OrderDao, OrderEntity> implements OrderService {

    @Autowired
    private MqttProducer mqttProducer;

    @Override
    public OrderEntity getByOrderNo(String orderNo) {
        QueryWrapper<OrderEntity> qw = new QueryWrapper<>();
        qw.lambda()
                .eq(OrderEntity::getOrderNo,orderNo);
        //qw.last("limit 1");
        return this.getOne(qw);
    }

    @Autowired
    private VMService vmService;

    @Autowired
    private UserService userService;

    /**
     * @description: 添加订单
     * @author Zle
     * @date 2022/7/16 14:21
     * @param payVO
     * @return Boolean
     */
    @Override
    public OrderEntity createOrder(PayVO payVO) {
        //通过远程调用vmService.hasCapacity 判断是否有库存 。
        Boolean aBoolean = vmService.hasCapacity(payVO.getInnerCode(), Long.valueOf(payVO.getSkuId()));
        if (!aBoolean){
            throw new LogicException("库存不足");
        }
        //构建实体类OrderEntity，售货机相关信息通过 vmService.getVMInfo获得。
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderNo(payVO.getInnerCode()+UUIDUtils.getUUID());
        orderEntity.setOpenId(payVO.getOpenId());
        orderEntity.setPayStatus(VMSystem.PAY_STATUS_NOPAY);
        VmVO vmInfo = vmService.getVMInfo(payVO.getInnerCode());
        BeanUtils.copyProperties(vmInfo,orderEntity);
        orderEntity.setAddr(vmInfo.getNodeAddr());
        //构建实体类OrderEntity，商品相关信息通过 vmService.getSku获得
        SkuVO sku = vmService.getSku(payVO.getSkuId());
        BeanUtils.copyProperties(sku,orderEntity);
        orderEntity.setAmount(sku.getRealPrice());
        orderEntity.setStatus(VMSystem.ORDER_STATUS_CREATE);

        //通过userService.getPartner 获得合作商，使用提成比例计算分成。
        PartnerVO partner = userService.getPartner(vmInfo.getOwnerId());
        //orderEntity.setBill(partner.getRatio() * sku.getPrice() /100);
        BigDecimal bg = new BigDecimal(sku.getRealPrice());
        int bill = bg.multiply(new BigDecimal(partner.getRatio())).divide(new BigDecimal(100),0, RoundingMode.HALF_UP).intValue();
        orderEntity.setBill(bill);
        //保存实体类。返回参数为订单实体类
        this.save(orderEntity);
        return orderEntity;
    }

    @Override
    public boolean vendout(String orderNo, Long skuId, String innercode) {
        VendoutContract vendoutContract = new VendoutContract();
        VendoutData vendoutData = new VendoutData();
        vendoutData.setOrderNo(orderNo);
        vendoutData.setSkuId(skuId);
        vendoutContract.setVendoutData(vendoutData);
        vendoutContract.setInnerCode(innercode);
        //向售货机微服务发送出货请求
        try {
            mqttProducer.send(TopicConfig.VMS_VENDOUT_TOPIC,2,vendoutContract);
        } catch (JsonProcessingException e) {
            log.info("send vendout req error.",e);
            return false;
        }

        return true;
    }


}
