/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.impl.journal;

import com.hazelcast.config.EventJournalConfig;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.RingbufferConfig;
import com.hazelcast.core.EntryEventType;
import com.hazelcast.internal.cluster.Versions;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.DataType;
import com.hazelcast.ringbuffer.impl.ReadResultSetImpl;
import com.hazelcast.ringbuffer.impl.RingbufferContainer;
import com.hazelcast.ringbuffer.impl.RingbufferService;
import com.hazelcast.ringbuffer.impl.RingbufferWaitNotifyKey;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.spi.ObjectNamespace;
import com.hazelcast.spi.WaitNotifyKey;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.operationparker.OperationParker;

import static com.hazelcast.core.EntryEventType.ADDED;
import static com.hazelcast.core.EntryEventType.EVICTED;
import static com.hazelcast.core.EntryEventType.REMOVED;
import static com.hazelcast.core.EntryEventType.UPDATED;

/**
 * The map event journal implementation based on the {@link com.hazelcast.ringbuffer.Ringbuffer}.
 * It will add all journal events into a {@link RingbufferContainer} with the provided namespace
 * and partition ID and allows checking if the map has a configured event journal.
 * Adapts the {@link EventJournalConfig} to the {@link RingbufferConfig} when creating the ringbuffer.
 */
public class RingbufferMapEventJournalImpl implements MapEventJournal {
    private final NodeEngineImpl nodeEngine;
    private final ILogger logger;

    public RingbufferMapEventJournalImpl(NodeEngine engine) {
        this.nodeEngine = (NodeEngineImpl) engine;
        this.logger = this.nodeEngine.getLogger(RingbufferMapEventJournalImpl.class);
    }

    @Override
    public void writeUpdateEvent(ObjectNamespace namespace, int partitionId, Data key, Object oldValue, Object newValue) {
        addToEventRingbuffer(namespace, partitionId, UPDATED, key, oldValue, newValue);
    }

    @Override
    public void writeAddEvent(ObjectNamespace namespace, int partitionId, Data key, Object value) {
        addToEventRingbuffer(namespace, partitionId, ADDED, key, null, value);
    }

    @Override
    public void writeRemoveEvent(ObjectNamespace namespace, int partitionId, Data key, Object value) {
        addToEventRingbuffer(namespace, partitionId, REMOVED, key, value, null);
    }

    @Override
    public void writeEvictEvent(ObjectNamespace namespace, int partitionId, Data key, Object value) {
        addToEventRingbuffer(namespace, partitionId, EVICTED, key, value, null);
    }

    @Override
    public long newestSequence(ObjectNamespace namespace, int partitionId) {
        return getRingbufferOrFail(namespace, partitionId).tailSequence();
    }

    @Override
    public long oldestSequence(ObjectNamespace namespace, int partitionId) {
        return getRingbufferOrFail(namespace, partitionId).headSequence();
    }

    @Override
    public void destroy(ObjectNamespace namespace, int partitionId) {
        final RingbufferService service;
        try {
            service = getRingbufferService();
        } catch (Exception e) {
            logger.fine("Could not retrieve ringbuffer service to destroy event journal " + namespace, e);
            return;
        }
        service.destroyContainer(partitionId, namespace);
    }

    @Override
    public void isAvailableOrNextSequence(ObjectNamespace namespace, int partitionId, long sequence) {
        getRingbufferOrFail(namespace, partitionId).checkBlockableReadSequence(sequence);
    }

    @Override
    public boolean isNextAvailableSequence(ObjectNamespace namespace, int partitionId, long sequence) {
        return getRingbufferOrFail(namespace, partitionId).shouldWait(sequence);
    }

    @Override
    public WaitNotifyKey getWaitNotifyKey(ObjectNamespace namespace, int partitionId) {
        return new RingbufferWaitNotifyKey(namespace, partitionId);
    }

    @Override
    public <T> long readMany(ObjectNamespace namespace, int partitionId, long beginSequence,
                             ReadResultSetImpl<InternalEventJournalMapEvent, T> resultSet) {
        return getRingbufferOrFail(namespace, partitionId).readMany(beginSequence, resultSet);
    }

    @Override
    public void cleanup(ObjectNamespace namespace, int partitionId) {
        getRingbufferOrFail(namespace, partitionId).cleanup();
    }

    @Override
    public boolean hasEventJournal(ObjectNamespace namespace) {
        final EventJournalConfig config = getEventJournalConfig(namespace);
        return config != null && config.isEnabled();
    }

    @Override
    public EventJournalConfig getEventJournalConfig(ObjectNamespace namespace) {
        // when the cluster version is less than 3.9 we act as if the journal is disabled
        // this is because some members might not know how to save journal events
        return nodeEngine.getClusterService().getClusterVersion().isLessThan(Versions.V3_9)
                ? null
                : nodeEngine.getConfig().findMapEventJournalConfig(namespace.getObjectName());
    }

    @Override
    public RingbufferConfig toRingbufferConfig(EventJournalConfig config) {
        final int partitionCount = nodeEngine.getPartitionService().getPartitionCount();
        return new RingbufferConfig()
                .setAsyncBackupCount(0)
                .setBackupCount(0)
                .setInMemoryFormat(InMemoryFormat.OBJECT)
                .setCapacity(config.getCapacity() / partitionCount)
                .setTimeToLiveSeconds(config.getTimeToLiveSeconds());
    }

    private void addToEventRingbuffer(ObjectNamespace namespace, int partitionId, EntryEventType eventType,
                                      Data key, Object oldValue, Object newValue) {
        final RingbufferContainer<InternalEventJournalMapEvent> eventContainer = getRingbufferOrNull(namespace, partitionId);
        if (eventContainer == null) {
            return;
        }
        final InternalEventJournalMapEvent event
                = new InternalEventJournalMapEvent(toData(key), toData(newValue), toData(oldValue), eventType.getType());
        eventContainer.add(event);
        getOperationParker().unpark(eventContainer);
    }

    private Data toData(Object val) {
        return getSerializationService().toData(val, DataType.HEAP);
    }

    private RingbufferContainer<InternalEventJournalMapEvent> getRingbufferOrFail(ObjectNamespace namespace, int partitionId) {
        final RingbufferContainer<InternalEventJournalMapEvent> ringbuffer = getRingbufferOrNull(namespace, partitionId);
        if (ringbuffer == null) {
            throw new IllegalStateException("There is no event journal configured for map with name: "
                    + namespace.getObjectName());
        }
        return ringbuffer;
    }

    private RingbufferContainer<InternalEventJournalMapEvent> getRingbufferOrNull(ObjectNamespace namespace, int partitionId) {
        final RingbufferService service = getRingbufferService();
        final RingbufferConfig ringbufferConfig;
        final RingbufferContainer<InternalEventJournalMapEvent> container = service.getContainerOrNull(partitionId, namespace);
        if (container != null) {
            return container;
        }

        final EventJournalConfig config = getEventJournalConfig(namespace);
        if (config == null || !config.isEnabled()) {
            return null;
        }
        ringbufferConfig = toRingbufferConfig(config);
        return service.getOrCreateContainer(partitionId, namespace, ringbufferConfig);
    }

    private RingbufferService getRingbufferService() {
        return nodeEngine.getService(RingbufferService.SERVICE_NAME);
    }

    private OperationParker getOperationParker() {
        return nodeEngine.getOperationParker();
    }

    private InternalSerializationService getSerializationService() {
        return (InternalSerializationService) nodeEngine.getSerializationService();
    }
}
