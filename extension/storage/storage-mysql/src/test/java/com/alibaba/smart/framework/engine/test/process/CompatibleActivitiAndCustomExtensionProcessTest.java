package com.alibaba.smart.framework.engine.test.process;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.smart.framework.engine.constant.DeploymentStatusConstant;
import com.alibaba.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.alibaba.smart.framework.engine.model.instance.DeploymentInstance;
import com.alibaba.smart.framework.engine.model.instance.ExecutionInstance;
import com.alibaba.smart.framework.engine.model.instance.InstanceStatus;
import com.alibaba.smart.framework.engine.model.instance.ProcessInstance;
import com.alibaba.smart.framework.engine.model.instance.TaskInstance;
import com.alibaba.smart.framework.engine.service.param.command.CreateDeploymentCommand;
import com.alibaba.smart.framework.engine.service.param.query.PendingTaskQueryParam;
import com.alibaba.smart.framework.engine.test.DatabaseBaseTestCase;
import com.alibaba.smart.framework.engine.test.process.bean.Order;
import com.alibaba.smart.framework.engine.test.process.helper.CustomExceptioinProcessor;
import com.alibaba.smart.framework.engine.test.process.helper.CustomVariablePersister;
import com.alibaba.smart.framework.engine.test.process.helper.DefaultMultiInstanceCounter;
import com.alibaba.smart.framework.engine.test.process.helper.DoNothingLockStrategy;
import com.alibaba.smart.framework.engine.test.process.helper.dispatcher.DefaultTaskAssigneeDispatcher;
import com.alibaba.smart.framework.engine.util.IOUtil;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

@ContextConfiguration("/spring/application-test.xml")
@RunWith(SpringJUnit4ClassRunner.class)
@Transactional
public class CompatibleActivitiAndCustomExtensionProcessTest extends DatabaseBaseTestCase {

    @Override
    protected void initProcessConfiguration() {
        super.initProcessConfiguration();
        processEngineConfiguration.setExceptionProcessor(new CustomExceptioinProcessor());
        processEngineConfiguration.setTaskAssigneeDispatcher(new DefaultTaskAssigneeDispatcher());
        processEngineConfiguration.setMultiInstanceCounter(new DefaultMultiInstanceCounter());
        processEngineConfiguration.setVariablePersister(new CustomVariablePersister());
        processEngineConfiguration.setLockStrategy(new DoNothingLockStrategy());
    }



    @Test
    public void testMultiInstance() throws Exception {


        //3. ??????????????????
        CreateDeploymentCommand createDeploymentCommand = new CreateDeploymentCommand();
        String content = IOUtil.readResourceFileAsUTF8String(
            "compatible-activiti-and-custom-extension-process-test.bpmn20.xml");
        createDeploymentCommand.setProcessDefinitionContent(content);
        createDeploymentCommand.setDeploymentUserId("123");
        createDeploymentCommand.setDeploymentStatus(DeploymentStatusConstant.ACTIVE);
        createDeploymentCommand.setProcessDefinitionDesc("desc");
        createDeploymentCommand.setProcessDefinitionName("name");
        createDeploymentCommand.setProcessDefinitionType("group");

        DeploymentInstance deploymentInstance =  deploymentCommandService.createDeployment(createDeploymentCommand);


        //4.??????????????????

        Map<String, Object> request = new HashMap();
        request.put(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID,"123");
        request.put(RequestMapSpecialKeyConstant.PROCESS_DEFINITION_TYPE,"group");
        request.put("processVariable","processVariableValue");


        ProcessInstance processInstance = processCommandService.start(
            deploymentInstance.getProcessDefinitionId(), deploymentInstance.getProcessDefinitionVersion()
              ,request  );
        Assert.assertNotNull(processInstance);
        Assert.assertEquals("group",processInstance.getProcessDefinitionType());



        List<TaskInstance> submitTaskInstanceList=  taskQueryService.findAllPendingTaskList(processInstance.getInstanceId());
        Assert.assertEquals(3,submitTaskInstanceList.size());
        TaskInstance submitTaskInstance = submitTaskInstanceList.get(0);



        //5.????????????:????????????????????????
        Map<String, Object> submitFormRequest = new HashMap<String, Object>();
        submitFormRequest.put("title", "new_title");
        submitFormRequest.put("qps", "300");
        submitFormRequest.put("capacity","10g");
        //submitFormRequest.put("assigneeUserId","1");
        submitFormRequest.put(RequestMapSpecialKeyConstant.TASK_INSTANCE_TAG, VariableInstanceAndMultiInstanceTest.AGREE);
        submitFormRequest.put(RequestMapSpecialKeyConstant.TASK_INSTANCE_CLAIM_USER_ID,"5");
        submitFormRequest.put("text","123");

        //submitFormRequest.put(RequestMapSpecialKeyConstant.PROCESS_DEFINITION_TYPE,"group");



        //6.????????????:?????? submitTask,??????????????????. ??????userTask1 ????????????????????????
        taskCommandService.complete(submitTaskInstance.getInstanceId(),submitFormRequest);


        PendingTaskQueryParam pendingTaskQueryParam = new PendingTaskQueryParam();
        pendingTaskQueryParam.setAssigneeUserId("3");
        List<TaskInstance>  assertedTaskInstanceList=   taskQueryService.findAllPendingTaskList(processInstance.getInstanceId());
        Assert.assertEquals(2,assertedTaskInstanceList.size());
        Assert.assertEquals("userTask1",assertedTaskInstanceList.get(0).getProcessDefinitionActivityId());


        List<ExecutionInstance> activeExecutions = executionQueryService.findActiveExecutionList(processInstance.getInstanceId());
        Assert.assertEquals(2,activeExecutions.size());
        submitTaskInstanceList=  taskQueryService.findAllPendingTaskList(processInstance.getInstanceId());
        Assert.assertEquals(2,submitTaskInstanceList.size());
        //??????userTask1 ????????????????????????
        taskCommandService.complete(submitTaskInstanceList.get(0).getInstanceId(),submitFormRequest);

        activeExecutions = executionQueryService.findActiveExecutionList(processInstance.getInstanceId());
        Assert.assertEquals(1,activeExecutions.size());
        submitTaskInstanceList=  taskQueryService.findAllPendingTaskList(processInstance.getInstanceId());
        Assert.assertEquals(1,submitTaskInstanceList.size());
        //??????userTask1 ????????????????????????
        taskCommandService.complete(submitTaskInstanceList.get(0).getInstanceId(),submitFormRequest);

        //??????,??????3??????????????????????????????. ?????????????????????????????????.
        submitTaskInstanceList=  taskQueryService.findAllPendingTaskList(processInstance.getInstanceId());
        Assert.assertEquals(3,submitTaskInstanceList.size());
        Assert.assertEquals("userTask2",submitTaskInstanceList.get(0).getProcessDefinitionActivityId());

        Order order = new Order();
        order.setYzje(101.02);
        submitFormRequest.put("order",order);

        // ??????userTask2 ??????????????? completionCondition ??? null??????????????? all ?????????????????????????????????????????????????????????
        taskCommandService.complete(submitTaskInstanceList.get(0).getInstanceId(),submitFormRequest);

        //??????????????????,?????????????????????????????????.
        submitTaskInstanceList=  taskQueryService.findAllPendingTaskList(processInstance.getInstanceId());
        Assert.assertEquals(3,submitTaskInstanceList.size());
        Assert.assertEquals("userTask3",submitTaskInstanceList.get(0).getProcessDefinitionActivityId());

        taskCommandService.complete(submitTaskInstanceList.get(0).getInstanceId(),submitFormRequest);

        //?????????????????????????????????.
        submitTaskInstanceList=  taskQueryService.findAllPendingTaskList(processInstance.getInstanceId());
        Assert.assertEquals(3,submitTaskInstanceList.size());
        Assert.assertEquals("UserTask_0ixdrmt",submitTaskInstanceList.get(0).getProcessDefinitionActivityId());

        taskCommandService.complete(submitTaskInstanceList.get(0).getInstanceId(),submitFormRequest);


        //10.??????????????????????????????,????????????????????????????????????,????????????.
        ProcessInstance finalProcessInstance = processQueryService.findById(processInstance.getInstanceId());
        Assert.assertEquals(InstanceStatus.completed,finalProcessInstance.getStatus());
    }


    @Test
    public void testCouterSign_Fail_All_modle() throws Exception {




        //3. ??????????????????
        CreateDeploymentCommand createDeploymentCommand = new CreateDeploymentCommand();
        String content = IOUtil.readResourceFileAsUTF8String(
            "compatible-activiti-and-custom-extension-process-test.bpmn20.xml");
        createDeploymentCommand.setProcessDefinitionContent(content);
        createDeploymentCommand.setDeploymentUserId("123");
        createDeploymentCommand.setDeploymentStatus(DeploymentStatusConstant.ACTIVE);
        createDeploymentCommand.setProcessDefinitionDesc("desc");
        createDeploymentCommand.setProcessDefinitionName("name");
        createDeploymentCommand.setProcessDefinitionType("group");

        DeploymentInstance deploymentInstance =  deploymentCommandService.createDeployment(createDeploymentCommand);


        //4.??????????????????

        Map<String, Object> request = new HashMap();
        request.put(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID,"123");
        request.put(RequestMapSpecialKeyConstant.PROCESS_DEFINITION_TYPE,"group");
        request.put("processVariable","processVariableValue");


        ProcessInstance processInstance = processCommandService.start(
            deploymentInstance.getProcessDefinitionId(), deploymentInstance.getProcessDefinitionVersion()
            ,request  );
        Assert.assertNotNull(processInstance);
        Assert.assertEquals("group",processInstance.getProcessDefinitionType());


        List<TaskInstance> submitTaskInstanceList=  taskQueryService.findAllPendingTaskList(processInstance.getInstanceId());
        Assert.assertEquals(3,submitTaskInstanceList.size());
        TaskInstance submitTaskInstance = submitTaskInstanceList.get(0);



        //5.????????????:????????????????????????
        Map<String, Object> submitFormRequest = new HashMap<String, Object>();
        submitFormRequest.put("title", "new_title");
        submitFormRequest.put("qps", "300");
        submitFormRequest.put("capacity","10g");
        //submitFormRequest.put("assigneeUserId","1");
        submitFormRequest.put(RequestMapSpecialKeyConstant.TASK_INSTANCE_TAG, VariableInstanceAndMultiInstanceTest.DISAGREE);
        submitFormRequest.put("text","123");

        //submitFormRequest.put(RequestMapSpecialKeyConstant.PROCESS_DEFINITION_TYPE,"group");

        taskCommandService.complete(submitTaskInstance.getInstanceId(),submitFormRequest);



        //6.????????????:?????? submitTask,??????????????????.

        //10.??????????????????????????????,????????????????????????????????????,????????????.
        ProcessInstance finalProcessInstance = processQueryService.findById(processInstance.getInstanceId());
        Assert.assertEquals(InstanceStatus.aborted,finalProcessInstance.getStatus());
    }





}