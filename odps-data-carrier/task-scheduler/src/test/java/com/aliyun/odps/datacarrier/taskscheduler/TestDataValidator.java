package com.aliyun.odps.datacarrier.taskscheduler;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestDataValidator {

  @Test(timeout = 5000)
  public void testValidateTaskCountResultByPartition() {

    Task task = new Task("TestDataBase", "TestTable", new MetaSource.TableMetaModel(), null);
    task.addExecutionInfo(Action.HIVE_VALIDATE, task.getTableNameWithProject());
    task.addExecutionInfo(Action.ODPS_VALIDATE, task.getTableNameWithProject());
    int date = 20200218;
    int partitionNum = 5;
    int succeededPartitionNum = 2;
    task.tableMetaModel.partitions.addAll(TestTableSplitter.createPartitions(date, partitionNum));

    Map<String, String> multiRecordResult = new HashMap<>();
    for (int i = 0; i < succeededPartitionNum; i++) {
      multiRecordResult.put(String.valueOf(date + i), String.valueOf(i));
    }

    multiRecordResult.put("20200226", "12");
    task.actionInfoMap.get(Action.HIVE_VALIDATE).executionInfoMap.get(
        task.getTableNameWithProject()).setMultiRecordResult(multiRecordResult);
    task.actionInfoMap.get(Action.ODPS_VALIDATE).executionInfoMap.get(
        task.getTableNameWithProject()).setMultiRecordResult(multiRecordResult);

    DataValidator dataValidator = new DataValidator();
    DataValidator.ValidationResult result = dataValidator.validationPartitions(task);
    assertTrue(result.succeededPartitions.size() == 2);
    assertEquals(result.succeededPartitions.get(0).get(0), "20200218");
    assertEquals(result.succeededPartitions.get(1).get(0), "20200219");
    assertTrue(result.failedPartitions.size() == 3);
    assertEquals(result.failedPartitions.get(0).get(0), "20200220");
    assertEquals(result.failedPartitions.get(1).get(0), "20200221");
    assertEquals(result.failedPartitions.get(2).get(0), "20200222");
  }
}