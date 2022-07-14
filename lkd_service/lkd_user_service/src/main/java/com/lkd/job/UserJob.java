package com.lkd.job;

import com.lkd.common.VMSystem;
import com.lkd.entity.UserEntity;
import com.lkd.http.controller.UserController;

import com.lkd.service.UserService;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.xxl.job.core.log.XxlJobLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * @description: 工单列表
 * @ClassName: UserJob
 * @author: Zle
 * @date: 2022-07-14 11:03
 * @version 1.0
*/
@Component
public class UserJob {

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private UserService userService;

    /**
     * @description: 每日工单量列表初始化
     * @author Zle
     * @date 2022/7/14 11:06
     * @param param param
     * @return ReturnT<String>
     */
    @XxlJob("workCountInitJobHandler")
    public ReturnT<String> workCountInitJobHandler(String param) throws Exception{
        try {
            XxlJobLogger.log("每日工单量列表初始化");

            //查询用户列表  构建数据（zset）
            List<UserEntity> list =userService.list();

            String nowDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            //String key = VMSystem.REGION_TASK_KEY_PREF + nowDate + "." +  ;
            list.forEach(user ->{
                if (!user.getRoleCode().equals("1001")){
                    String key = VMSystem.REGION_TASK_KEY_PREF + nowDate + "."
                            + user.getRegionId() + "."
                            + user.getRoleCode();
                    redisTemplate.opsForZSet().add(key,user.getId(),0);
                    redisTemplate.expire(key, Duration.ofDays(1));
                }
            });
            return ReturnT.SUCCESS;
        } catch (Exception e) {
            return ReturnT.FAIL;
        }
    }
}
