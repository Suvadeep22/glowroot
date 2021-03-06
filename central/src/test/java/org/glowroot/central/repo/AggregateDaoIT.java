/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.central.repo;

import java.util.List;
import java.util.Map;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.util.Sessions;
import org.glowroot.common.config.CentralStorageConfig;
import org.glowroot.common.config.ImmutableCentralStorageConfig;
import org.glowroot.common.live.ImmutableOverallQuery;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.MutableQuery;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallErrorSummaryCollector.OverallErrorSummary;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector.OverallSummary;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.Result;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector.ErrorSummarySortOrder;
import org.glowroot.common.model.TransactionErrorSummaryCollector.TransactionErrorSummary;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector.SummarySortOrder;
import org.glowroot.common.model.TransactionSummaryCollector.TransactionSummary;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.OldTransactionAggregate;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;
import org.glowroot.wire.api.model.Proto.OptionalInt64;

import static org.assertj.core.api.Assertions.assertThat;

public class AggregateDaoIT {

    private static Cluster cluster;
    private static Session session;
    private static AgentDao agentDao;
    private static AggregateDao aggregateDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = cluster.newSession();
        Sessions.createKeyspaceIfNotExists(session, "glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");
        KeyspaceMetadata keyspace = cluster.getMetadata().getKeyspace("glowroot_unit_tests");

        CentralConfigDao centralConfigDao = new CentralConfigDao(session);
        agentDao = new AgentDao(session);

        UserDao userDao = new UserDao(session, keyspace);
        RoleDao roleDao = new RoleDao(session, keyspace);
        ConfigRepository configRepository =
                new ConfigRepositoryImpl(centralConfigDao, agentDao, userDao, roleDao);
        CentralStorageConfig storageConfig = configRepository.getCentralStorageConfig();
        configRepository.updateCentralStorageConfig(
                ImmutableCentralStorageConfig
                        .copyOf(storageConfig)
                        .withRollupExpirationHours(0, 0, 0, 0),
                storageConfig.version());
        agentDao.setConfigRepository(configRepository);
        centralConfigDao.setConfigRepository(configRepository);
        TransactionTypeDao transactionTypeDao = new TransactionTypeDao(session, configRepository);
        FullQueryTextDao fullQueryTextDao = new FullQueryTextDao(session, configRepository);
        aggregateDao = new AggregateDao(session, agentDao, transactionTypeDao, fullQueryTextDao,
                configRepository, Clock.systemClock());
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldRollup() throws Exception {
        aggregateDao.truncateAll();
        List<Aggregate.SharedQueryText> sharedQueryText = ImmutableList
                .of(Aggregate.SharedQueryText.newBuilder().setFullText("select 1").build());
        aggregateDao.store("one", 60000, createData(), sharedQueryText);
        aggregateDao.store("one", 120000, createData(), sharedQueryText);
        aggregateDao.store("one", 360000, createData(), sharedQueryText);

        // check non-rolled up data
        OverallQuery overallQuery = ImmutableOverallQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();
        TransactionQuery transactionQuery = ImmutableTransactionQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();

        OverallSummaryCollector overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("one", overallQuery, overallSummaryCollector);
        OverallSummary overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        TransactionSummaryCollector transactionSummaryCollector = new TransactionSummaryCollector();
        SummarySortOrder sortOrder = SummarySortOrder.TOTAL_TIME;
        aggregateDao.mergeTransactionSummariesInto("one", overallQuery, sortOrder, 10,
                transactionSummaryCollector);
        Result<TransactionSummary> result = transactionSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        OverallErrorSummaryCollector overallErrorSummaryCollector =
                new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("one", overallQuery,
                overallErrorSummaryCollector);
        OverallErrorSummary overallErrorSummary =
                overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        TransactionErrorSummaryCollector errorSummaryCollector =
                new TransactionErrorSummaryCollector();
        ErrorSummarySortOrder errorSortOrder = ErrorSummarySortOrder.ERROR_COUNT;
        aggregateDao.mergeTransactionErrorSummariesInto("one", overallQuery, errorSortOrder, 10,
                errorSummaryCollector);
        Result<TransactionErrorSummary> errorSummaryResult =
                errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        List<OverviewAggregate> overviewAggregates =
                aggregateDao.readOverviewAggregates("one", transactionQuery);
        assertThat(overviewAggregates).hasSize(2);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(overviewAggregates.get(1).transactionCount()).isEqualTo(3);

        List<PercentileAggregate> percentileAggregates =
                aggregateDao.readPercentileAggregates("one", transactionQuery);
        assertThat(percentileAggregates).hasSize(2);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(percentileAggregates.get(1).transactionCount()).isEqualTo(3);

        List<ThroughputAggregate> throughputAggregates =
                aggregateDao.readThroughputAggregates("one", transactionQuery);
        assertThat(throughputAggregates).hasSize(2);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(throughputAggregates.get(1).transactionCount()).isEqualTo(3);

        QueryCollector queryCollector = new QueryCollector(1000);
        aggregateDao.mergeQueriesInto("one", transactionQuery, queryCollector);
        Map<String, List<MutableQuery>> queries = queryCollector.getSortedQueries();
        assertThat(queries).hasSize(1);
        List<MutableQuery> queriesByType = queries.get("sqlo");
        assertThat(queriesByType).hasSize(1);
        MutableQuery query = queriesByType.get(0);
        assertThat(query.getTruncatedText()).isEqualTo("select 1");
        assertThat(query.getFullTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);

        // rollup
        aggregateDao.rollup("one", null, true);

        // check rolled-up data after rollup
        overallQuery = ImmutableOverallQuery.builder()
                .copyFrom(overallQuery)
                .rollupLevel(1)
                .build();
        transactionQuery = ImmutableTransactionQuery.builder()
                .copyFrom(transactionQuery)
                .rollupLevel(1)
                .build();

        overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("one", overallQuery, overallSummaryCollector);
        overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        transactionSummaryCollector = new TransactionSummaryCollector();
        aggregateDao.mergeTransactionSummariesInto("one", overallQuery, sortOrder, 10,
                transactionSummaryCollector);
        result = transactionSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        overallErrorSummaryCollector = new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("one", overallQuery,
                overallErrorSummaryCollector);
        overallErrorSummary = overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        errorSummaryCollector = new TransactionErrorSummaryCollector();
        aggregateDao.mergeTransactionErrorSummariesInto("one", overallQuery, errorSortOrder, 10,
                errorSummaryCollector);
        errorSummaryResult = errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        overviewAggregates = aggregateDao.readOverviewAggregates("one", transactionQuery);
        assertThat(overviewAggregates).hasSize(1);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(6);

        percentileAggregates = aggregateDao.readPercentileAggregates("one", transactionQuery);
        assertThat(percentileAggregates).hasSize(1);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(6);

        throughputAggregates = aggregateDao.readThroughputAggregates("one", transactionQuery);
        assertThat(throughputAggregates).hasSize(1);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(6);

        queryCollector = new QueryCollector(1000);
        aggregateDao.mergeQueriesInto("one", transactionQuery, queryCollector);
        queries = queryCollector.getSortedQueries();
        assertThat(queries).hasSize(1);
        queriesByType = queries.get("sqlo");
        assertThat(queriesByType).hasSize(1);
        query = queriesByType.get(0);
        assertThat(query.getTruncatedText()).isEqualTo("select 1");
        assertThat(query.getFullTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);
    }

    @Test
    public void shouldRollupFromChildren() throws Exception {

        agentDao.store("one", "the parent", Environment.getDefaultInstance(),
                AgentConfig.getDefaultInstance());

        aggregateDao.truncateAll();
        List<Aggregate.SharedQueryText> sharedQueryText = ImmutableList
                .of(Aggregate.SharedQueryText.newBuilder().setFullText("select 1").build());
        aggregateDao.store("one", 60000, createData(), sharedQueryText);
        aggregateDao.store("one", 120000, createData(), sharedQueryText);
        aggregateDao.store("one", 360000, createData(), sharedQueryText);

        // rollup
        aggregateDao.rollup("the parent", null, false);

        // check level-0 rolled up from children data
        OverallQuery overallQuery = ImmutableOverallQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();
        TransactionQuery transactionQuery = ImmutableTransactionQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();

        OverallSummaryCollector overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("the parent", overallQuery, overallSummaryCollector);
        OverallSummary overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        TransactionSummaryCollector transactionSummaryCollector = new TransactionSummaryCollector();
        SummarySortOrder sortOrder = SummarySortOrder.TOTAL_TIME;
        aggregateDao.mergeTransactionSummariesInto("the parent", overallQuery, sortOrder, 10,
                transactionSummaryCollector);
        Result<TransactionSummary> result = transactionSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        OverallErrorSummaryCollector overallErrorSummaryCollector =
                new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("the parent", overallQuery,
                overallErrorSummaryCollector);
        OverallErrorSummary overallErrorSummary =
                overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        TransactionErrorSummaryCollector errorSummaryCollector =
                new TransactionErrorSummaryCollector();
        ErrorSummarySortOrder errorSortOrder = ErrorSummarySortOrder.ERROR_COUNT;
        aggregateDao.mergeTransactionErrorSummariesInto("the parent", overallQuery, errorSortOrder,
                10, errorSummaryCollector);
        Result<TransactionErrorSummary> errorSummaryResult =
                errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        List<OverviewAggregate> overviewAggregates =
                aggregateDao.readOverviewAggregates("the parent", transactionQuery);
        assertThat(overviewAggregates).hasSize(2);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(overviewAggregates.get(1).transactionCount()).isEqualTo(3);

        List<PercentileAggregate> percentileAggregates =
                aggregateDao.readPercentileAggregates("the parent", transactionQuery);
        assertThat(percentileAggregates).hasSize(2);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(percentileAggregates.get(1).transactionCount()).isEqualTo(3);

        List<ThroughputAggregate> throughputAggregates =
                aggregateDao.readThroughputAggregates("the parent", transactionQuery);
        assertThat(throughputAggregates).hasSize(2);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(throughputAggregates.get(1).transactionCount()).isEqualTo(3);

        QueryCollector queryCollector = new QueryCollector(1000);
        aggregateDao.mergeQueriesInto("the parent", transactionQuery, queryCollector);
        Map<String, List<MutableQuery>> queries = queryCollector.getSortedQueries();
        assertThat(queries).hasSize(1);
        List<MutableQuery> queriesByType = queries.get("sqlo");
        assertThat(queriesByType).hasSize(1);
        MutableQuery query = queriesByType.get(0);
        assertThat(query.getTruncatedText()).isEqualTo("select 1");
        assertThat(query.getFullTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);

        // check rolled-up data after rollup
        overallQuery = ImmutableOverallQuery.builder()
                .copyFrom(overallQuery)
                .rollupLevel(1)
                .build();
        transactionQuery = ImmutableTransactionQuery.builder()
                .copyFrom(transactionQuery)
                .rollupLevel(1)
                .build();

        overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("the parent", overallQuery, overallSummaryCollector);
        overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        transactionSummaryCollector = new TransactionSummaryCollector();
        aggregateDao.mergeTransactionSummariesInto("the parent", overallQuery, sortOrder, 10,
                transactionSummaryCollector);
        result = transactionSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        overallErrorSummaryCollector = new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("the parent", overallQuery,
                overallErrorSummaryCollector);
        overallErrorSummary = overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        errorSummaryCollector = new TransactionErrorSummaryCollector();
        aggregateDao.mergeTransactionErrorSummariesInto("the parent", overallQuery, errorSortOrder,
                10, errorSummaryCollector);
        errorSummaryResult = errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        overviewAggregates = aggregateDao.readOverviewAggregates("the parent", transactionQuery);
        assertThat(overviewAggregates).hasSize(1);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(6);

        percentileAggregates =
                aggregateDao.readPercentileAggregates("the parent", transactionQuery);
        assertThat(percentileAggregates).hasSize(1);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(6);

        throughputAggregates =
                aggregateDao.readThroughputAggregates("the parent", transactionQuery);
        assertThat(throughputAggregates).hasSize(1);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(6);

        queryCollector = new QueryCollector(1000);
        aggregateDao.mergeQueriesInto("the parent", transactionQuery, queryCollector);
        queries = queryCollector.getSortedQueries();
        assertThat(queries).hasSize(1);
        queriesByType = queries.get("sqlo");
        assertThat(queriesByType).hasSize(1);
        query = queriesByType.get(0);
        assertThat(query.getTruncatedText()).isEqualTo("select 1");
        assertThat(query.getFullTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);
    }

    @Test
    public void shouldRollupFromGrandChildren() throws Exception {

        agentDao.store("one", "the gp/the parent", Environment.getDefaultInstance(),
                AgentConfig.getDefaultInstance());

        aggregateDao.truncateAll();
        List<Aggregate.SharedQueryText> sharedQueryText = ImmutableList
                .of(Aggregate.SharedQueryText.newBuilder().setFullText("select 1").build());
        aggregateDao.store("one", 60000, createData(), sharedQueryText);
        aggregateDao.store("one", 120000, createData(), sharedQueryText);
        aggregateDao.store("one", 360000, createData(), sharedQueryText);

        // rollup
        aggregateDao.rollup("the gp/the parent", "the gp", false);
        aggregateDao.rollup("the gp", null, false);

        // check level-0 rolled up from children data
        OverallQuery overallQuery = ImmutableOverallQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();
        TransactionQuery transactionQuery = ImmutableTransactionQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();

        OverallSummaryCollector overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("the gp", overallQuery, overallSummaryCollector);
        OverallSummary overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        TransactionSummaryCollector transactionSummaryCollector = new TransactionSummaryCollector();
        SummarySortOrder sortOrder = SummarySortOrder.TOTAL_TIME;
        aggregateDao.mergeTransactionSummariesInto("the gp", overallQuery, sortOrder, 10,
                transactionSummaryCollector);
        Result<TransactionSummary> result = transactionSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        OverallErrorSummaryCollector overallErrorSummaryCollector =
                new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("the gp", overallQuery,
                overallErrorSummaryCollector);
        OverallErrorSummary overallErrorSummary =
                overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        TransactionErrorSummaryCollector errorSummaryCollector =
                new TransactionErrorSummaryCollector();
        ErrorSummarySortOrder errorSortOrder = ErrorSummarySortOrder.ERROR_COUNT;
        aggregateDao.mergeTransactionErrorSummariesInto("the gp", overallQuery, errorSortOrder,
                10, errorSummaryCollector);
        Result<TransactionErrorSummary> errorSummaryResult =
                errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        List<OverviewAggregate> overviewAggregates =
                aggregateDao.readOverviewAggregates("the gp", transactionQuery);
        assertThat(overviewAggregates).hasSize(2);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(overviewAggregates.get(1).transactionCount()).isEqualTo(3);

        List<PercentileAggregate> percentileAggregates =
                aggregateDao.readPercentileAggregates("the gp", transactionQuery);
        assertThat(percentileAggregates).hasSize(2);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(percentileAggregates.get(1).transactionCount()).isEqualTo(3);

        List<ThroughputAggregate> throughputAggregates =
                aggregateDao.readThroughputAggregates("the gp", transactionQuery);
        assertThat(throughputAggregates).hasSize(2);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(throughputAggregates.get(1).transactionCount()).isEqualTo(3);

        QueryCollector queryCollector = new QueryCollector(1000);
        aggregateDao.mergeQueriesInto("the gp", transactionQuery, queryCollector);
        Map<String, List<MutableQuery>> queries = queryCollector.getSortedQueries();
        assertThat(queries).hasSize(1);
        List<MutableQuery> queriesByType = queries.get("sqlo");
        assertThat(queriesByType).hasSize(1);
        MutableQuery query = queriesByType.get(0);
        assertThat(query.getTruncatedText()).isEqualTo("select 1");
        assertThat(query.getFullTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);

        // check rolled-up data after rollup
        overallQuery = ImmutableOverallQuery.builder()
                .copyFrom(overallQuery)
                .rollupLevel(1)
                .build();
        transactionQuery = ImmutableTransactionQuery.builder()
                .copyFrom(transactionQuery)
                .rollupLevel(1)
                .build();

        overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("the gp", overallQuery, overallSummaryCollector);
        overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        transactionSummaryCollector = new TransactionSummaryCollector();
        aggregateDao.mergeTransactionSummariesInto("the gp", overallQuery, sortOrder, 10,
                transactionSummaryCollector);
        result = transactionSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        overallErrorSummaryCollector = new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("the gp", overallQuery,
                overallErrorSummaryCollector);
        overallErrorSummary = overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        errorSummaryCollector = new TransactionErrorSummaryCollector();
        aggregateDao.mergeTransactionErrorSummariesInto("the gp", overallQuery, errorSortOrder,
                10, errorSummaryCollector);
        errorSummaryResult = errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        overviewAggregates = aggregateDao.readOverviewAggregates("the gp", transactionQuery);
        assertThat(overviewAggregates).hasSize(1);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(6);

        percentileAggregates =
                aggregateDao.readPercentileAggregates("the gp", transactionQuery);
        assertThat(percentileAggregates).hasSize(1);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(6);

        throughputAggregates =
                aggregateDao.readThroughputAggregates("the gp", transactionQuery);
        assertThat(throughputAggregates).hasSize(1);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(6);

        queryCollector = new QueryCollector(1000);
        aggregateDao.mergeQueriesInto("the gp", transactionQuery, queryCollector);
        queries = queryCollector.getSortedQueries();
        assertThat(queries).hasSize(1);
        queriesByType = queries.get("sqlo");
        assertThat(queriesByType).hasSize(1);
        query = queriesByType.get(0);
        assertThat(query.getTruncatedText()).isEqualTo("select 1");
        assertThat(query.getFullTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);
    }

    private static List<OldAggregatesByType> createData() {
        List<OldAggregatesByType> aggregatesByType = Lists.newArrayList();
        aggregatesByType.add(OldAggregatesByType.newBuilder()
                .setTransactionType("tt0")
                .setOverallAggregate(createOverallAggregate())
                .addTransactionAggregate(createTransactionAggregate1())
                .addTransactionAggregate(createTransactionAggregate2())
                .build());
        aggregatesByType.add(OldAggregatesByType.newBuilder()
                .setTransactionType("tt1")
                .setOverallAggregate(createOverallAggregate())
                .addTransactionAggregate(createTransactionAggregate1())
                .addTransactionAggregate(createTransactionAggregate2())
                .build());
        return aggregatesByType;
    }

    private static Aggregate createOverallAggregate() {
        return Aggregate.newBuilder()
                .setTotalDurationNanos(3579)
                .setTransactionCount(3)
                .setErrorCount(1)
                .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                        .setName("abc")
                        .setTotalNanos(333)
                        .setCount(3))
                .addAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                        .setName("xyz")
                        .setTotalNanos(666)
                        .setCount(3))
                .addAsyncTimer(Aggregate.Timer.newBuilder()
                        .setName("mnm")
                        .setTotalNanos(999)
                        .setCount(3))
                .addQueriesByType(Aggregate.QueriesByType.newBuilder()
                        .setType("sqlo")
                        .addQuery(Aggregate.Query.newBuilder()
                                .setSharedQueryTextIndex(0)
                                .setTotalDurationNanos(7)
                                .setTotalRows(OptionalInt64.newBuilder().setValue(5))
                                .setExecutionCount(2)))
                .build();
    }

    private static OldTransactionAggregate createTransactionAggregate1() {
        return OldTransactionAggregate.newBuilder()
                .setTransactionName("tn1")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(1234)
                        .setTransactionCount(1)
                        .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("abc")
                                .setTotalNanos(111)
                                .setCount(1))
                        .addAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("xyz")
                                .setTotalNanos(222)
                                .setCount(1))
                        .addAsyncTimer(Aggregate.Timer.newBuilder()
                                .setName("mnm")
                                .setTotalNanos(333)
                                .setCount(1))
                        .addQueriesByType(Aggregate.QueriesByType.newBuilder()
                                .setType("sqlo")
                                .addQuery(Aggregate.Query.newBuilder()
                                        .setSharedQueryTextIndex(0)
                                        .setTotalDurationNanos(7)
                                        .setTotalRows(OptionalInt64.newBuilder().setValue(5))
                                        .setExecutionCount(2))))
                .build();
    }

    private static OldTransactionAggregate createTransactionAggregate2() {
        return OldTransactionAggregate.newBuilder()
                .setTransactionName("tn2")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(2345)
                        .setTransactionCount(2)
                        .setErrorCount(1)
                        .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("abc")
                                .setTotalNanos(222)
                                .setCount(2))
                        .addAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("xyz")
                                .setTotalNanos(444)
                                .setCount(2))
                        .addAsyncTimer(Aggregate.Timer.newBuilder()
                                .setName("mnm")
                                .setTotalNanos(666)
                                .setCount(2)))
                .build();
    }
}
