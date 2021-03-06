/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The ASF licenses this file to you under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package org.apache.storm.trident.spout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.trident.operation.TridentCollector;
import org.apache.storm.trident.topology.TransactionAttempt;
import org.apache.storm.trident.topology.state.RotatingTransactionalState;
import org.apache.storm.trident.topology.state.TransactionalState;
import org.apache.storm.tuple.Fields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class PartitionedTridentSpoutExecutor implements ITridentSpout<Object> {
    private static final Logger LOG = LoggerFactory.getLogger(PartitionedTridentSpoutExecutor.class);

    IPartitionedTridentSpout<Object, ISpoutPartition, Object> _spout;

    public PartitionedTridentSpoutExecutor(IPartitionedTridentSpout<Object, ISpoutPartition, Object> spout) {
        _spout = spout;
    }

    public IPartitionedTridentSpout<Object, ISpoutPartition, Object> getPartitionedSpout() {
        return _spout;
    }

    @Override
    public ITridentSpout.BatchCoordinator<Object> getCoordinator(String txStateId, Map<String, Object> conf, TopologyContext context) {
        return new Coordinator(conf, context);
    }

    @Override
    public ITridentSpout.Emitter<Object> getEmitter(String txStateId, Map<String, Object> conf, TopologyContext context) {
        return new Emitter(txStateId, conf, context);
    }

    @Override
    public Map<String, Object> getComponentConfiguration() {
        return _spout.getComponentConfiguration();
    }

    @Override
    public Fields getOutputFields() {
        return _spout.getOutputFields();
    }

    static class EmitterPartitionState {
        public RotatingTransactionalState rotatingState;
        public ISpoutPartition partition;

        public EmitterPartitionState(RotatingTransactionalState s, ISpoutPartition p) {
            rotatingState = s;
            partition = p;
        }
    }

    class Coordinator implements ITridentSpout.BatchCoordinator<Object> {
        private IPartitionedTridentSpout.Coordinator<Object> _coordinator;

        public Coordinator(Map<String, Object> conf, TopologyContext context) {
            _coordinator = _spout.getCoordinator(conf, context);
        }

        @Override
        public Object initializeTransaction(long txid, Object prevMetadata, Object currMetadata) {
            LOG.debug("Initialize Transaction. txid = {}, prevMetadata = {}, currMetadata = {}", txid, prevMetadata, currMetadata);

            if (currMetadata != null) {
                return currMetadata;
            } else {
                return _coordinator.getPartitionsForBatch();
            }
        }


        @Override
        public void close() {
            LOG.debug("Closing");
            _coordinator.close();
            LOG.debug("Closed");
        }

        @Override
        public void success(long txid) {
            LOG.debug("Success transaction id " + txid);
        }

        @Override
        public boolean isReady(long txid) {
            boolean ready = _coordinator.isReady(txid);
            LOG.debug("isReady = {} ", ready);
            return ready;
        }
    }

    class Emitter implements ITridentSpout.Emitter<Object> {
        Object _savedCoordinatorMeta = null;
        private IPartitionedTridentSpout.Emitter<Object, ISpoutPartition, Object> _emitter;
        private TransactionalState _state;
        private Map<String, EmitterPartitionState> _partitionStates = new HashMap<>();
        private int _index;
        private int _numTasks;

        public Emitter(String txStateId, Map<String, Object> conf, TopologyContext context) {
            _emitter = _spout.getEmitter(conf, context);
            _state = TransactionalState.newUserState(conf, txStateId);
            _index = context.getThisTaskIndex();
            _numTasks = context.getComponentTasks(context.getThisComponentId()).size();
        }

        @Override
        public void emitBatch(final TransactionAttempt tx, final Object coordinatorMeta, final TridentCollector collector) {
            LOG.debug("Emitting Batch. [transaction = {}], [coordinatorMeta = {}], [collector = {}]", tx, coordinatorMeta, collector);

            if (_savedCoordinatorMeta == null || !_savedCoordinatorMeta.equals(coordinatorMeta)) {
                _partitionStates.clear();
                List<ISpoutPartition> taskPartitions = _emitter.getPartitionsForTask(_index, _numTasks,
                        _emitter.getOrderedPartitions(coordinatorMeta));
                for (ISpoutPartition partition : taskPartitions) {
                    _partitionStates.put(partition.getId(),
                            new EmitterPartitionState(new RotatingTransactionalState(_state, partition.getId()), partition));
                }

                _emitter.refreshPartitions(taskPartitions);
                _savedCoordinatorMeta = coordinatorMeta;
            }
            for (EmitterPartitionState s : _partitionStates.values()) {
                RotatingTransactionalState state = s.rotatingState;
                final ISpoutPartition partition = s.partition;
                Object meta = state.getStateOrCreate(tx.getTransactionId(),
                                                     new RotatingTransactionalState.StateInitializer() {
                                                         @Override
                                                         public Object init(long txid, Object lastState) {
                                                             return _emitter.emitPartitionBatchNew(tx, collector, partition, lastState);
                                                         }
                                                     });
                // it's null if one of:
                //   a) a later transaction batch was emitted before this, so we should skip this batch
                //   b) if didn't exist and was created (in which case the StateInitializer was invoked and
                //      it was emitted
                if (meta != null) {
                    _emitter.emitPartitionBatch(tx, collector, partition, meta);
                }
            }
            LOG.debug("Emitted Batch. [tx = {}], [coordinatorMeta = {}], [collector = {}]", tx, coordinatorMeta, collector);
        }

        @Override
        public void success(TransactionAttempt tx) {
            LOG.debug("Success transaction " + tx);
            for (EmitterPartitionState state : _partitionStates.values()) {
                state.rotatingState.cleanupBefore(tx.getTransactionId());
            }
        }

        @Override
        public void close() {
            LOG.debug("Closing");
            _state.close();
            _emitter.close();
            LOG.debug("Closed");
        }
    }
}
