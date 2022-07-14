package com.lkd.service.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.lkd.common.VMSystem;
import com.lkd.config.TopicConfig;
import com.lkd.contract.SupplyChannel;
import com.lkd.contract.SupplyContract;
import com.lkd.contract.TaskCompleteContract;
import com.lkd.dao.TaskDao;
import com.lkd.emq.MqttProducer;
import com.lkd.entity.TaskDetailsEntity;
import com.lkd.entity.TaskEntity;
import com.lkd.entity.TaskStatusTypeEntity;
import com.lkd.entity.TaskTypeEntity;
import com.lkd.exception.LogicException;
import com.lkd.feign.UserService;
import com.lkd.feign.VMService;
import com.lkd.http.vo.CancelTaskViewModel;
import com.lkd.http.vo.TaskDetailsViewModel;
import com.lkd.http.vo.TaskViewModel;
import com.lkd.service.TaskDetailsService;
import com.lkd.service.TaskService;
import com.lkd.service.TaskStatusTypeService;
import com.lkd.utils.JsonUtil;
import com.lkd.vo.Pager;
import com.lkd.vo.UserVO;
import com.lkd.vo.VmVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class TaskServiceImpl extends ServiceImpl<TaskDao,TaskEntity> implements TaskService{

    @Autowired
    private TaskStatusTypeService statusTypeService;

    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    @Autowired
    private VMService vmService;

    @Autowired
    private UserService userService;

    @Autowired
    private TaskDetailsService taskDetailsService;

    @Autowired
    private MqttProducer mqttProducer;


    @Override
    public Pager<TaskEntity> search(Long pageIndex, Long pageSize, String innerCode, Integer userId, String taskCode, Integer status, Boolean isRepair, String start, String end) {
        Page<TaskEntity> page = new Page<>(pageIndex,pageSize);
        LambdaQueryWrapper<TaskEntity> qw = new LambdaQueryWrapper<>();
        if(!Strings.isNullOrEmpty(innerCode)){
            qw.eq(TaskEntity::getInnerCode,innerCode);
        }
        if(userId != null && userId > 0){
            qw.eq(TaskEntity::getUserId,userId);
        }
        if(!Strings.isNullOrEmpty(taskCode)){
            qw.like(TaskEntity::getTaskCode,taskCode);
        }
        if(status != null && status > 0){
            qw.eq(TaskEntity::getTaskStatus,status);
        }
        if(isRepair != null){
            if(isRepair){
                qw.ne(TaskEntity::getProductTypeId, VMSystem.TASK_TYPE_SUPPLY);
            }else {
                qw.eq(TaskEntity::getProductTypeId,VMSystem.TASK_TYPE_SUPPLY);
            }
        }
        if(!Strings.isNullOrEmpty(start) && !Strings.isNullOrEmpty(end)){
            qw
                    .ge(TaskEntity::getCreateTime, LocalDate.parse(start, DateTimeFormatter.ISO_LOCAL_DATE))
                    .le(TaskEntity::getCreateTime,LocalDate.parse(end,DateTimeFormatter.ISO_LOCAL_DATE));
        }
        //根据最后更新时间倒序排序
        qw.orderByDesc(TaskEntity::getUpdateTime);

        return Pager.build(this.page(page,qw));
    }



    @Override
    public List<TaskStatusTypeEntity> getAllStatus() {
        QueryWrapper<TaskStatusTypeEntity> qw = new QueryWrapper<>();
        qw.lambda()
                .ge(TaskStatusTypeEntity::getStatusId,VMSystem.TASK_STATUS_CREATE);

        return statusTypeService.list(qw);
    }







    @Transactional
    @Override
    public boolean createTask(TaskViewModel taskViewModel) throws LogicException {

        if(hasTask(taskViewModel.getInnerCode(),taskViewModel.getProductType())) {
            throw new LogicException("该机器有未完成的同类型工单");
        }

        //判断设备是否存在
        VmVO vmInfo = vmService.getVMInfo(taskViewModel.getInnerCode());
        if (vmInfo == null) {
            throw  new LogicException("该设备不存在");
        }

        //校验售货机状态
        checkCreateTask(vmInfo.getVmStatus(),taskViewModel.getProductType());//验证售货机状态
        //判断用户
        UserVO user = userService.getUser(taskViewModel.getUserId());
        if (user == null) {
            throw  new LogicException("用户不存在");
        }

        TaskEntity taskEntity = new TaskEntity();
        BeanUtils.copyProperties(taskViewModel,taskEntity);
        taskEntity.setTaskCode(createTaskCode());
        taskEntity.setTaskStatus(VMSystem.TASK_STATUS_CREATE);
        taskEntity.setProductTypeId(taskViewModel.getProductType());
        taskEntity.setAddr(vmInfo.getNodeAddr());//地址
        taskEntity.setRegionId(vmInfo.getRegionId());//区域
        taskEntity.setUserName(user.getUserName());//用户名
        this.save(taskEntity);

        //判断是否为补货工单
        if (VMSystem.TASK_TYPE_SUPPLY.equals(taskViewModel.getProductType())){

            for (TaskDetailsViewModel detail : taskViewModel.getDetails()) {
                TaskDetailsEntity taskDetailsEntity = new TaskDetailsEntity();
                BeanUtils.copyProperties(detail,taskDetailsEntity);
                taskDetailsEntity.setTaskId(taskEntity.getTaskId());
                taskDetailsService.save(taskDetailsEntity);
            }

        }

        updateTaskZSet(taskEntity,1);

        return true;
    }

    /**
     * @description: 更新工单量列表
     * @author Zle
     * @date 2022/7/14 14:38
     * @param taskEntity
     * @param score
     */
    private void updateTaskZSet(TaskEntity taskEntity, int score) {
        String roleCode = "1003";  //运维
        if (taskEntity.getProductTypeId().intValue() == 2){  //如果是补货工单
            roleCode = "1002";  //运营
        }
        String key = VMSystem.REGION_TASK_KEY_PREF
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "." + taskEntity.getRegionId()
                + "." + roleCode;
        //incrementScore 中包含两个操作get score+1  incrementScore方法属于原子增，不用考虑锁
        redisTemplate.opsForZSet().incrementScore(key,taskEntity.getUserId(),score);
    }

    /**
     * @description: 校验售货机状态
     * @author Zle
     * @date 2022/7/11 18:18
     * @param
     * @param
     */
    //private void checkTypeAndStatus(TaskViewModel taskViewModel, TaskEntity taskEntity) {
    //    if (VMSystem.TASK_TYPE_DEPLOY.equals(taskViewModel.getProductType())){
    //        if (VMSystem.VM_STATUS_RUNNING.equals(taskEntity.getTaskStatus())){
    //            throw new LogicException("当前设备为运营状态，不能投放");
    //        }
    //    }
    //
    //    if (VMSystem.TASK_TYPE_REVOKE.equals(taskViewModel.getProductType())){
    //        if (!VMSystem.TASK_TYPE_REVOKE.equals(taskEntity.getTaskStatus())){
    //            throw new LogicException("当前设备不是运营状态，不能撤机");
    //        }
    //    }
    //
    //    if (VMSystem.TASK_TYPE_SUPPLY.equals(taskViewModel.getProductType())){{
    //        if (!VMSystem.VM_STATUS_RUNNING.equals(taskEntity.getTaskStatus())){
    //            throw new LogicException("当前设备不是运营状态，不能补货");
    //        }
    //    }}
    //}

    private void checkCreateTask(Integer vmStatus,int productType) throws LogicException {
        //如果是投放工单，状态为运营
        if(productType == VMSystem.TASK_TYPE_DEPLOY  && vmStatus.equals(VMSystem.VM_STATUS_RUNNING)){
            throw new LogicException("该设备已在运营");
        }

        //如果是补货工单，状态不是运营状态
        if(productType == VMSystem.TASK_TYPE_SUPPLY  && !vmStatus.equals(VMSystem.VM_STATUS_RUNNING)){
            throw new LogicException("该设备不在运营状态");
        }

        //如果是撤机工单，状态不是运营状态
        if(productType == VMSystem.TASK_TYPE_REVOKE  && !vmStatus.equals(VMSystem.VM_STATUS_RUNNING)){
            throw new LogicException("该设备不在运营状态");
        }


    }

    private String createTaskCode() {
        /*SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        Date date = new Date();
        String format = sdf.format(date);
        String s = Strings.padStart("3", 4, '0');

        return format+s;*/

        //日期+序号
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));  //日期字符串
        String key= "lkd.task.code."+date; //redis key
        Object obj = redisTemplate.opsForValue().get(key);
        if(obj==null){
            redisTemplate.opsForValue().set(key,1L, Duration.ofDays(1) );
            return date+"0001";
        }
        return date+  Strings.padStart( redisTemplate.opsForValue().increment(key,1).toString(),4,'0');
    }

    /**
     * @description: 是否有未完成工单
     * @author Zle
     * @date 2022/7/11 18:27
     * @param innerCode
     * @param productionType
     * @return boolean
     */
    private boolean hasTask(String innerCode,int productionType){
        QueryWrapper<TaskEntity> qw = new QueryWrapper<>();
        qw.lambda()
                .select(TaskEntity::getTaskId)
                .eq(TaskEntity::getInnerCode,innerCode)
                .eq(TaskEntity::getProductTypeId,productionType)
                .le(TaskEntity::getTaskStatus,VMSystem.TASK_STATUS_PROGRESS);
        return this.count(qw) > 0;
    }












    /*@Transactional
    @Override
    public boolean createTask(TaskViewModel taskViewModel) throws LogicException {
        //跨服务查询售货机微服务，得到售货机的地址和区域id
        VmVO vm = vmService.getVMInfo(taskViewModel.getInnerCode());
        if(vm==null){
            throw new LogicException("该机器不存在");
        }
        //跨服务查询用户微服务，得到用户名
        UserVO user = userService.getUser(taskViewModel.getUserId());
        if(user==null){
            throw new LogicException("该用户不存在");
        }

        //新增工单表记录
        TaskEntity taskEntity = new TaskEntity();
        taskEntity.setTaskCode(generateTaskCode());//工单编号
        BeanUtils.copyProperties(taskViewModel,taskEntity);//复制属性
        taskEntity.setTaskStatus(VMSystem.TASK_STATUS_CREATE);//工单状态
        taskEntity.setProductTypeId(taskViewModel.getProductType());//工单类型

        taskEntity.setAddr(vm.getNodeAddr());//地址
        taskEntity.setRegionId(  vm.getRegionId() );//区域
        taskEntity.setUserName(user.getUserName());//用户名
        this.save(taskEntity);
        //如果是补货工单，向 工单明细表插入记录
        if(taskEntity.getProductTypeId() == VMSystem.TASK_TYPE_SUPPLY){
            taskViewModel.getDetails().forEach(d->{
                TaskDetailsEntity detailsEntity = new TaskDetailsEntity();
                BeanUtils.copyProperties( d,detailsEntity );
                detailsEntity.setTaskId(taskEntity.getTaskId());
                taskDetailsService.save(detailsEntity);
            });
        }

        return true;
    }


    *//**
     * 生成工单编号
     * @return
     *//*
    private String generateTaskCode(){
        //日期+序号
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));  //日期字符串
        String key= "lkd.task.code."+date; //redis key
        Object obj = redisTemplate.opsForValue().get(key);
        if(obj==null){
            redisTemplate.opsForValue().set(key,1L, Duration.ofDays(1) );
            return date+"0001";
        }
        return date+  Strings.padStart( redisTemplate.opsForValue().increment(key,1).toString(),4,'0');
    }
*/

    @Override
    public boolean accept(Long id) {

        TaskEntity task = this.getById(id);  //查询工单

        if (!task.getProductTypeId().equals(VMSystem.TASK_STATUS_CREATE)){
            throw new LogicException("工单状态不是待处理");
        }

        task.setTaskStatus( VMSystem.TASK_STATUS_PROGRESS );//修改工单状态为进行

        return this.updateById(task);
    }

    /**
     * @description: 取消工单
     * @author Zle
     * @date 2022/7/14 14:58
     * @param id
     * @param cancelVM
     * @return boolean
     */
    @Override
    public boolean cancelTask(Long id, CancelTaskViewModel cancelVM) {
        TaskEntity task = this.getById(id);  //查询工单
        if (task.getProductTypeId().equals(VMSystem.TASK_STATUS_PROGRESS) ||
                task.getProductTypeId().equals(VMSystem.TASK_STATUS_FINISH) ){
            throw new LogicException("该工单已取消或已完成");
        }
        task.setTaskStatus( VMSystem.TASK_STATUS_CANCEL  );
        task.setDesc(cancelVM.getDesc());

        updateTaskZSet(task,-1);
        return this.updateById(task);
    }

    @Override
    public boolean completeTask(long id)  {
        TaskEntity taskEntity = this.getById(id);
        if (taskEntity.getProductTypeId().equals(VMSystem.TASK_STATUS_PROGRESS) ||
                taskEntity.getProductTypeId().equals(VMSystem.TASK_STATUS_FINISH) ){
            throw new LogicException("该工单已取消或已完成");
        }
        taskEntity.setTaskStatus(VMSystem.TASK_STATUS_FINISH);//修改工单状态
        this.updateById(taskEntity);

        //获取工单类型
        TaskCompleteContract taskCompleteContract = new TaskCompleteContract();
        taskCompleteContract.setTaskType(taskEntity.getProductTypeId());
        taskCompleteContract.setInnerCode(taskEntity.getInnerCode());

        //判断是否是运维工单
        //if (taskCompleteContract.getTaskType() == VMSystem.TASK_TYPE_DEPLOY) {
        if (taskEntity.getProductTypeId().equals(VMSystem.TASK_TYPE_DEPLOY)
                || taskEntity.getProductTypeId().equals(VMSystem.TASK_TYPE_REVOKE)) {

            /*//获取工单类型
            TaskCompleteContract taskCompleteContract = new TaskCompleteContract();
            taskCompleteContract.setTaskType(taskEntity.getProductTypeId());
            taskCompleteContract.setInnerCode(taskEntity.getInnerCode());*/

            try {
                mqttProducer.send(TopicConfig.VMS_COMPLETED_TOPIC,2, JsonUtil.serialize(taskCompleteContract));
            } catch (JsonProcessingException e) {
                throw new LogicException("发送工单协议出错");
            }
        }

        //如果是补货工单!
        if(taskEntity.getProductTypeId().equals(VMSystem.TASK_TYPE_SUPPLY)){
            noticeVMServiceSupply(taskEntity);
        }
        return true;
    }

    @Override
    public int getLeastUser(Long regionId, Boolean isRepair) {
        String roleCode = "1002";
        if (isRepair){
            roleCode = "1003";
        }
        String key = VMSystem.REGION_TASK_KEY_PREF
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "." + regionId
                + "." + roleCode;
        Set<Object> set = redisTemplate.opsForZSet().range(key, 0, 0);
        if (set == null || set.isEmpty()){
            return 0;  //没有数据就返回0
        }
        //有数据就获取set第一个元素
        return (Integer) set.toArray()[0];
    }

    /**
     * @description: 补货协议的封装和下发
     * @author Zle
     * @date 2022/7/12 19:54
     * @param taskEntity taskEntity
     */
    private void noticeVMServiceSupply(TaskEntity taskEntity) {
        //协议内容封装
        //1.根据工单id查询工单明细表
        LambdaQueryWrapper<TaskDetailsEntity> lambdaQueryWrapper = new LambdaQueryWrapper<TaskDetailsEntity>();
        lambdaQueryWrapper.eq(TaskDetailsEntity::getTaskId,taskEntity.getTaskId());
        List<TaskDetailsEntity> details = taskDetailsService.list(lambdaQueryWrapper);
        //构建协议 内容
        SupplyContract supplyContract = new SupplyContract();
        supplyContract.setInnerCode(taskEntity.getInnerCode());//售货机编号
        List<SupplyChannel> supplyChannels = Lists.newArrayList();//guava中的lists方法 补货数据
        //从工单明细表提取数据加到补货数据中
        details.forEach(d ->{
            SupplyChannel channel = new SupplyChannel();
            channel.setChannelId(d.getChannelCode());//货道编号
            channel.setCapacity(d.getExpectCapacity());//补货数量
            supplyChannels.add(channel);
        });
        supplyContract.setSupplyData(supplyChannels);

        //下发补货协议
        //发送到emq
        try {
            mqttProducer.send( TopicConfig.VMS_SUPPLY_TOPIC,2, supplyContract );
        } catch (Exception e) {
            log.error("发送工单完成协议出错");
            throw new LogicException("发送工单完成协议出错");
        }
    }

}
