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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.SortedSet;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;

public class TableSplitter implements TaskManager {

  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final Random RANDOM = new Random();

  private static final Logger LOG = LogManager.getLogger(TableSplitter.class);
  private List<MetaSource.TableMetaModel> tables;
  private List<Task> tasks = new LinkedList<>();
  private MmaMetaManager mmaMetaManager;

  public TableSplitter(List<MetaSource.TableMetaModel> tables, MmaMetaManager mmaMetaManager) {
    this.tables = tables;
    this.mmaMetaManager = mmaMetaManager;
  }

  @Override
  public List<Task> generateTasks(SortedSet<Action> actions) throws MmaException {
    for (MetaSource.TableMetaModel tableMetaModel : this.tables) {

      MmaConfig.AdditionalTableConfig config =
          mmaMetaManager.getConfig(tableMetaModel.databaseName, tableMetaModel.tableName)
              .getAdditionalTableConfig();

      // Empty partitions, means the table is non-partition table.
      if (tableMetaModel.partitionColumns.isEmpty()) {
        tasks.add(generateTaskForNonPartitionedTable(tableMetaModel, config, actions));
      } else {
        tasks.addAll(generateTaskForPartitionedTable(tableMetaModel, config, actions));
      }
    }

    LOG.warn("Tasks: {}",
             tasks.stream().map(Task::getName).collect(Collectors.joining(", ")));

    return this.tasks;
  }

  @VisibleForTesting
  protected Task generateTaskForNonPartitionedTable(MetaSource.TableMetaModel tableMetaModel,
                                                    MmaConfig.AdditionalTableConfig config,
                                                    SortedSet<Action> actions) {

    String taskName = tableMetaModel.databaseName + "." + tableMetaModel.tableName;
    Task task = new Task(taskName, tableMetaModel, config, mmaMetaManager);
    for (Action action : actions) {
      if (Action.ODPS_ADD_PARTITION.equals(action)) {
        continue;
      }
      task.addActionInfo(action);
    }
    return task;
  }

  @VisibleForTesting
  protected List<Task> generateTaskForPartitionedTable(MetaSource.TableMetaModel tableMetaModel,
                                                       MmaConfig.AdditionalTableConfig config,
                                                       SortedSet<Action> actions) {
    // TODO: add directly to this.task could avoid creating an extra list, but will make it much
    // harder to test
    List<Task> ret = new LinkedList<>();

    // If this table doesn't have any partition, create a task an return
    // TODO: Handle this case in a more elegant way
    if (tableMetaModel.partitions.isEmpty()) {
      LOG.info("Partitioned table job with no partition, db: {}, tbl: {}",
               tableMetaModel.databaseName,
               tableMetaModel.tableName);
      String taskName = tableMetaModel.databaseName + "." + tableMetaModel.tableName;
      Task task = new Task(taskName, tableMetaModel.clone(), config, mmaMetaManager);
      task.addActionInfo(Action.ODPS_CREATE_TABLE);
      ret.add(task);
      return ret;
    }

    int partitionGroupSize;
    // By default, partition group size is number of partitions
    if (config != null && config.getPartitionGroupSize() > 0) {
      partitionGroupSize = config.getPartitionGroupSize();
    } else {
      partitionGroupSize = tableMetaModel.partitions.size();
    }

    // TODO: should do this in meta configuration
    if (partitionGroupSize <= 0) {
      throw new IllegalArgumentException("Invalid partition group size: " + partitionGroupSize);
    }

    int startIdx = 0;
    int taskIdx = 0;
    String taskNamePrefix = getUniqueTaskName(tableMetaModel.databaseName,
                                               tableMetaModel.tableName);
    while (startIdx < tableMetaModel.partitions.size()) {
      MetaSource.TableMetaModel clone = tableMetaModel.clone();

      // Set partitions
      int endIdx = Math.min(tableMetaModel.partitions.size(), startIdx + partitionGroupSize);
      clone.partitions = new ArrayList<>(tableMetaModel.partitions.subList(startIdx, endIdx));

      String taskName = taskNamePrefix + "." + taskIdx;
      Task task = new Task(taskName, clone, config, mmaMetaManager);
      for (Action action : actions) {
        task.addActionInfo(action);
      }
      ret.add(task);

      startIdx += partitionGroupSize;
      taskIdx += 1;
    }

    return ret;
  }

  private static String getUniqueTaskName(String db, String tbl) {
    StringBuilder sb = new StringBuilder();
    sb.append(db).append(".").append(tbl).append(".");

    for (int i = 0; i < 4; i++) {
      sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }
}