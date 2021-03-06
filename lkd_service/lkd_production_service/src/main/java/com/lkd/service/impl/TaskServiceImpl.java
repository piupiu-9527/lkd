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
        //????????????????????????????????????
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
            throw new LogicException("???????????????????????????????????????");
        }

        //????????????????????????
        VmVO vmInfo = vmService.getVMInfo(taskViewModel.getInnerCode());
        if (vmInfo == null) {
            throw  new LogicException("??????????????????");
        }

        //?????????????????????
        checkCreateTask(vmInfo.getVmStatus(),taskViewModel.getProductType());//?????????????????????
        //????????????
        UserVO user = userService.getUser(taskViewModel.getUserId());
        if (user == null) {
            throw  new LogicException("???????????????");
        }

        TaskEntity taskEntity = new TaskEntity();
        BeanUtils.copyProperties(taskViewModel,taskEntity);
        taskEntity.setTaskCode(createTaskCode());
        taskEntity.setTaskStatus(VMSystem.TASK_STATUS_CREATE);
        taskEntity.setProductTypeId(taskViewModel.getProductType());
        taskEntity.setAddr(vmInfo.getNodeAddr());//??????
        taskEntity.setRegionId(vmInfo.getRegionId());//??????
        taskEntity.setUserName(user.getUserName());//?????????
        this.save(taskEntity);

        //???????????????????????????
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
     * @description: ?????????????????????
     * @author Zle
     * @date 2022/7/14 14:38
     * @param taskEntity
     * @param score
     */
    private void updateTaskZSet(TaskEntity taskEntity, int score) {
        String roleCode = "1003";  //??????
        if (taskEntity.getProductTypeId().intValue() == 2){  //?????????????????????
            roleCode = "1002";  //??????
        }
        String key = VMSystem.REGION_TASK_KEY_PREF
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "." + taskEntity.getRegionId()
                + "." + roleCode;
        //incrementScore ?????????????????????get score+1  incrementScore???????????????????????????????????????
        redisTemplate.opsForZSet().incrementScore(key,taskEntity.getUserId(),score);
    }

    /**
     * @description: ?????????????????????
     * @author Zle
     * @date 2022/7/11 18:18
     * @param
     * @param
     */
    //private void checkTypeAndStatus(TaskViewModel taskViewModel, TaskEntity taskEntity) {
    //    if (VMSystem.TASK_TYPE_DEPLOY.equals(taskViewModel.getProductType())){
    //        if (VMSystem.VM_STATUS_RUNNING.equals(taskEntity.getTaskStatus())){
    //            throw new LogicException("??????????????????????????????????????????");
    //        }
    //    }
    //
    //    if (VMSystem.TASK_TYPE_REVOKE.equals(taskViewModel.getProductType())){
    //        if (!VMSystem.TASK_TYPE_REVOKE.equals(taskEntity.getTaskStatus())){
    //            throw new LogicException("?????????????????????????????????????????????");
    //        }
    //    }
    //
    //    if (VMSystem.TASK_TYPE_SUPPLY.equals(taskViewModel.getProductType())){{
    //        if (!VMSystem.VM_STATUS_RUNNING.equals(taskEntity.getTaskStatus())){
    //            throw new LogicException("?????????????????????????????????????????????");
    //        }
    //    }}
    //}

    private void checkCreateTask(Integer vmStatus,int productType) throws LogicException {
        //???????????????????????????????????????
        if(productType == VMSystem.TASK_TYPE_DEPLOY  && vmStatus.equals(VMSystem.VM_STATUS_RUNNING)){
            throw new LogicException("?????????????????????");
        }

        //????????????????????????????????????????????????
        if(productType == VMSystem.TASK_TYPE_SUPPLY  && !vmStatus.equals(VMSystem.VM_STATUS_RUNNING)){
            throw new LogicException("???????????????????????????");
        }

        //????????????????????????????????????????????????
        if(productType == VMSystem.TASK_TYPE_REVOKE  && !vmStatus.equals(VMSystem.VM_STATUS_RUNNING)){
            throw new LogicException("???????????????????????????");
        }


    }

    private String createTaskCode() {
        /*SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        Date date = new Date();
        String format = sdf.format(date);
        String s = Strings.padStart("3", 4, '0');

        return format+s;*/

        //??????+??????
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));  //???????????????
        String key= "lkd.task.code."+date; //redis key
        Object obj = redisTemplate.opsForValue().get(key);
        if(obj==null){
            redisTemplate.opsForValue().set(key,1L, Duration.ofDays(1) );
            return date+"0001";
        }
        return date+  Strings.padStart( redisTemplate.opsForValue().increment(key,1).toString(),4,'0');
    }

    /**
     * @description: ????????????????????????
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
        //?????????????????????????????????????????????????????????????????????id
        VmVO vm = vmService.getVMInfo(taskViewModel.getInnerCode());
        if(vm==null){
            throw new LogicException("??????????????????");
        }
        //????????????????????????????????????????????????
        UserVO user = userService.getUser(taskViewModel.getUserId());
        if(user==null){
            throw new LogicException("??????????????????");
        }

        //?????????????????????
        TaskEntity taskEntity = new TaskEntity();
        taskEntity.setTaskCode(generateTaskCode());//????????????
        BeanUtils.copyProperties(taskViewModel,taskEntity);//????????????
        taskEntity.setTaskStatus(VMSystem.TASK_STATUS_CREATE);//????????????
        taskEntity.setProductTypeId(taskViewModel.getProductType());//????????????

        taskEntity.setAddr(vm.getNodeAddr());//??????
        taskEntity.setRegionId(  vm.getRegionId() );//??????
        taskEntity.setUserName(user.getUserName());//?????????
        this.save(taskEntity);
        //??????????????????????????? ???????????????????????????
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
     * ??????????????????
     * @return
     *//*
    private String generateTaskCode(){
        //??????+??????
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));  //???????????????
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

        TaskEntity task = this.getById(id);  //????????????

        if (!task.getProductTypeId().equals(VMSystem.TASK_STATUS_CREATE)){
            throw new LogicException("???????????????????????????");
        }

        task.setTaskStatus( VMSystem.TASK_STATUS_PROGRESS );//???????????????????????????

        return this.updateById(task);
    }

    /**
     * @description: ????????????
     * @author Zle
     * @date 2022/7/14 14:58
     * @param id
     * @param cancelVM
     * @return boolean
     */
    @Override
    public boolean cancelTask(Long id, CancelTaskViewModel cancelVM) {
        TaskEntity task = this.getById(id);  //????????????
        if (task.getProductTypeId().equals(VMSystem.TASK_STATUS_PROGRESS) ||
                task.getProductTypeId().equals(VMSystem.TASK_STATUS_FINISH) ){
            throw new LogicException("??????????????????????????????");
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
            throw new LogicException("??????????????????????????????");
        }
        taskEntity.setTaskStatus(VMSystem.TASK_STATUS_FINISH);//??????????????????
        this.updateById(taskEntity);

        //??????????????????
        TaskCompleteContract taskCompleteContract = new TaskCompleteContract();
        taskCompleteContract.setTaskType(taskEntity.getProductTypeId());
        taskCompleteContract.setInnerCode(taskEntity.getInnerCode());

        //???????????????????????????
        //if (taskCompleteContract.getTaskType() == VMSystem.TASK_TYPE_DEPLOY) {
        if (taskEntity.getProductTypeId().equals(VMSystem.TASK_TYPE_DEPLOY)
                || taskEntity.getProductTypeId().equals(VMSystem.TASK_TYPE_REVOKE)) {

            /*//??????????????????
            TaskCompleteContract taskCompleteContract = new TaskCompleteContract();
            taskCompleteContract.setTaskType(taskEntity.getProductTypeId());
            taskCompleteContract.setInnerCode(taskEntity.getInnerCode());*/

            try {
                mqttProducer.send(TopicConfig.VMS_COMPLETED_TOPIC,2, JsonUtil.serialize(taskCompleteContract));
            } catch (JsonProcessingException e) {
                throw new LogicException("????????????????????????");
            }
        }

        //?????????????????????!
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
            return 0;  //?????????????????????0
        }
        //??????????????????set???????????????
        return (Integer) set.toArray()[0];
    }

    /**
     * @description: ??????????????????????????????
     * @author Zle
     * @date 2022/7/12 19:54
     * @param taskEntity taskEntity
     */
    private void noticeVMServiceSupply(TaskEntity taskEntity) {
        //??????????????????
        //1.????????????id?????????????????????
        LambdaQueryWrapper<TaskDetailsEntity> lambdaQueryWrapper = new LambdaQueryWrapper<TaskDetailsEntity>();
        lambdaQueryWrapper.eq(TaskDetailsEntity::getTaskId,taskEntity.getTaskId());
        List<TaskDetailsEntity> details = taskDetailsService.list(lambdaQueryWrapper);
        //???????????? ??????
        SupplyContract supplyContract = new SupplyContract();
        supplyContract.setInnerCode(taskEntity.getInnerCode());//???????????????
        List<SupplyChannel> supplyChannels = Lists.newArrayList();//guava??????lists?????? ????????????
        //???????????????????????????????????????????????????
        details.forEach(d ->{
            SupplyChannel channel = new SupplyChannel();
            channel.setChannelId(d.getChannelCode());//????????????
            channel.setCapacity(d.getExpectCapacity());//????????????
            supplyChannels.add(channel);
        });
        supplyContract.setSupplyData(supplyChannels);

        //??????????????????
        //?????????emq
        try {
            mqttProducer.send( TopicConfig.VMS_SUPPLY_TOPIC,2, supplyContract );
        } catch (Exception e) {
            log.error("??????????????????????????????");
            throw new LogicException("??????????????????????????????");
        }
    }

}
