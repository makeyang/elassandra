/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.shard;

import org.elassandra.cluster.InternalCassandraClusterService;
import org.elassandra.index.search.TokenRangesSearcherWrapper;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.multibindings.Multibinder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.engine.EngineFactory;
import org.elasticsearch.index.engine.IndexSearcherWrapper;
import org.elasticsearch.index.engine.IndexSearcherWrappingService;
import org.elasticsearch.index.engine.InternalEngineFactory;
import org.elasticsearch.index.engine.VersionLessInternalEngineFactory;
import org.elasticsearch.index.percolator.stats.ShardPercolateService;
import org.elasticsearch.index.termvectors.ShardTermVectorsService;
import org.elasticsearch.index.translog.TranslogService;

/**
 * The {@code IndexShardModule} module is responsible for binding the correct
 * shard id, index shard, engine factory, and warming service for a newly
 * created shard.
 */
public class IndexShardModule extends AbstractModule {

    private final ShardId shardId;
    private final Settings settings;
    private final boolean primary;

    // pkg private so tests can mock
    Class<? extends EngineFactory> engineFactoryImpl = InternalEngineFactory.class;
    Class<? extends IndexSearcherWrapper> indexSearchWrapper = TokenRangesSearcherWrapper.class;
    
    public IndexShardModule(ShardId shardId, boolean primary, Settings settings) {
        this.settings = settings;
        this.shardId = shardId;
        this.primary = primary;
        if (settings.get("index.translog.type") != null) {
            throw new IllegalStateException("a custom translog type is no longer supported. got [" + settings.get("index.translog.type") + "]");
        }
        if (settings.get("index.search.wrapper") != null) {
            try {
                indexSearchWrapper = (Class<? extends IndexSearcherWrapper>) Class.forName(settings.get("index.search.wrapper"));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("index.search.wrapper ["+ settings.get("index.search.wrapper") +"] class not found", e);
            }
        }
    }

    /** Return true if a shadow engine should be used */
    protected boolean useShadowEngine() {
        return primary == false && IndexMetaData.isIndexUsingShadowReplicas(settings);
    }
    
    protected boolean useVersionLessEngine() {
        return IndexMetaData.isIndexUsingVersionLessEngine(settings);
    }

    @Override
    protected void configure() {
        bind(ShardId.class).toInstance(shardId);
        if (useShadowEngine()) {
            bind(IndexShard.class).to(ShadowIndexShard.class).asEagerSingleton();
        } else {
            bind(IndexShard.class).asEagerSingleton();
            bind(TranslogService.class).asEagerSingleton();

            if (settings.getAsBoolean(IndexMetaData.SETTING_TOKEN_RANGES_BITSET_CACHE, Boolean.getBoolean(InternalCassandraClusterService.SETTING_SYSTEM_TOKEN_RANGES_BITSET_CACHE))) {
                Multibinder wrapperMultibinder = Multibinder.newSetBinder(binder(), IndexSearcherWrapper.class);
                wrapperMultibinder.addBinding().to(indexSearchWrapper);
                bind(indexSearchWrapper).asEagerSingleton();
            }
        }

        if (useVersionLessEngine()) {
            bind(EngineFactory.class).to(VersionLessInternalEngineFactory.class);
        } else {
            bind(EngineFactory.class).to(engineFactoryImpl);
        }
        
        bind(StoreRecoveryService.class).asEagerSingleton();
        bind(ShardPercolateService.class).asEagerSingleton();
        bind(ShardTermVectorsService.class).asEagerSingleton();
        bind(IndexSearcherWrappingService.class).asEagerSingleton();
        // this injects an empty set in IndexSearcherWrappingService, otherwise guice can't construct IndexSearcherWrappingService
        Multibinder<IndexSearcherWrapper> multibinder
                = Multibinder.newSetBinder(binder(), IndexSearcherWrapper.class);
    }


}