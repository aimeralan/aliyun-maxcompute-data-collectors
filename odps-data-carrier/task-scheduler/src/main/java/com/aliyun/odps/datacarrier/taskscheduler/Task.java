/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.aliyun.odps.datacarrier.taskscheduler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


class Task {

  private static final Logger LOG = LogManager.getLogger(Task.class);
  private String taskName;
  protected long updateTime;
  protected Map<Action, AbstractActionInfo> actionInfoMap;
  protected Progress progress;
  MetaSource.TableMetaModel tableMetaModel;
  MmaConfig.AdditionalTableConfig tableConfig;
  private MmaMetaManager mmaMetaManager;

  public Task(String taskName,
              MetaSource.TableMetaModel tableMetaModel,
              MmaConfig.AdditionalTableConfig tableConfig,
              MmaMetaManager mmaMetaManager) {
    this.taskName = taskName;
    this.tableMetaModel = tableMetaModel;
    this.tableConfig = tableConfig;
    this.updateTime = System.currentTimeMillis();
    this.actionInfoMap = new ConcurrentHashMap<>();
    this.progress = Progress.NEW;
    this.mmaMetaManager = mmaMetaManager;
  }

  protected void addActionInfo(Action action) {
    RunnerType runnerType = CommonUtils.getRunnerTypeByAction(action);
    if (RunnerType.ODPS.equals(runnerType)) {
      actionInfoMap.put(action, new OdpsActionInfo());
    } else if (RunnerType.HIVE.equals(runnerType)) {
      actionInfoMap.put(action, new HiveActionInfo());
    } else {
      actionInfoMap.put(action, new ValidateActionInfo());
    }
  }

  /**
   * Update execution progress, will trigger a action progress update
   * @param action action that the execution belongs to
   * @param progress new progress
   */
  protected synchronized void updateActionProgress(Action action,
                                                   Progress progress) throws MmaException {
    if (!actionInfoMap.containsKey(action)) {
      return;
    }

    AbstractActionInfo actionInfo = actionInfoMap.get(action);
    if (!actionInfo.progress.equals(progress)) {
      actionInfo.progress = progress;
      updateTaskProgress(actionInfo.progress);
    }
  }

  /**
   * Update task progress, triggered by an action progress update
   * @param actionNewProgress the new action progress that triggers this update
   */
  private void updateTaskProgress(Progress actionNewProgress) throws MmaException {
    boolean taskProgressChanged = false;

    switch (progress) {
      case NEW:
      case RUNNING:
        if (Progress.RUNNING.equals(actionNewProgress)) {
          progress = Progress.RUNNING;
          taskProgressChanged = true;
        } else if (Progress.FAILED.equals(actionNewProgress)) {
          progress = Progress.FAILED;
          taskProgressChanged = true;
        } else if (Progress.SUCCEEDED.equals(actionNewProgress)) {
          boolean allActionsSucceeded = actionInfoMap.values()
              .stream().allMatch(v -> v.progress.equals(Progress.SUCCEEDED));
          if (allActionsSucceeded) {
            progress = Progress.SUCCEEDED;
            taskProgressChanged = true;
          }
        }
        break;
      case FAILED:
      case SUCCEEDED:
      default:
    }

    // No need to update the table status when succeeded, since succeeded is default value
    if (taskProgressChanged) {
      if (Progress.FAILED.equals(progress)) {
        TaskScheduler.markAsFailed(tableMetaModel.databaseName, tableMetaModel.tableName);
      }
      //Partitioned table should update partition values when task.progress is Succeeded or failed in Action.VERIFICATION.
      if (!tableMetaModel.partitionColumns.isEmpty()) {
        if (Progress.SUCCEEDED.equals(progress) ||
            (Progress.FAILED.equals(progress) &&
                actionInfoMap.containsKey(Action.VERIFICATION) &&
                actionInfoMap.get(Action.VERIFICATION).progress.equals(Progress.FAILED))) {
          List<List<String>> partitionValuesList = tableMetaModel.partitions
              .stream()
              .map(p -> p.partitionValues)
              .collect(Collectors.toList());

          // TODO: retry
          mmaMetaManager.updateStatus(tableMetaModel.databaseName,
              tableMetaModel.tableName,
              partitionValuesList,
              MmaMetaManager.MigrationStatus.SUCCEEDED);

          partitionValuesList.forEach(partitionValues -> LOG.info("Partition succeeded: {}", String.join(", ", partitionValues)));
        }
      }
    }
  }

  /**
   * If all parent actions succeeded
   * @param action action
   * @return returns true if all parent actions succeeded, else false
   */
  public boolean isReadyAction(Action action) {
    if (!actionInfoMap.containsKey(action)) {
      return false;
    }

    // If is action is already scheduled
    if (!Progress.NEW.equals(actionInfoMap.get(action).progress)) {
      return false;
    }

    // If its previous actions have finished successfully
    for (Map.Entry<Action, AbstractActionInfo> entry : actionInfoMap.entrySet()) {
      // TODO: quite hacky
      if (entry.getKey().getPriority() < action.getPriority() &&
          !Progress.SUCCEEDED.equals(entry.getValue().progress)) {
        return false;
      }
    }

    return true;
  }

  @Override
  public String toString() {
    return taskName;
  }


  public String getSourceDatabaseName() {
    return tableMetaModel.databaseName;
  }

  public String getSourceTableName() {
    return tableMetaModel.tableName;
  }

  public String getName() {
    return taskName;
  }
}