package com.alibaba.smart.framework.engine.behavior.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.smart.framework.engine.SmartEngine;
import com.alibaba.smart.framework.engine.behavior.base.AbstractActivityBehavior;
import com.alibaba.smart.framework.engine.bpmn.assembly.multi.instance.MultiInstanceLoopCharacteristics;
import com.alibaba.smart.framework.engine.bpmn.assembly.task.UserTask;
import com.alibaba.smart.framework.engine.common.expression.ExpressionUtil;
import com.alibaba.smart.framework.engine.common.util.MarkDoneUtil;
import com.alibaba.smart.framework.engine.configuration.IdGenerator;
import com.alibaba.smart.framework.engine.configuration.MultiInstanceCounter;
import com.alibaba.smart.framework.engine.context.ExecutionContext;
import com.alibaba.smart.framework.engine.exception.EngineException;
import com.alibaba.smart.framework.engine.exception.ValidationException;
import com.alibaba.smart.framework.engine.extension.annoation.ExtensionBinding;
import com.alibaba.smart.framework.engine.extension.constant.ExtensionConstant;
import com.alibaba.smart.framework.engine.instance.storage.TaskInstanceStorage;
import com.alibaba.smart.framework.engine.model.assembly.ConditionExpression;
import com.alibaba.smart.framework.engine.model.instance.ActivityInstance;
import com.alibaba.smart.framework.engine.model.instance.ExecutionInstance;
import com.alibaba.smart.framework.engine.model.instance.ProcessInstance;
import com.alibaba.smart.framework.engine.model.instance.TaskAssigneeCandidateInstance;
import com.alibaba.smart.framework.engine.model.instance.TaskAssigneeInstance;
import com.alibaba.smart.framework.engine.model.instance.TaskInstance;
import com.alibaba.smart.framework.engine.pvm.PvmActivity;
import com.alibaba.smart.framework.engine.pvm.event.PvmEventConstant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtensionBinding(group = ExtensionConstant.ACTIVITY_BEHAVIOR, bindKey = UserTask.class)

public class UserTaskBehavior extends AbstractActivityBehavior<UserTask> {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserTaskBehavior.class);


    public UserTaskBehavior() {
        super();
    }

    @Override
    public boolean enter(ExecutionContext context, PvmActivity pvmActivity) {
        UserTask userTask = (UserTask)pvmActivity.getModel();

        List<TaskAssigneeCandidateInstance> allTaskAssigneeCandidateInstanceList = UserTaskBehaviorHelper.getTaskAssigneeCandidateInstances(
            context, userTask);

        ProcessInstance processInstance = context.getProcessInstance();

        LOGGER.info("The taskAssigneeCandidateInstance are "+allTaskAssigneeCandidateInstanceList +" for PI:"+ processInstance
            .getInstanceId() +" and AI id: "+pvmActivity.getModel().getId() );

        
        //1. ?????????????????????
        MultiInstanceLoopCharacteristics multiInstanceLoopCharacteristics = userTask
            .getMultiInstanceLoopCharacteristics();

        if (null != multiInstanceLoopCharacteristics) {

            fireEvent(context,pvmActivity, PvmEventConstant.ACTIVITY_START);

            ActivityInstance activityInstance  = super.createSingleActivityInstanceAndAttachToProcessInstance(context,userTask);


            List<ExecutionInstance> executionInstanceList = new ArrayList<ExecutionInstance>(allTaskAssigneeCandidateInstanceList.size());
            activityInstance.setExecutionInstanceList(executionInstanceList);

            //1.1 ????????????????????????????????????????????? ???????????? ??? ??????????????? ????????????????????????????????????????????????????????????????????????????????????
            // ???????????????????????????????????????????????????????????? UserTask ?????????????????????????????????????????????????????????
            // ??????????????????????????????????????????????????????????????????

            List<TaskAssigneeCandidateInstance> newTaskAssigneeCandidateInstanceList = null;

            if(multiInstanceLoopCharacteristics.isSequential()){
                 newTaskAssigneeCandidateInstanceList = UserTaskBehaviorHelper.findBatchOfHighestPriorityTaskAssigneeList(allTaskAssigneeCandidateInstanceList);
            }else{
                newTaskAssigneeCandidateInstanceList = allTaskAssigneeCandidateInstanceList;
            }

            for (TaskAssigneeCandidateInstance taskAssigneeCandidateInstance : newTaskAssigneeCandidateInstanceList) {

                ExecutionInstance executionInstance = this.executionInstanceFactory.create(activityInstance, context);
                executionInstanceList.add(executionInstance);


                TaskInstance taskInstance = super.taskInstanceFactory.create(userTask, executionInstance, context);
                taskInstance.setPriority(taskAssigneeCandidateInstance.getPriority());

                List<TaskAssigneeInstance> taskAssigneeInstanceList = new ArrayList<TaskAssigneeInstance>(2);

                IdGenerator idGenerator = context.getProcessEngineConfiguration().getIdGenerator();

                UserTaskBehaviorHelper.buildTaskAssigneeInstance(taskAssigneeCandidateInstance, taskAssigneeInstanceList, idGenerator);

                taskInstance.setTaskAssigneeInstanceList(taskAssigneeInstanceList);

                executionInstance.setTaskInstance(taskInstance);

            }

        } else {

            //2.  ??????????????????
            super.enter(context, pvmActivity);

            if (null != allTaskAssigneeCandidateInstanceList) {
                ExecutionInstance executionInstance = context.getExecutionInstance();

                TaskInstance taskInstance = super.taskInstanceFactory.create(userTask, executionInstance,
                    context);

                List<TaskAssigneeInstance> taskAssigneeInstanceList = new ArrayList<TaskAssigneeInstance>(2);

                IdGenerator idGenerator = context.getProcessEngineConfiguration().getIdGenerator();

                for (TaskAssigneeCandidateInstance taskAssigneeCandidateInstance : allTaskAssigneeCandidateInstanceList) {
                    UserTaskBehaviorHelper.buildTaskAssigneeInstance(taskAssigneeCandidateInstance, taskAssigneeInstanceList, idGenerator);
                }

                //2.1 ??????UserTask????????????????????????TI???????????????TACI(TaskAssigneeCandidateInstance)
                taskInstance.setTaskAssigneeInstanceList(taskAssigneeInstanceList);
                executionInstance.setTaskInstance(taskInstance);
            }
        }

        return true;
    }





    @Override
    public void execute(ExecutionContext context, PvmActivity pvmActivity) {

        fireEvent(context,pvmActivity, PvmEventConstant.ACTIVITY_EXECUTE);


        //1. ????????????ExecutionInstance???????????????
        ExecutionInstance executionInstance = context.getExecutionInstance();
        //?????????????????????executionInstance???????????????,??????????????? DB ???.
        MarkDoneUtil.markDoneExecutionInstance(executionInstance, this.executionInstanceStorage,
            this.processEngineConfiguration);


        UserTask userTask = (UserTask) pvmActivity.getModel();

        super.executeDelegation(context,userTask);


        MultiInstanceLoopCharacteristics multiInstanceLoopCharacteristics = userTask
            .getMultiInstanceLoopCharacteristics();

        if (null != multiInstanceLoopCharacteristics) {

            handleMultiInstance(context, executionInstance, userTask, multiInstanceLoopCharacteristics);

        } else {

            handleSingleInstance(context);
        }
    
    }

    protected void handleMultiInstance(ExecutionContext context, ExecutionInstance executionInstance, UserTask userTask,
                                     MultiInstanceLoopCharacteristics multiInstanceLoopCharacteristics) {
        ActivityInstance activityInstance = context.getActivityInstance();

        SmartEngine smartEngine = processEngineConfiguration.getSmartEngine();

        //????????????: ??????????????????,ei:ti:tai= 1:1:1 ,?????????????????????, assigneeType ??????????????? user.
        //1. ?????????????????????????????? totalExecutionInstanceList??????????????????????????? ?????????????????????????????????????????????totalExecutionInstanceList ??????????????????ExecutionList??????
        List<ExecutionInstance> totalExecutionInstanceList = executionInstanceStorage.findByActivityInstanceId(
            activityInstance.getProcessInstanceId(), activityInstance.getInstanceId(),
            this.processEngineConfiguration);

        // ??????????????????,??????totalInstanceCount ??????????????????????????????count,????????????????????????; ???????????????????????????,?????????????????????,????????????.
        Integer totalInstanceCount  = totalExecutionInstanceList.size();
        Integer passedTaskInstanceCount = 0;
        Integer rejectedTaskInstanceCount = 0;

        MultiInstanceCounter multiInstanceCounter = context.getProcessEngineConfiguration().getMultiInstanceCounter();

        if(multiInstanceCounter != null) {
            passedTaskInstanceCount = multiInstanceCounter.countPassedTaskInstanceNumber(
                activityInstance.getProcessInstanceId(), activityInstance.getInstanceId(), smartEngine);
            rejectedTaskInstanceCount = multiInstanceCounter.countRejectedTaskInstanceNumber(
                activityInstance.getProcessInstanceId(), activityInstance.getInstanceId(), smartEngine);
        } else {
            throw new ValidationException("MultiInstanceCounter can NOT be null for multiInstanceLoopCharacteristics");
        }

        // ?????????  nrOfCompletedInstances + nrOfRejectedInstance <= nrOfInstances
        Map<String,Object> requestContext =  new HashMap<String, Object>(4);
        requestContext.put("nrOfCompletedInstances", passedTaskInstanceCount);
        requestContext.put("nrOfRejectedInstance", rejectedTaskInstanceCount);
        requestContext.put("nrOfInstances", totalInstanceCount);

        // ??????????????????????????????????????????????????????????????????
        boolean abortMatched = false;

        ConditionExpression abortCondition = multiInstanceLoopCharacteristics.getAbortCondition();

        if (null != abortCondition) {
            abortMatched = ExpressionUtil.eval(requestContext, abortCondition,context.getProcessEngineConfiguration());
        }

        //???????????????????????????abort??????
        if (!abortMatched) {
            ConditionExpression completionCondition = multiInstanceLoopCharacteristics.getCompletionCondition();

            if(null != completionCondition){
                boolean passedMatched  = ExpressionUtil.eval(requestContext, completionCondition,context.getProcessEngineConfiguration()) ;

                Integer completedTaskInstanceCount = passedTaskInstanceCount + rejectedTaskInstanceCount;
                if(completedTaskInstanceCount < totalInstanceCount){

                    if(passedMatched){
                        UserTaskBehaviorHelper.markDoneEIAndCancelTI(context, executionInstance, totalExecutionInstanceList,executionInstanceStorage,processEngineConfiguration);

                        context.setNeedPause(false);

                    } else {
                        context.setNeedPause(true);
                        //???????????????????????????????????????????????????
                        if(multiInstanceLoopCharacteristics.isSequential()){
                            Map<String, TaskAssigneeCandidateInstance> taskAssigneeMap = queryTaskAssigneeCandidateInstance(context, userTask);

                            UserTaskBehaviorHelper.compensateExecutionAndTask(context, userTask, activityInstance, executionInstance, taskAssigneeMap,executionInstanceStorage,executionInstanceFactory,taskInstanceFactory,processEngineConfiguration);
                        }else{
                            // do nothing
                        }
                    }

                }else if(completedTaskInstanceCount.equals(totalInstanceCount)){

                    if(passedMatched){
                        context.setNeedPause(false);
                    }else {
                        UserTaskBehaviorHelper.abortAndSetNeedPause(context, executionInstance, smartEngine);
                    }

                }else{
                    handleException(totalInstanceCount, passedTaskInstanceCount, rejectedTaskInstanceCount);
                }

            }
            else{
                //completionCondition ????????????????????????all?????? ??????????????????????????? ???????????????????????????????????????????????????

                if(rejectedTaskInstanceCount >= 1){
                    //fail fast ,abort
                    UserTaskBehaviorHelper.abortAndSetNeedPause(context, executionInstance, smartEngine);
                    UserTaskBehaviorHelper.markDoneEIAndCancelTI(context, executionInstance, totalExecutionInstanceList,executionInstanceStorage,processEngineConfiguration);

                } else if(passedTaskInstanceCount < totalInstanceCount  ){
                    context.setNeedPause(true);

                    if(multiInstanceLoopCharacteristics.isSequential()){
                        Map<String, TaskAssigneeCandidateInstance> taskAssigneeMap = queryTaskAssigneeCandidateInstance(context, userTask);

                        UserTaskBehaviorHelper.compensateExecutionAndTask(context, userTask, activityInstance, executionInstance, taskAssigneeMap,executionInstanceStorage,executionInstanceFactory,taskInstanceFactory,processEngineConfiguration);
                    } else {
                        //do nothing
                    }
                }
                else  if(passedTaskInstanceCount.equals(totalInstanceCount)  ){
                    context.setNeedPause(false);
                }else{
                    handleException(totalInstanceCount, passedTaskInstanceCount, rejectedTaskInstanceCount);

                }

            }

        } else {

            UserTaskBehaviorHelper.abortAndSetNeedPause(context, executionInstance, smartEngine);
            UserTaskBehaviorHelper.markDoneEIAndCancelTI(context, executionInstance, totalExecutionInstanceList,executionInstanceStorage,processEngineConfiguration);

        }
    }

    private Map<String, TaskAssigneeCandidateInstance> queryTaskAssigneeCandidateInstance(ExecutionContext context, UserTask userTask) {
        //?????? ????????????,?????????????????????????????????????????????,????????????????????????.
        Map<String, TaskAssigneeCandidateInstance> taskAssigneeMap = new HashMap<String, TaskAssigneeCandidateInstance>();

        List<TaskAssigneeCandidateInstance> taskAssigneeCandidateInstanceList = UserTaskBehaviorHelper
         .getTaskAssigneeCandidateInstances(context, userTask);

        if(taskAssigneeCandidateInstanceList != null) {
            for(TaskAssigneeCandidateInstance assigneeCandidateInstance : taskAssigneeCandidateInstanceList) {
                taskAssigneeMap.put(assigneeCandidateInstance.getAssigneeId(), assigneeCandidateInstance);
            }
        }
        return taskAssigneeMap;
    }

    protected void handleSingleInstance(ExecutionContext context) {
        super.commonUpdateExecutionInstance(context);
    }

    private void handleException(Integer totalInstanceCount, Integer passedTaskInstanceCount,
                                 Integer rejectedTaskInstanceCount) {
        String message =
            "Error args: passedTaskInstanceCount, rejectedTaskInstanceCount, totalInstanceCount each is :"
                + passedTaskInstanceCount+"," + rejectedTaskInstanceCount+"," + totalInstanceCount+".";
        throw new EngineException(message);
    }

}
