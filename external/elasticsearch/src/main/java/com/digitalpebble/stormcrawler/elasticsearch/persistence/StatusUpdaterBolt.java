/**
 * Licensed to DigitalPebble Ltd under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.digitalpebble.stormcrawler.elasticsearch.persistence;

import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;

import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.elasticsearch.ElasticSearchConnection;
import com.digitalpebble.stormcrawler.persistence.AbstractStatusUpdaterBolt;
import com.digitalpebble.stormcrawler.persistence.Status;
import com.digitalpebble.stormcrawler.util.ConfUtils;
import com.digitalpebble.stormcrawler.util.URLPartitioner;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang.StringUtils;
import org.apache.storm.metric.api.MultiCountMetric;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.XContentBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple bolt which stores the status of URLs into ElasticSearch. Takes the tuples coming from the
 * 'status' stream. To be used in combination with a Spout to read from the index.
 */
public class StatusUpdaterBolt extends AbstractStatusUpdaterBolt
        implements RemovalListener<String, List<Tuple>>, BulkProcessor.Listener {

    private static final Logger LOG = LoggerFactory.getLogger(StatusUpdaterBolt.class);

    private String ESBoltType = "status";

    private static final String ESStatusIndexNameParamName = "es.%s.index.name";
    private static final String ESStatusRoutingParamName = "es.%s.routing";
    private static final String ESStatusRoutingFieldParamName = "es.%s.routing.fieldname";

    private boolean routingFieldNameInMetadata = false;

    private String indexName;

    private URLPartitioner partitioner;

    /** whether to apply the same partitioning logic used for politeness for routing, e.g byHost */
    private boolean doRouting;

    /** Store the key used for routing explicitly as a field in metadata * */
    private String fieldNameForRoutingKey = null;

    private ElasticSearchConnection connection;

    private Cache<String, List<Tuple>> waitAck;

    private MultiCountMetric eventCounter;

    public StatusUpdaterBolt() {
        super();
    }

    /**
     * Loads the configuration using a substring different from the default value 'status' in order
     * to distinguish it from the spout configurations
     */
    public StatusUpdaterBolt(String boltType) {
        super();
        ESBoltType = boltType;
    }

    @Override
    public void prepare(
            Map<String, Object> stormConf, TopologyContext context, OutputCollector collector) {

        super.prepare(stormConf, context, collector);

        indexName =
                ConfUtils.getString(
                        stormConf,
                        String.format(StatusUpdaterBolt.ESStatusIndexNameParamName, ESBoltType),
                        "status");

        doRouting =
                ConfUtils.getBoolean(
                        stormConf,
                        String.format(StatusUpdaterBolt.ESStatusRoutingParamName, ESBoltType),
                        false);

        partitioner = new URLPartitioner();
        partitioner.configure(stormConf);

        fieldNameForRoutingKey =
                ConfUtils.getString(
                        stormConf,
                        String.format(StatusUpdaterBolt.ESStatusRoutingFieldParamName, ESBoltType));
        if (StringUtils.isNotBlank(fieldNameForRoutingKey)) {
            if (fieldNameForRoutingKey.startsWith("metadata.")) {
                routingFieldNameInMetadata = true;
                fieldNameForRoutingKey = fieldNameForRoutingKey.substring("metadata.".length());
            }
            // periods are not allowed in ES2 - replace with %2E
            fieldNameForRoutingKey = fieldNameForRoutingKey.replaceAll("\\.", "%2E");
        }

        waitAck =
                Caffeine.newBuilder()
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .removalListener(this)
                        .build();

        // create gauge for waitAck
        context.registerMetric(
                "waitAck",
                () -> {
                    return waitAck.estimatedSize();
                },
                10);

        try {
            connection = ElasticSearchConnection.getConnection(stormConf, ESBoltType, this);
        } catch (Exception e1) {
            LOG.error("Can't connect to ElasticSearch", e1);
            throw new RuntimeException(e1);
        }

        this.eventCounter = context.registerMetric("counters", new MultiCountMetric(), 30);
    }

    @Override
    public void cleanup() {
        if (connection != null) connection.close();
    }

    @Override
    public void store(
            String url, Status status, Metadata metadata, Optional<Date> nextFetch, Tuple tuple)
            throws Exception {

        String sha256hex = org.apache.commons.codec.digest.DigestUtils.sha256Hex(url);

        // need to synchronize: otherwise it might get added to the cache
        // without having been sent to ES
        synchronized (waitAck) {
            // check that the same URL is not being sent to ES
            List<Tuple> alreadySent = waitAck.getIfPresent(sha256hex);
            if (alreadySent != null && status.equals(Status.DISCOVERED)) {
                // if this object is discovered - adding another version of it
                // won't make any difference
                LOG.debug(
                        "Already being sent to ES {} with status {} and ID {}",
                        url,
                        status,
                        sha256hex);
                // ack straight away!
                eventCounter.scope("acked").incrBy(1);
                super.ack(tuple, url);
                return;
            }
        }

        XContentBuilder builder = jsonBuilder().startObject();
        builder.field("url", url);
        builder.field("status", status);

        // check that we don't overwrite an existing entry
        // When create is used, the index operation will fail if a document
        // by that id already exists in the index.
        boolean create = status.equals(Status.DISCOVERED);

        builder.startObject("metadata");
        Iterator<String> mdKeys = metadata.keySet().iterator();
        while (mdKeys.hasNext()) {
            String mdKey = mdKeys.next();
            String[] values = metadata.getValues(mdKey);
            // periods are not allowed in ES2 - replace with %2E
            mdKey = mdKey.replaceAll("\\.", "%2E");
            builder.array(mdKey, values);
        }

        String partitionKey = partitioner.getPartition(url, metadata);
        if (partitionKey == null) {
            partitionKey = "_DEFAULT_";
        }

        // store routing key in metadata?
        if (StringUtils.isNotBlank(fieldNameForRoutingKey) && routingFieldNameInMetadata) {
            builder.field(fieldNameForRoutingKey, partitionKey);
        }

        builder.endObject();

        // store routing key outside metadata?
        if (StringUtils.isNotBlank(fieldNameForRoutingKey) && !routingFieldNameInMetadata) {
            builder.field(fieldNameForRoutingKey, partitionKey);
        }

        if (nextFetch.isPresent()) {
            builder.timeField("nextFetchDate", nextFetch.get());
        }

        builder.endObject();

        IndexRequest request = new IndexRequest(getIndexName(metadata));
        request.source(builder).id(sha256hex).create(create);

        if (doRouting) {
            request.routing(partitionKey);
        }

        synchronized (waitAck) {
            List<Tuple> tt = waitAck.getIfPresent(sha256hex);
            if (tt == null) {
                tt = new LinkedList<>();
                waitAck.put(sha256hex, tt);
            }
            tt.add(tuple);
            LOG.debug("Added to waitAck {} with ID {} total {}", url, sha256hex, tt.size());
        }

        LOG.debug("Sending to ES buffer {} with ID {}", url, sha256hex);

        connection.getProcessor().add(request);
    }

    @Override
    public void onRemoval(
            @Nullable String key, @Nullable List<Tuple> value, @NotNull RemovalCause cause) {
        if (!cause.wasEvicted()) return;
        LOG.error("Purged from waitAck {} with {} values", key, value.size());
        for (Tuple t : value) {
            eventCounter.scope("failed").incrBy(1);
            _collector.fail(t);
        }
    }

    @Override
    public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
        LOG.debug("afterBulk [{}] with {} responses", executionId, request.numberOfActions());
        long msec = response.getTook().getMillis();
        eventCounter.scope("bulks_received").incrBy(1);
        eventCounter.scope("bulk_msec").incrBy(msec);
        Iterator<BulkItemResponse> bulkitemiterator = response.iterator();
        int itemcount = 0;
        int acked = 0;
        int failurecount = 0;

        synchronized (waitAck) {
            while (bulkitemiterator.hasNext()) {
                BulkItemResponse bir = bulkitemiterator.next();
                itemcount++;
                String id = bir.getId();
                BulkItemResponse.Failure f = bir.getFailure();
                boolean failed = false;
                if (f != null) {
                    // already discovered
                    if (f.getStatus().equals(RestStatus.CONFLICT)) {
                        eventCounter.scope("doc_conflicts").incrBy(1);
                        LOG.debug("Doc conflict ID {}", id);
                    } else {
                        LOG.error("Update ID {}, failure: {}", id, f);
                        failed = true;
                    }
                }
                List<Tuple> xx = waitAck.getIfPresent(id);
                if (xx != null) {
                    LOG.debug("Acked {} tuple(s) for ID {}", xx.size(), id);
                    for (Tuple x : xx) {
                        if (!failed) {
                            String url = x.getStringByField("url");
                            acked++;
                            // ack and put in cache
                            LOG.debug("Acked {} with ID {}", url, id);
                            eventCounter.scope("acked").incrBy(1);
                            super.ack(x, url);
                        } else {
                            failurecount++;
                            eventCounter.scope("failed").incrBy(1);
                            _collector.fail(x);
                        }
                    }
                    waitAck.invalidate(id);
                } else {
                    LOG.warn("Could not find unacked tuple for {}", id);
                }
            }

            LOG.info(
                    "Bulk response [{}] : items {}, waitAck {}, acked {}, failed {}",
                    executionId,
                    itemcount,
                    waitAck.estimatedSize(),
                    acked,
                    failurecount);
            if (waitAck.estimatedSize() > 0 && LOG.isDebugEnabled()) {
                for (String kinaw : waitAck.asMap().keySet()) {
                    LOG.debug(
                            "Still in wait ack after bulk response [{}] => {}", executionId, kinaw);
                }
            }
        }
    }

    @Override
    public void afterBulk(long executionId, BulkRequest request, Throwable throwable) {
        eventCounter.scope("bulks_received").incrBy(1);
        LOG.error("Exception with bulk {} - failing the whole lot ", executionId, throwable);
        synchronized (waitAck) {
            // WHOLE BULK FAILED
            // mark all the docs as fail
            Iterator<DocWriteRequest<?>> itreq = request.requests().iterator();
            while (itreq.hasNext()) {
                DocWriteRequest bir = itreq.next();
                String id = bir.id();
                List<Tuple> xx = waitAck.getIfPresent(id);
                if (xx != null) {
                    LOG.debug("Failed {} tuple(s) for ID {}", xx.size(), id);
                    for (Tuple x : xx) {
                        // fail it
                        eventCounter.scope("failed").incrBy(1);
                        _collector.fail(x);
                    }
                    waitAck.invalidate(id);
                } else {
                    LOG.warn("Could not find unacked tuple for {}", id);
                }
            }
        }
    }

    @Override
    public void beforeBulk(long executionId, BulkRequest request) {
        LOG.debug("beforeBulk {} with {} actions", executionId, request.numberOfActions());
        eventCounter.scope("bulks_received").incrBy(1);
    }

    /**
     * Must be overridden for implementing custom index names based on some metadata information By
     * Default, indexName coming from config is used
     */
    protected String getIndexName(Metadata m) {
        return indexName;
    }
}
