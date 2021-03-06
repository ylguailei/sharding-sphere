/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.core.jdbc.core.statement;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterators;
import io.shardingsphere.core.executor.type.batch.BatchPreparedStatementExecutor;
import io.shardingsphere.core.executor.type.batch.BatchPreparedStatementUnit;
import io.shardingsphere.core.executor.type.prepared.PreparedStatementExecutor;
import io.shardingsphere.core.executor.type.prepared.PreparedStatementUnit;
import io.shardingsphere.core.jdbc.adapter.AbstractShardingPreparedStatementAdapter;
import io.shardingsphere.core.jdbc.core.ShardingContext;
import io.shardingsphere.core.jdbc.core.connection.ShardingConnection;
import io.shardingsphere.core.jdbc.core.resultset.GeneratedKeysResultSet;
import io.shardingsphere.core.jdbc.core.resultset.ShardingResultSet;
import io.shardingsphere.core.jdbc.metadata.dialect.JDBCShardingRefreshHandler;
import io.shardingsphere.core.merger.JDBCQueryResult;
import io.shardingsphere.core.merger.MergeEngine;
import io.shardingsphere.core.merger.MergeEngineFactory;
import io.shardingsphere.core.merger.MergedResult;
import io.shardingsphere.core.merger.QueryResult;
import io.shardingsphere.core.merger.event.EventMergeType;
import io.shardingsphere.core.merger.event.ResultSetMergeEvent;
import io.shardingsphere.core.parsing.parser.sql.dal.DALStatement;
import io.shardingsphere.core.parsing.parser.sql.dml.insert.InsertStatement;
import io.shardingsphere.core.parsing.parser.sql.dql.DQLStatement;
import io.shardingsphere.core.parsing.parser.sql.dql.select.SelectStatement;
import io.shardingsphere.core.routing.PreparedStatementRoutingEngine;
import io.shardingsphere.core.routing.SQLExecutionUnit;
import io.shardingsphere.core.routing.SQLRouteResult;
import io.shardingsphere.core.routing.event.EventRoutingType;
import io.shardingsphere.core.routing.event.SqlRoutingEvent;
import io.shardingsphere.core.routing.router.sharding.GeneratedKey;
import io.shardingsphere.core.util.EventBusInstance;
import lombok.AccessLevel;
import lombok.Getter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * PreparedStatement that support sharding.
 * 
 * @author zhangliang
 * @author caohao
 * @author maxiaoguang
 * @author panjuan
 */
@Getter
public final class ShardingPreparedStatement extends AbstractShardingPreparedStatementAdapter {
    
    private final ShardingConnection connection;
    
    private final int resultSetType;
    
    private final int resultSetConcurrency;
    
    private final int resultSetHoldability;
    
    private final PreparedStatementRoutingEngine routingEngine;
    
    private final List<BatchPreparedStatementUnit> batchStatementUnits = new LinkedList<>();
    
    private final Collection<PreparedStatement> routedStatements = new LinkedList<>();

    private final String sql;

    private int batchCount;
    
    @Getter(AccessLevel.NONE)
    private boolean returnGeneratedKeys;
    
    @Getter(AccessLevel.NONE)
    private SQLRouteResult routeResult;
    
    @Getter(AccessLevel.NONE)
    private ResultSet currentResultSet;
    
    public ShardingPreparedStatement(final ShardingConnection connection, final String sql) {
        this(connection, sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    public ShardingPreparedStatement(final ShardingConnection connection, final String sql, final int resultSetType, final int resultSetConcurrency) {
        this(connection, sql, resultSetType, resultSetConcurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    public ShardingPreparedStatement(final ShardingConnection connection, final String sql, final int autoGeneratedKeys) {
        this(connection, sql);
        if (Statement.RETURN_GENERATED_KEYS == autoGeneratedKeys) {
            returnGeneratedKeys = true;
        }
    }
    
    public ShardingPreparedStatement(final ShardingConnection connection, final String sql, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability) {
        this.connection = connection;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.resultSetHoldability = resultSetHoldability;
        this.sql = sql;
        ShardingContext shardingContext = connection.getShardingContext();
        routingEngine = new PreparedStatementRoutingEngine(
                sql, shardingContext.getShardingRule(), shardingContext.getShardingMetaData(), shardingContext.getDatabaseType(), shardingContext.isShowSQL());
    }
    
    @Override
    public ResultSet executeQuery() throws SQLException {
        ResultSet result;
        try {
            Collection<PreparedStatementUnit> preparedStatementUnits = route();
            List<ResultSet> resultSets = new PreparedStatementExecutor(
                    getConnection().getShardingContext().getExecutorEngine(), routeResult.getSqlStatement().getType(), preparedStatementUnits).executeQuery();
            List<QueryResult> queryResults = new ArrayList<>(resultSets.size());
            for (ResultSet each : resultSets) {
                queryResults.add(new JDBCQueryResult(each));
            }
            MergeEngine mergeEngine = MergeEngineFactory.newInstance(connection.getShardingContext().getShardingRule(),
                    queryResults, routeResult.getSqlStatement(), connection.getShardingContext().getShardingMetaData());
            result = new ShardingResultSet(resultSets, merge(mergeEngine), this);
        } finally {
            clearBatch();
        }
        currentResultSet = result;
        return result;
    }
    
    @Override
    public int executeUpdate() throws SQLException {
        try {
            Collection<PreparedStatementUnit> preparedStatementUnits = route();
            return new PreparedStatementExecutor(
                    getConnection().getShardingContext().getExecutorEngine(), routeResult.getSqlStatement().getType(), preparedStatementUnits).executeUpdate();
        } finally {
            if (routeResult != null && connection != null) {
                JDBCShardingRefreshHandler.build(routeResult, connection).execute();
            }
            clearBatch();
        }
    }
    
    @Override
    public boolean execute() throws SQLException {
        try {
            Collection<PreparedStatementUnit> preparedStatementUnits = route();
            return new PreparedStatementExecutor(
                    getConnection().getShardingContext().getExecutorEngine(), routeResult.getSqlStatement().getType(), preparedStatementUnits).execute();
        } finally {
            if (routeResult != null && connection != null) {
                JDBCShardingRefreshHandler.build(routeResult, connection).execute();
            }
            clearBatch();
        }
    }
    
    private Collection<PreparedStatementUnit> route() throws SQLException {
        Collection<PreparedStatementUnit> result = new LinkedList<>();
        sqlRoute();
        for (SQLExecutionUnit each : routeResult.getExecutionUnits()) {
            PreparedStatement preparedStatement = generatePreparedStatement(each);
            routedStatements.add(preparedStatement);
            replaySetParameter(preparedStatement, each.getSqlUnit().getParameterSets().get(0));
            result.add(new PreparedStatementUnit(each, preparedStatement));
        }
        return result;
    }
    
    private PreparedStatement generatePreparedStatement(final SQLExecutionUnit sqlExecutionUnit) throws SQLException {
        Connection connection = getConnection().getConnection(sqlExecutionUnit.getDataSource());
        return returnGeneratedKeys ? connection.prepareStatement(sqlExecutionUnit.getSqlUnit().getSql(), Statement.RETURN_GENERATED_KEYS)
                : connection.prepareStatement(sqlExecutionUnit.getSqlUnit().getSql(), resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    private void sqlRoute() {
        SqlRoutingEvent event = new SqlRoutingEvent(sql);
        EventBusInstance.getInstance().post(event);
        try {
            routeResult = routingEngine.route(getParameters());
            // CHECKSTYLE:OFF
        } catch (final Exception ex) {
            // CHECKSTYLE:ON
            event.setException(ex);
            event.setEventRoutingType(EventRoutingType.ROUTE_FAILURE);
            EventBusInstance.getInstance().post(event);
            throw ex;
        }
        event.setEventRoutingType(EventRoutingType.ROUTE_SUCCESS);
        EventBusInstance.getInstance().post(event);
    }

    @Override
    public void clearBatch() {
        currentResultSet = null;
        clearParameters();
        batchStatementUnits.clear();
        batchCount = 0;
    }
    
    @Override
    public void addBatch() throws SQLException {
        try {
            for (BatchPreparedStatementUnit each : routeBatch()) {
                each.getStatement().addBatch();
                each.mapAddBatchCount(batchCount);
            }
            batchCount++;
        } finally {
            currentResultSet = null;
            clearParameters();
        }
    }
    
    private List<BatchPreparedStatementUnit> routeBatch() throws SQLException {
        List<BatchPreparedStatementUnit> result = new ArrayList<>();
        sqlRoute();
        for (SQLExecutionUnit each : routeResult.getExecutionUnits()) {
            BatchPreparedStatementUnit batchStatementUnit = getPreparedBatchStatement(each);
            replaySetParameter(batchStatementUnit.getStatement(), each.getSqlUnit().getParameterSets().get(0));
            result.add(batchStatementUnit);
        }
        return result;
    }
    
    private BatchPreparedStatementUnit getPreparedBatchStatement(final SQLExecutionUnit sqlExecutionUnit) throws SQLException {
        Optional<BatchPreparedStatementUnit> preparedBatchStatement = Iterators.tryFind(batchStatementUnits.iterator(), new Predicate<BatchPreparedStatementUnit>() {
            
            @Override
            public boolean apply(final BatchPreparedStatementUnit input) {
                return Objects.equals(input.getSqlExecutionUnit(), sqlExecutionUnit);
            }
        });
        if (preparedBatchStatement.isPresent()) {
            preparedBatchStatement.get().getSqlExecutionUnit().getSqlUnit().getParameterSets().add(sqlExecutionUnit.getSqlUnit().getParameterSets().get(0));
            return preparedBatchStatement.get();
        }
        BatchPreparedStatementUnit result = new BatchPreparedStatementUnit(sqlExecutionUnit, generatePreparedStatement(sqlExecutionUnit));
        batchStatementUnits.add(result);
        return result;
    }
    
    @Override
    public int[] executeBatch() throws SQLException {
        try {
            return new BatchPreparedStatementExecutor(getConnection().getShardingContext().getExecutorEngine(),
                    getConnection().getShardingContext().getDatabaseType(), routeResult.getSqlStatement().getType(), batchStatementUnits, batchCount).executeBatch();
        } finally {
            clearBatch();
        }
    }
    
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        Optional<GeneratedKey> generatedKey = getGeneratedKey();
        if (returnGeneratedKeys && generatedKey.isPresent()) {
            return new GeneratedKeysResultSet(routeResult.getGeneratedKey().getGeneratedKeys().iterator(), generatedKey.get().getColumn().getName(), this);
        }
        if (1 == routedStatements.size()) {
            return routedStatements.iterator().next().getGeneratedKeys();
        }
        return new GeneratedKeysResultSet();
    }
    
    private Optional<GeneratedKey> getGeneratedKey() {
        if (null != routeResult && routeResult.getSqlStatement() instanceof InsertStatement) {
            return Optional.fromNullable(routeResult.getGeneratedKey());
        }
        return Optional.absent();
    }
    
    @Override
    public ResultSet getResultSet() throws SQLException {
        if (null != currentResultSet) {
            return currentResultSet;
        }
        if (1 == routedStatements.size() && routeResult.getSqlStatement() instanceof DQLStatement) {
            currentResultSet = routedStatements.iterator().next().getResultSet();
            return currentResultSet;
        }
        List<ResultSet> resultSets = new ArrayList<>(routedStatements.size());
        List<QueryResult> queryResults = new ArrayList<>(routedStatements.size());
        for (PreparedStatement each : routedStatements) {
            ResultSet resultSet = each.getResultSet();
            resultSets.add(resultSet);
            queryResults.add(new JDBCQueryResult(resultSet));
        }
        if (routeResult.getSqlStatement() instanceof SelectStatement || routeResult.getSqlStatement() instanceof DALStatement) {
            MergeEngine mergeEngine = MergeEngineFactory.newInstance(connection.getShardingContext().getShardingRule(), queryResults,
                    routeResult.getSqlStatement(), connection.getShardingContext().getShardingMetaData());
            currentResultSet = new ShardingResultSet(resultSets, merge(mergeEngine), this);
        }
        return currentResultSet;
    }
    
    private MergedResult merge(final MergeEngine mergeEngine) throws SQLException {
        ResultSetMergeEvent event = new ResultSetMergeEvent();
        try {
            EventBusInstance.getInstance().post(event);
            MergedResult result = mergeEngine.merge();
            event.setEventMergeType(EventMergeType.MERGE_SUCCESS);
            EventBusInstance.getInstance().post(event);
            return result;
            // CHECKSTYLE:OFF
        } catch (final Exception ex) {
            // CHECKSTYLE:ON
            event.setException(ex);
            event.setEventMergeType(EventMergeType.MERGE_FAILURE);
            EventBusInstance.getInstance().post(event);
            throw ex;
        }
    }
}
