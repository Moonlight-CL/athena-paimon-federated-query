/*-
 * #%L
 * athena-paimon
 * %%
 * Copyright (C) 2019 - 2024 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package me.aws.athena.connector.paimon;

import com.amazonaws.athena.connector.lambda.QueryStatusChecker;
import com.amazonaws.athena.connector.lambda.data.Block;
import com.amazonaws.athena.connector.lambda.data.BlockAllocator;
import com.amazonaws.athena.connector.lambda.data.BlockWriter;
import com.amazonaws.athena.connector.lambda.data.SchemaBuilder;
import com.amazonaws.athena.connector.lambda.domain.Split;
import com.amazonaws.athena.connector.lambda.domain.TableName;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.domain.predicate.ValueSet;
import com.amazonaws.athena.connector.lambda.domain.spill.SpillLocation;
import com.amazonaws.athena.connector.lambda.handlers.MetadataHandler;
import com.amazonaws.athena.connector.lambda.metadata.*;
import com.amazonaws.athena.connector.lambda.metadata.optimizations.DataSourceOptimizations;
import com.amazonaws.athena.connector.lambda.metadata.optimizations.OptimizationSubType;
import com.amazonaws.athena.connector.lambda.metadata.optimizations.pushdown.FilterPushdownSubType;
import com.amazonaws.athena.connector.lambda.metadata.optimizations.pushdown.LimitPushdownSubType;
import com.google.common.collect.ImmutableMap;
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.table.Table;
import org.apache.paimon.types.RowType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class PaimonMetadataHandler extends MetadataHandler{

    private static final Logger logger = LoggerFactory.getLogger(PaimonMetadataHandler.class);
    private static final String PARTITION_ID_COL = "partitionId";
    private static final int MAX_SPLITS_PER_REQUEST = 1000_000;
    private Catalog catalog;

    public PaimonMetadataHandler(Map<String, String> configOptions, Catalog catalog) {
        super(Constants.SOURCE_TYPE, configOptions);
        Objects.requireNonNull(catalog);
        this.catalog = catalog;
    }

    public ListSchemasResponse doListSchemaNames(BlockAllocator allocator, ListSchemasRequest request) throws Exception {
        List<String> dbs = this.catalog.listDatabases();
        logger.info("doListSchemaNames: catalogName:{}, dbs:{}", request.getCatalogName(), dbs);
        return new ListSchemasResponse(request.getCatalogName(), dbs);
    }

    public ListTablesResponse doListTables(BlockAllocator allocator, ListTablesRequest request) throws Exception {
        String catalogName = request.getCatalogName();
        String schemaName = request.getSchemaName();
        List<TableName> tables = this.catalog.listTables(schemaName)
                .stream()
                .map(t -> new TableName(schemaName, t))
                .collect(Collectors.toList());
        logger.info("doListTables: catalog:{}, schema:{}, tables:{}", catalogName, schemaName, tables);
        return new ListTablesResponse(catalogName, tables, null);
    }

    public GetTableResponse doGetTable(BlockAllocator allocator, GetTableRequest request) throws Exception {
        String catalogName = request.getCatalogName();
        TableName tableName = request.getTableName();
        Identifier tablePath = new Identifier(tableName.getSchemaName(), tableName.getTableName());

        SchemaBuilder schemaBuilder = SchemaBuilder.newBuilder();
        Table table = catalog.getTable(tablePath);
        List<String> partitionKeys = table.partitionKeys();

        table.rowType().getFields().forEach(f -> {
            schemaBuilder.addField(f.name(), AthenaTypeUtils.fromPaimonType(f.type()));
        });

        Schema tableSchema = schemaBuilder.build();

        logger.info("doGetTable: catalog:{}, schema:{}, table:{}, schema: {}", catalogName,
                tableName.getSchemaName(), tableName, tableSchema);
        Set<String> partitionKeySet = new HashSet<>(partitionKeys);
        return new GetTableResponse(catalogName, tableName, tableSchema, partitionKeySet);
    }

    public void getPartitions(BlockWriter blockWriter, GetTableLayoutRequest request,
                              QueryStatusChecker queryStatusChecker) throws Exception {
        String queryId = request.getQueryId();
        String catalogName = request.getCatalogName();
        TableName tableName = request.getTableName();

        Identifier tablePath = new Identifier(tableName.getSchemaName(), tableName.getTableName());
        Table paimonTable = catalog.getTable(tablePath);
        List<String> partitionKeys = paimonTable.partitionKeys();

        if (null == partitionKeys || partitionKeys.isEmpty()) {
            return;
        }

        Constraints constraints = request.getConstraints();
        Map<String, ValueSet> summary = constraints.getSummary();

        Predicate predication = PushdownUtils.partitionPushdownPredicate(summary, paimonTable);
        List<BinaryRow> binaryRows = new ArrayList<>();
        if (predication == null) {
            // return all partitions
            binaryRows = paimonTable.newReadBuilder().newScan().listPartitions();
        } else {
            // return filtered partitions
            binaryRows = paimonTable.newReadBuilder().withFilter(predication).newScan().listPartitions();
        }

        PushdownUtils.writeFilteredPartitionData(blockWriter, paimonTable, binaryRows);
        logger.info("getPartitions: catalog:{}, table:{}, queryId:{}, partition size: {}", catalogName,
                tableName.getTableName(), queryId, binaryRows.size());


    }

    public GetSplitsResponse doGetSplits(BlockAllocator allocator, GetSplitsRequest request) throws Exception {
        String queryId = request.getQueryId();

        TableName tableName = request.getTableName();
        Identifier tablePath = new Identifier(tableName.getSchemaName(), tableName.getTableName());
        Table paimonTable = catalog.getTable(tablePath);
        RowType rowType = paimonTable.rowType();
        List<String> parKeys = paimonTable.partitionKeys();

        int partitionContd = decodeContinuationToken(request);
        Block partitions = request.getPartitions();

        logger.info("doGetSplits->params: catalog:{}, table:{}, queryId:{}, parkeys: {} ",
                request.getCatalogName(), request.getTableName().getTableName(), queryId, parKeys);

        Set<Split> result = new HashSet<>();

        // every partition as a split with no consideration of Paimon table bucket
        for (int curPartition = partitionContd; curPartition < partitions.getRowCount(); curPartition++) {

            if ((parKeys == null || parKeys.isEmpty()) ) {

                FieldReader locationReader = partitions.getFieldReader(PARTITION_ID_COL);
                locationReader.setPosition(curPartition);

                SpillLocation spillLocation = makeSpillLocation(request);
                Split.Builder splitBuilder = Split.newBuilder(spillLocation, makeEncryptionKey())
                        .add(PARTITION_ID_COL, String.valueOf(locationReader.readInteger()));

                result.add(splitBuilder.build());

            } else {
                SpillLocation spillLocation = makeSpillLocation(request);
                Split.Builder splitBuilder = Split.newBuilder(spillLocation, makeEncryptionKey());

                for (String parKey : parKeys) {

                    splitBuilder.add(parKey, ValueExtractUtils.extractValueFromBlock(partitions, parKey,
                            curPartition, rowType.getTypeAt(rowType.getFieldIndex(parKey))).toString());
                }

                result.add(splitBuilder.build());
            }

            if (result.size() >= MAX_SPLITS_PER_REQUEST) {
                return new GetSplitsResponse(request.getCatalogName(), result,
                        encodeContinuationToken(curPartition + 1));
            }
        }
        logger.info("doGetSplits: catalog:{}, table:{}, queryId:{}, split size: {}",
                request.getCatalogName(), request.getTableName().getTableName(), queryId, result.size());
        return new GetSplitsResponse(request.getCatalogName(), result, null);
    }

    @Override
    public GetDataSourceCapabilitiesResponse doGetDataSourceCapabilities(BlockAllocator allocator,
                                                                         GetDataSourceCapabilitiesRequest request) {

        ImmutableMap.Builder<String, List<OptimizationSubType>> capabilities = ImmutableMap.builder();
        // add filter push down support
        capabilities.put(DataSourceOptimizations.SUPPORTS_FILTER_PUSHDOWN.withSupportedSubTypes(
                FilterPushdownSubType.SORTED_RANGE_SET, FilterPushdownSubType.EQUATABLE_VALUE_SET
        ));
        // add limit push down support
        capabilities.put(DataSourceOptimizations.SUPPORTS_LIMIT_PUSHDOWN.withSupportedSubTypes(LimitPushdownSubType.INTEGER_CONSTANT));
        // add complex expression push down support
//        capabilities.put(DataSourceOptimizations.SUPPORTS_COMPLEX_EXPRESSION_PUSHDOWN.withSupportedSubTypes(
//                ComplexExpressionPushdownSubType.SUPPORTED_FUNCTION_EXPRESSION_TYPES
//                        .withSubTypeProperties(
//                                Arrays.stream(StandardFunctions.values())
//                                .map(standardFunctions -> standardFunctions.getFunctionName().getFunctionName())
//                                .toArray(String[]::new)
//                        )
//        ));

        return new GetDataSourceCapabilitiesResponse(request.getCatalogName(), capabilities.build());
    }

    private int decodeContinuationToken(GetSplitsRequest request) {
        if (request.hasContinuationToken()) {
            return Integer.parseInt(request.getContinuationToken());
        }

        //No continuation token present
        return 0;
    }

    private String encodeContinuationToken(int partition) {
        return String.valueOf(partition);
    }

}
