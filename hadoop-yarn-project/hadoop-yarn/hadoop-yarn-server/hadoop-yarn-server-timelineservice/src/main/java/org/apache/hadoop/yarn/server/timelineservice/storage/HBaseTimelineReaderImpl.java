/**
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
package org.apache.hadoop.yarn.server.timelineservice.storage;


import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEntityType;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineEvent;
import org.apache.hadoop.yarn.api.records.timelineservice.TimelineMetric;
import org.apache.hadoop.yarn.server.timelineservice.storage.application.ApplicationColumn;
import org.apache.hadoop.yarn.server.timelineservice.storage.application.ApplicationColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.application.ApplicationRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.application.ApplicationTable;
import org.apache.hadoop.yarn.server.timelineservice.storage.apptoflow.AppToFlowColumn;
import org.apache.hadoop.yarn.server.timelineservice.storage.apptoflow.AppToFlowRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.apptoflow.AppToFlowTable;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.ColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.Separator;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.TimelineReaderUtils;
import org.apache.hadoop.yarn.server.timelineservice.storage.common.TimelineWriterUtils;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityColumn;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityColumnPrefix;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityRowKey;
import org.apache.hadoop.yarn.server.timelineservice.storage.entity.EntityTable;

import com.google.common.base.Preconditions;

public class HBaseTimelineReaderImpl
    extends AbstractService implements TimelineReader {

  private static final Log LOG = LogFactory
      .getLog(HBaseTimelineReaderImpl.class);
  private static final long DEFAULT_BEGIN_TIME = 0L;
  private static final long DEFAULT_END_TIME = Long.MAX_VALUE;

  private Configuration hbaseConf = null;
  private Connection conn;
  private EntityTable entityTable;
  private AppToFlowTable appToFlowTable;
  private ApplicationTable applicationTable;

  public HBaseTimelineReaderImpl() {
    super(HBaseTimelineReaderImpl.class.getName());
  }

  @Override
  public void serviceInit(Configuration conf) throws Exception {
    super.serviceInit(conf);
    hbaseConf = HBaseConfiguration.create(conf);
    conn = ConnectionFactory.createConnection(hbaseConf);
    entityTable = new EntityTable();
    appToFlowTable = new AppToFlowTable();
    applicationTable = new ApplicationTable();
  }

  @Override
  protected void serviceStop() throws Exception {
    if (conn != null) {
      LOG.info("closing the hbase Connection");
      conn.close();
    }
    super.serviceStop();
  }

  @Override
  public TimelineEntity getEntity(String userId, String clusterId,
      String flowId, Long flowRunId, String appId, String entityType,
      String entityId, EnumSet<Field> fieldsToRetrieve)
      throws IOException {
    validateParams(userId, clusterId, appId, entityType, entityId, true);
    // In reality both should be null or neither should be null
    if (flowId == null || flowRunId == null) {
      FlowContext context = lookupFlowContext(clusterId, appId);
      flowId = context.flowId;
      flowRunId = context.flowRunId;
    }
    if (fieldsToRetrieve == null) {
      fieldsToRetrieve = EnumSet.noneOf(Field.class);
    }

    boolean isApplication = isApplicationEntity(entityType);
    byte[] rowKey = isApplication ?
        ApplicationRowKey.getRowKey(clusterId, userId, flowId, flowRunId,
            appId) :
        EntityRowKey.getRowKey(clusterId, userId, flowId, flowRunId, appId,
            entityType, entityId);
    Get get = new Get(rowKey);
    get.setMaxVersions(Integer.MAX_VALUE);
    Result result = isApplication ?
        applicationTable.getResult(hbaseConf, conn, get) :
        entityTable.getResult(hbaseConf, conn, get);
    return parseEntity(result, fieldsToRetrieve,
        false, DEFAULT_BEGIN_TIME, DEFAULT_END_TIME, false, DEFAULT_BEGIN_TIME,
        DEFAULT_END_TIME, null, null, null, null, null, null, isApplication);
  }

  private static boolean isApplicationEntity(String entityType) {
    return TimelineEntityType.YARN_APPLICATION.toString().equals(entityType);
  }

  @Override
  public Set<TimelineEntity> getEntities(String userId, String clusterId,
      String flowId, Long flowRunId, String appId, String entityType,
      Long limit, Long createdTimeBegin, Long createdTimeEnd,
      Long modifiedTimeBegin, Long modifiedTimeEnd,
      Map<String, Set<String>> relatesTo, Map<String, Set<String>> isRelatedTo,
      Map<String, Object> infoFilters, Map<String, String> configFilters,
      Set<String> metricFilters, Set<String> eventFilters,
      EnumSet<Field> fieldsToRetrieve) throws IOException {
    validateParams(userId, clusterId, appId, entityType, null, false);
    // In reality both should be null or neither should be null
    if (flowId == null || flowRunId == null) {
      FlowContext context = lookupFlowContext(clusterId, appId);
      flowId = context.flowId;
      flowRunId = context.flowRunId;
    }
    if (limit == null) {
      limit = TimelineReader.DEFAULT_LIMIT;
    }
    if (createdTimeBegin == null) {
      createdTimeBegin = DEFAULT_BEGIN_TIME;
    }
    if (createdTimeEnd == null) {
      createdTimeEnd = DEFAULT_END_TIME;
    }
    if (modifiedTimeBegin == null) {
      modifiedTimeBegin = DEFAULT_BEGIN_TIME;
    }
    if (modifiedTimeEnd == null) {
      modifiedTimeEnd = DEFAULT_END_TIME;
    }
    if (fieldsToRetrieve == null) {
      fieldsToRetrieve = EnumSet.noneOf(Field.class);
    }

    NavigableSet<TimelineEntity> entities = new TreeSet<>();
    boolean isApplication = isApplicationEntity(entityType);
    if (isApplication) {
      // If getEntities() is called for an application, there can be at most
      // one entity. If the entity passes the filter, it is returned. Otherwise,
      // an empty set is returned.
      byte[] rowKey = ApplicationRowKey.getRowKey(clusterId, userId, flowId,
          flowRunId, appId);
      Get get = new Get(rowKey);
      get.setMaxVersions(Integer.MAX_VALUE);
      Result result = applicationTable.getResult(hbaseConf, conn, get);
      TimelineEntity entity = parseEntity(result, fieldsToRetrieve,
          true, createdTimeBegin, createdTimeEnd, true, modifiedTimeBegin,
          modifiedTimeEnd, isRelatedTo, relatesTo, infoFilters, configFilters,
          eventFilters, metricFilters, isApplication);
      if (entity != null) {
        entities.add(entity);
      }
    } else {
      // Scan through part of the table to find the entities belong to one app
      // and one type
      Scan scan = new Scan();
      scan.setRowPrefixFilter(EntityRowKey.getRowKeyPrefix(
          clusterId, userId, flowId, flowRunId, appId, entityType));
      scan.setMaxVersions(Integer.MAX_VALUE);
      ResultScanner scanner =
          entityTable.getResultScanner(hbaseConf, conn, scan);
      for (Result result : scanner) {
        TimelineEntity entity = parseEntity(result, fieldsToRetrieve,
            true, createdTimeBegin, createdTimeEnd,
            true, modifiedTimeBegin, modifiedTimeEnd,
            isRelatedTo, relatesTo, infoFilters, configFilters, eventFilters,
            metricFilters, isApplication);
        if (entity == null) {
          continue;
        }
        if (entities.size() > limit) {
          entities.pollLast();
        }
        entities.add(entity);
      }
    }
    return entities;
  }

  private FlowContext lookupFlowContext(String clusterId, String appId)
      throws IOException {
    byte[] rowKey = AppToFlowRowKey.getRowKey(clusterId, appId);
    Get get = new Get(rowKey);
    Result result = appToFlowTable.getResult(hbaseConf, conn, get);
    if (result != null && !result.isEmpty()) {
      return new FlowContext(
          AppToFlowColumn.FLOW_ID.readResult(result).toString(),
          ((Number) AppToFlowColumn.FLOW_RUN_ID.readResult(result)).longValue());
    } else {
       throw new IOException(
           "Unable to find the context flow ID and flow run ID for clusterId=" +
           clusterId + ", appId=" + appId);
    }
  }

  private static class FlowContext {
    private String flowId;
    private Long flowRunId;
    public FlowContext(String flowId, Long flowRunId) {
      this.flowId = flowId;
      this.flowRunId = flowRunId;
    }
  }

  private static void validateParams(String userId, String clusterId,
      String appId, String entityType, String entityId, boolean checkEntityId) {
    Preconditions.checkNotNull(userId, "userId shouldn't be null");
    Preconditions.checkNotNull(clusterId, "clusterId shouldn't be null");
    Preconditions.checkNotNull(appId, "appId shouldn't be null");
    Preconditions.checkNotNull(entityType, "entityType shouldn't be null");
    if (checkEntityId) {
      Preconditions.checkNotNull(entityId, "entityId shouldn't be null");
    }
  }

  private static TimelineEntity parseEntity(
      Result result, EnumSet<Field> fieldsToRetrieve,
      boolean checkCreatedTime, long createdTimeBegin, long createdTimeEnd,
      boolean checkModifiedTime, long modifiedTimeBegin, long modifiedTimeEnd,
      Map<String, Set<String>> isRelatedTo, Map<String, Set<String>> relatesTo,
      Map<String, Object> infoFilters, Map<String, String> configFilters,
      Set<String> eventFilters, Set<String> metricFilters,
      boolean isApplication)
          throws IOException {
    if (result == null || result.isEmpty()) {
      return null;
    }
    TimelineEntity entity = new TimelineEntity();
    String entityType = isApplication ?
        TimelineEntityType.YARN_APPLICATION.toString() :
        EntityColumn.TYPE.readResult(result).toString();
    entity.setType(entityType);
    String entityId = isApplication ?
        ApplicationColumn.ID.readResult(result).toString() :
        EntityColumn.ID.readResult(result).toString();
    entity.setId(entityId);

    // fetch created time
    Number createdTime = isApplication ?
        (Number)ApplicationColumn.CREATED_TIME.readResult(result) :
        (Number)EntityColumn.CREATED_TIME.readResult(result);
    entity.setCreatedTime(createdTime.longValue());
    if (checkCreatedTime && (entity.getCreatedTime() < createdTimeBegin ||
        entity.getCreatedTime() > createdTimeEnd)) {
      return null;
    }

    // fetch modified time
    Number modifiedTime = isApplication ?
        (Number)ApplicationColumn.MODIFIED_TIME.readResult(result) :
        (Number)EntityColumn.MODIFIED_TIME.readResult(result);
    entity.setModifiedTime(modifiedTime.longValue());
    if (checkModifiedTime && (entity.getModifiedTime() < modifiedTimeBegin ||
        entity.getModifiedTime() > modifiedTimeEnd)) {
      return null;
    }

    // fetch is related to entities
    boolean checkIsRelatedTo = isRelatedTo != null && isRelatedTo.size() > 0;
    if (fieldsToRetrieve.contains(Field.ALL) ||
        fieldsToRetrieve.contains(Field.IS_RELATED_TO) || checkIsRelatedTo) {
      if (isApplication) {
        readRelationship(entity, result, ApplicationColumnPrefix.IS_RELATED_TO,
            true);
      } else {
        readRelationship(entity, result, EntityColumnPrefix.IS_RELATED_TO,
            true);
      }
      if (checkIsRelatedTo && !TimelineReaderUtils.matchRelations(
          entity.getIsRelatedToEntities(), isRelatedTo)) {
        return null;
      }
      if (!fieldsToRetrieve.contains(Field.ALL) &&
          !fieldsToRetrieve.contains(Field.IS_RELATED_TO)) {
        entity.getIsRelatedToEntities().clear();
      }
    }

    // fetch relates to entities
    boolean checkRelatesTo = relatesTo != null && relatesTo.size() > 0;
    if (fieldsToRetrieve.contains(Field.ALL) ||
        fieldsToRetrieve.contains(Field.RELATES_TO) || checkRelatesTo) {
      if (isApplication) {
        readRelationship(entity, result, ApplicationColumnPrefix.RELATES_TO,
            false);
      } else {
        readRelationship(entity, result, EntityColumnPrefix.RELATES_TO, false);
      }
      if (checkRelatesTo && !TimelineReaderUtils.matchRelations(
          entity.getRelatesToEntities(), relatesTo)) {
        return null;
      }
      if (!fieldsToRetrieve.contains(Field.ALL) &&
          !fieldsToRetrieve.contains(Field.RELATES_TO)) {
        entity.getRelatesToEntities().clear();
      }
    }

    // fetch info
    boolean checkInfo = infoFilters != null && infoFilters.size() > 0;
    if (fieldsToRetrieve.contains(Field.ALL) ||
        fieldsToRetrieve.contains(Field.INFO) || checkInfo) {
      if (isApplication) {
        readKeyValuePairs(entity, result, ApplicationColumnPrefix.INFO, false);
      } else {
        readKeyValuePairs(entity, result, EntityColumnPrefix.INFO, false);
      }
      if (checkInfo &&
          !TimelineReaderUtils.matchFilters(entity.getInfo(), infoFilters)) {
        return null;
      }
      if (!fieldsToRetrieve.contains(Field.ALL) &&
          !fieldsToRetrieve.contains(Field.INFO)) {
        entity.getInfo().clear();
      }
    }

    // fetch configs
    boolean checkConfigs = configFilters != null && configFilters.size() > 0;
    if (fieldsToRetrieve.contains(Field.ALL) ||
        fieldsToRetrieve.contains(Field.CONFIGS) || checkConfigs) {
      if (isApplication) {
        readKeyValuePairs(entity, result, ApplicationColumnPrefix.CONFIG, true);
      } else {
        readKeyValuePairs(entity, result, EntityColumnPrefix.CONFIG, true);
      }
      if (checkConfigs && !TimelineReaderUtils.matchFilters(
          entity.getConfigs(), configFilters)) {
        return null;
      }
      if (!fieldsToRetrieve.contains(Field.ALL) &&
          !fieldsToRetrieve.contains(Field.CONFIGS)) {
        entity.getConfigs().clear();
      }
    }

    // fetch events
    boolean checkEvents = eventFilters != null && eventFilters.size() > 0;
    if (fieldsToRetrieve.contains(Field.ALL) ||
        fieldsToRetrieve.contains(Field.EVENTS) || checkEvents) {
      readEvents(entity, result, isApplication);
      if (checkEvents && !TimelineReaderUtils.matchEventFilters(
          entity.getEvents(), eventFilters)) {
        return null;
      }
      if (!fieldsToRetrieve.contains(Field.ALL) &&
          !fieldsToRetrieve.contains(Field.EVENTS)) {
        entity.getEvents().clear();
      }
    }

    // fetch metrics
    boolean checkMetrics = metricFilters != null && metricFilters.size() > 0;
    if (fieldsToRetrieve.contains(Field.ALL) ||
        fieldsToRetrieve.contains(Field.METRICS) || checkMetrics) {
      readMetrics(entity, result, isApplication);
      if (checkMetrics && !TimelineReaderUtils.matchMetricFilters(
          entity.getMetrics(), metricFilters)) {
        return null;
      }
      if (!fieldsToRetrieve.contains(Field.ALL) &&
          !fieldsToRetrieve.contains(Field.METRICS)) {
        entity.getMetrics().clear();
      }
    }
    return entity;
  }

  private static <T> void readRelationship(
      TimelineEntity entity, Result result, ColumnPrefix<T> prefix,
      boolean isRelatedTo) throws IOException {
    // isRelatedTo and relatesTo are of type Map<String, Set<String>>
    Map<String, Object> columns = prefix.readResults(result);
    for (Map.Entry<String, Object> column : columns.entrySet()) {
      for (String id : Separator.VALUES.splitEncoded(
          column.getValue().toString())) {
        if (isRelatedTo) {
          entity.addIsRelatedToEntity(column.getKey(), id);
        } else {
          entity.addRelatesToEntity(column.getKey(), id);
        }
      }
    }
  }

  private static <T> void readKeyValuePairs(
      TimelineEntity entity, Result result, ColumnPrefix<T> prefix,
      boolean isConfig) throws IOException {
    // info and configuration are of type Map<String, Object or String>
    Map<String, Object> columns = prefix.readResults(result);
    if (isConfig) {
      for (Map.Entry<String, Object> column : columns.entrySet()) {
        entity.addConfig(column.getKey(), column.getValue().toString());
      }
    } else {
      entity.addInfo(columns);
    }
  }

  /**
   * Read events from the entity table or the application table. The column name
   * is of the form "eventId=timestamp=infoKey" where "infoKey" may be omitted
   * if there is no info associated with the event.
   *
   * See {@link EntityTable} and {@link ApplicationTable} for a more detailed
   * schema description.
   */
  private static void readEvents(TimelineEntity entity, Result result,
      boolean isApplication) throws IOException {
    Map<String, TimelineEvent> eventsMap = new HashMap<>();
    Map<?, Object> eventsResult = isApplication ?
        ApplicationColumnPrefix.EVENT.
            readResultsHavingCompoundColumnQualifiers(result) :
        EntityColumnPrefix.EVENT.
            readResultsHavingCompoundColumnQualifiers(result);
    for (Map.Entry<?, Object> eventResult : eventsResult.entrySet()) {
      byte[][] karr = (byte[][])eventResult.getKey();
      // the column name is of the form "eventId=timestamp=infoKey"
      if (karr.length == 3) {
        String id = Bytes.toString(karr[0]);
        long ts = TimelineWriterUtils.invert(Bytes.toLong(karr[1]));
        String key = Separator.VALUES.joinEncoded(id, Long.toString(ts));
        TimelineEvent event = eventsMap.get(key);
        if (event == null) {
          event = new TimelineEvent();
          event.setId(id);
          event.setTimestamp(ts);
          eventsMap.put(key, event);
        }
        // handle empty info
        String infoKey = karr[2].length == 0 ? null : Bytes.toString(karr[2]);
        if (infoKey != null) {
          event.addInfo(infoKey, eventResult.getValue());
        }
      } else {
        LOG.warn("incorrectly formatted column name: it will be discarded");
        continue;
      }
    }
    Set<TimelineEvent> eventsSet = new HashSet<>(eventsMap.values());
    entity.addEvents(eventsSet);
  }

  private static void readMetrics(TimelineEntity entity, Result result,
      boolean isApplication) throws IOException {
    NavigableMap<String, NavigableMap<Long, Number>> metricsResult;
    if (isApplication) {
      metricsResult =
          ApplicationColumnPrefix.METRIC.readResultsWithTimestamps(result);
    } else {
      metricsResult =
          EntityColumnPrefix.METRIC.readResultsWithTimestamps(result);
    }
    for (Map.Entry<String, NavigableMap<Long, Number>> metricResult:
        metricsResult.entrySet()) {
      TimelineMetric metric = new TimelineMetric();
      metric.setId(metricResult.getKey());
      // Simply assume that if the value set contains more than 1 elements, the
      // metric is a TIME_SERIES metric, otherwise, it's a SINGLE_VALUE metric
      metric.setType(metricResult.getValue().size() > 1 ?
          TimelineMetric.Type.TIME_SERIES : TimelineMetric.Type.SINGLE_VALUE);
      metric.addValues(metricResult.getValue());
      entity.addMetric(metric);
    }
  }
}
