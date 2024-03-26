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
import com.amazonaws.athena.connector.lambda.data.BlockSpiller;
import com.amazonaws.athena.connector.lambda.domain.TableName;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.domain.predicate.ValueSet;
import com.amazonaws.athena.connector.lambda.handlers.RecordHandler;
import com.amazonaws.athena.connector.lambda.records.ReadRecordsRequest;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.reader.RecordReaderIterator;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.TableRead;
import org.apache.paimon.table.source.TableScan;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.RowType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static me.aws.athena.connector.paimon.ValueExtractUtils.paimonValueConvertToAthena;

public class PaimonRecordHandler extends RecordHandler{

    private static final Logger logger = LoggerFactory.getLogger(PaimonMetadataHandler.class);

    private Catalog catalog;

    public PaimonRecordHandler(Map<String, String> configOptions, Catalog catalog) {
        super(Constants.SOURCE_TYPE, configOptions);
        Objects.requireNonNull(catalog);
        this.catalog = catalog;
    }

    @Override
    protected void readWithConstraint(BlockSpiller spiller, ReadRecordsRequest request,
                                      QueryStatusChecker queryStatusChecker) throws Exception {
        String queryId = request.getQueryId();
        String catalogName = request.getCatalogName();
        TableName tableName = request.getTableName();

        Constraints constraints = request.getConstraints();
        Map<String, ValueSet> summary = constraints.getSummary();
        Set<String> reqSchemaFields = request.getSchema().getFields().stream().map(Field::getName).collect(Collectors.toSet());

        logger.info("readWithConstraint->params queryId:{}, catalog:{}, table: {}, valSetSize: {}, valKeys:{}, schema:{}",
                queryId, catalogName, tableName.getTableName(), summary.size(), summary.keySet(), request.getSchema());

        Identifier tablePath = new Identifier(tableName.getSchemaName(), tableName.getTableName());
        Table paimonTable = catalog.getTable(tablePath);
        RowType rowType = paimonTable.rowType();
        List<DataField> fields = rowType.getFields();
        List<Pair<DataField, InternalRow.FieldGetter>> fieldGetters = new ArrayList<>();
        for (DataField field : fields) {
            InternalRow.FieldGetter fieldGetter = InternalRow.createFieldGetter(field.type(), field.id());
            fieldGetters.add(Pair.of(field, fieldGetter));
        }

        Predicate predicate = PushdownUtils.dataReadPushdownPredicate(summary, paimonTable);

        ReadBuilder readBuilder;
        // set Filter
        if (predicate == null) {
            readBuilder = paimonTable.newReadBuilder();
        } else {
            readBuilder = paimonTable.newReadBuilder().withFilter(predicate);
        }

        // set Limit
        if (constraints.hasLimit() && constraints.getLimit() <= Integer.MAX_VALUE) {
            readBuilder.withLimit((int) constraints.getLimit());
        }

        TableScan.Plan plan = readBuilder.newScan().plan();
        TableRead tableRead = readBuilder.newRead();
        tableRead = tableRead.executeFilter();
        RecordReader<InternalRow> reader = tableRead.createReader(plan);
        RecordReaderIterator<InternalRow> iterator = new RecordReaderIterator<>(reader);

        int totalCnt = 0;
        while (iterator.hasNext()) {

            if (!queryStatusChecker.isQueryRunning()) {
                return;
            }

            InternalRow next = iterator.next();
            totalCnt += 1;
            spiller.writeRows(((block, rowNum) -> {

                for (Pair<DataField, InternalRow.FieldGetter> p : fieldGetters) {
                    // confirms the field is the requested filed
                    if (!reqSchemaFields.contains(p.getLeft().name())) {
                        continue;
                    }

                    try {
                        block.setValue(p.getLeft().name(), rowNum, paimonValueConvertToAthena(p.getRight().getFieldOrNull(next)));
                    } catch (Exception e) {
                        logger.error("readWithConstraint: queryId:{} setValue get error, key: {}, val:{}",
                                queryId, p.getLeft().name(), p.getRight().getFieldOrNull(next));
                        logger.error("readWithConstraint setValue error stack: ", e);
                    }

                }
                return 1;
            }));
        }

        logger.info("readWithConstraint: queryId:{}, table: {}, valSetSize: {}, hasLimit:{}, result: {}",
                queryId, tableName.getTableName(), summary.size(), constraints.hasLimit(), totalCnt);

    }

}
