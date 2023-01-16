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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.v2.odps

import com.aliyun.odps.table.DataFormat
import com.aliyun.odps.table.configuration.{CompressionCodec, ReaderOptions}
import com.aliyun.odps.table.metrics.MetricNames
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.read.{InputPartition, PartitionReader, PartitionReaderFactory}
import org.apache.spark.sql.odps.vectorized._
import org.apache.spark.sql.types._
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.TaskContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.catalyst.expressions.codegen.GenerateUnsafeProjection
import org.apache.spark.sql.catalyst.expressions.{SpecificInternalRow, UnsafeRow}
import org.apache.spark.sql.odps.OdpsClient
import org.apache.spark.util.SerializableConfiguration

import scala.collection.JavaConverters._

case class OdpsPartitionReaderFactory(broadcastedConf: Broadcast[SerializableConfiguration],
                                       readDataSchema: StructType,
                                       readPartitionSchema: StructType,
                                       supportColumnarReads: Boolean,
                                       batchSize: Int,
                                       reusedBatch: Boolean,
                                       compressionCodec: String)
  extends PartitionReaderFactory with Logging {

  private val output = readDataSchema.toAttributes ++ readPartitionSchema.toAttributes
  private val allNames = output.map(_.name)
  private val allTypes = output.map(_.dataType)
  private val arrowDataFormat = new DataFormat(DataFormat.Type.ARROW, DataFormat.Version.V5)
  private val recordDataFormat = new DataFormat(DataFormat.Type.RECORD, DataFormat.Version.UNKNOWN)
  private val codec = CompressionCodec.byName(compressionCodec).orElse(CompressionCodec.NO_COMPRESSION)

  override def createReader(partition: InputPartition): PartitionReader[InternalRow] = {
    if (output.isEmpty) {
      assert(partition.isInstanceOf[OdpsEmptyColumnPartition], "Output column is empty")
      val emptyColumnPartition = partition.asInstanceOf[OdpsEmptyColumnPartition]
      return new PartitionReader[InternalRow] {
        private val unsafeRow: InternalRow = InternalRow()
        private var count = 0
        override def next(): Boolean = {
          if (count < emptyColumnPartition.rowCount) {
            count = count + 1
            true
          } else {
            false
          }
        }
        override def get(): InternalRow = unsafeRow
        override def close(): Unit = {
        }
      }
    }

    val conf = broadcastedConf.value.value
    val settings = OdpsClient.builder.config(conf).getOrCreate.getEnvironmentSettings
    val odpsScanPartition = partition.asInstanceOf[OdpsScanPartition]
    val supportRecordReader = odpsScanPartition.scan.supportsDataFormat(recordDataFormat)
    if (supportRecordReader) {
      val readerOptions = ReaderOptions.newBuilder()
        .withSettings(settings)
        .build()

      val recordReader = odpsScanPartition.scan.createRecordReader(odpsScanPartition.inputSplit, readerOptions)
      val readTypeInfos = odpsScanPartition.scan.readSchema.getColumns.asScala.map(_.getTypeInfo)

      new PartitionReader[InternalRow] {
        private val converters = readTypeInfos.map(OdpsUtils.odpsData2SparkData)
        private val currentRow = {
          val row = new SpecificInternalRow(allTypes)
          row
        }
        private val unsafeProjection = GenerateUnsafeProjection.generate(output, output)
        private var unsafeRow: UnsafeRow = _

        override def next(): Boolean = {
          if (!recordReader.hasNext) {
            false
          } else {
            val record = recordReader.get()
            var i = 0
            if (record ne null) {
              while (i < converters.length) {
                val value = record.get(i)
                if (value ne null) {
                  currentRow.update(i, converters(i)(value))
                } else {
                  currentRow.setNullAt(i)
                }
                i += 1
              }
            } else {
              while (i < allTypes.length) {
                currentRow.setNullAt(i)
                i += 1
              }
            }
            unsafeRow = unsafeProjection(currentRow)
            true
          }
        }

        override def get(): InternalRow = unsafeRow

        override def close(): Unit = {
          recordReader.currentMetricsValues.counter(MetricNames.BYTES_COUNT).ifPresent(c =>
            TaskContext.get().taskMetrics().inputMetrics
              .incBytesRead(c.getCount))
          recordReader.close()
        }
      }
    } else {
      val supportArrowReader = odpsScanPartition.scan.supportsDataFormat(arrowDataFormat)
      if (supportArrowReader) {
        new PartitionReader[InternalRow] {
          private var unsafeRow: InternalRow = _
          private val batchReader = createColumnarReader(partition)
          private var rowIterator: Iterator[InternalRow] = _

          private def hasNext: Boolean = {
            if (rowIterator == null || !rowIterator.hasNext) {
              if (batchReader.next) {
                val batch = batchReader.get
                if (batch != null) {
                  rowIterator = batch.rowIterator.asScala
                } else {
                  rowIterator = null
                }
              } else {
                rowIterator = null
              }
            }
            rowIterator != null && rowIterator.hasNext
          }

          override def next(): Boolean = {
            if (!hasNext) {
              false
            } else {
              unsafeRow = rowIterator.next()
              true
            }
          }

          override def get(): InternalRow = unsafeRow

          override def close(): Unit = {
            batchReader.close()
          }
        }
      } else {
        throw new UnsupportedOperationException("Table provider unsupported record/arrow data format")
      }
    }
  }

  override def createColumnarReader(partition: InputPartition): PartitionReader[ColumnarBatch] = {
    val conf = broadcastedConf.value.value
    // TODO: bearer token refresh
    val settings = OdpsClient.builder.config(conf).getOrCreate.getEnvironmentSettings

    val odpsScanPartition = partition.asInstanceOf[OdpsScanPartition]
    val readerOptions = ReaderOptions.newBuilder()
      .withMaxBatchRowCount(batchSize)
      .withSettings(settings)
      .withCompressionCodec(codec)
      .withReuseBatch(reusedBatch)
      .build()

    val arrowReader = odpsScanPartition.scan.createArrowReader(odpsScanPartition.inputSplit, readerOptions)
    val schema = odpsScanPartition.scan.readSchema

    new PartitionReader[ColumnarBatch] {
      private var columnarBatch: ColumnarBatch = _

      private def updateColumnBatch(root: VectorSchemaRoot): Unit = {
        if (columnarBatch != null && !reusedBatch) {
          columnarBatch.close()
        }
        val vectors = root.getFieldVectors
        val fields = root.getSchema.getFields
        val fieldNameIdxMap = fields.asScala.map(f => f.getName).zipWithIndex.toMap
        if (allNames.nonEmpty) {
          val arrowVectors =
            allNames.map(name => {
              fieldNameIdxMap.get(name) match {
                case Some(fieldIdx) => new OdpsArrowColumnVector(vectors.get(fieldIdx))
                case None => throw new RuntimeException("Missing column " + name + " from arrow reader.")
              }
            }).toList
          columnarBatch = new ColumnarBatch(arrowVectors.toArray)
        } else {
          val arrowVectors =
            vectors.asScala.map(v => {
              new OdpsArrowColumnVector(v)
            }).toList
          columnarBatch = new ColumnarBatch(arrowVectors.toArray)
        }
        columnarBatch.setNumRows(root.getRowCount)
      }

      override def next(): Boolean = {
        if (!arrowReader.hasNext) {
          false
        } else {
          updateColumnBatch(arrowReader.get())
          true
        }
      }

      override def get(): ColumnarBatch = columnarBatch

      override def close(): Unit = {
        arrowReader.currentMetricsValues.counter(MetricNames.BYTES_COUNT).ifPresent(c =>
          TaskContext.get().taskMetrics().inputMetrics
          .incBytesRead(c.getCount))
        if (columnarBatch != null)
          columnarBatch.close()
        arrowReader.close()
      }
    }
  }

  override def supportColumnarReads(partition: InputPartition): Boolean = {
    supportColumnarReads &&
      partition.isInstanceOf[OdpsScanPartition] &&
      partition.asInstanceOf[OdpsScanPartition].scan.supportsDataFormat(arrowDataFormat)
  }
}
