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

import com.amazonaws.athena.connector.lambda.data.Block;
import com.amazonaws.athena.connector.lambda.data.BlockWriter;
import com.amazonaws.athena.connector.lambda.domain.predicate.*;

import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.util.Text;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.predicate.Predicate;
import org.apache.paimon.predicate.PredicateBuilder;
import org.apache.paimon.table.Table;
import org.apache.paimon.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PushdownUtils {

    private final static Logger logger = LoggerFactory.getLogger(PushdownUtils.class);

    private static final String DEFAULT_COLUMN = "col1";


    @Nullable
    public static Predicate dataReadPushdownPredicate(Map<String, ValueSet> valSetMap, Table paimonTable) {
        if (valSetMap == null || valSetMap.isEmpty() || paimonTable == null) {
            return null;
        }

        List<String> keys;
        List<String> partitionKeys = paimonTable.partitionKeys();
        if (partitionKeys == null || partitionKeys.isEmpty()) {
            keys = new ArrayList<>(valSetMap.keySet());
        } else {
            keys = new ArrayList<>(partitionKeys);
            for (String key : valSetMap.keySet()) {
                if (!partitionKeys.contains(key)) {
                    keys.add(key);
                }
            }
        }

        return generatePredicate(valSetMap, paimonTable.rowType(), keys);
    }

    @Nullable
    public static Predicate partitionPushdownPredicate(Map<String, ValueSet> valSetMap, Table paimonTable) {
        Objects.requireNonNull(valSetMap, "value set should not be null");
        Objects.requireNonNull(paimonTable, "paimon table should not be null");

        List<String> partitionKeys = paimonTable.partitionKeys();
//        List<String> fieldNames = paimonTable.rowType().getFieldNames();
        if (partitionKeys == null || partitionKeys.isEmpty()) {
            return null;
        }

        return generatePredicate(valSetMap, paimonTable.rowType(), partitionKeys);


    }

    private static Predicate generatePredicate(Map<String, ValueSet> valSetMap, RowType rowType, List<String> keys) {
        Predicate predicate = null;
        List<String> fieldNames = rowType.getFieldNames();
        for (String  key : keys) {
            if (!valSetMap.containsKey(key)) {
                continue;
            }

            PredicateBuilder predicateBuilder = new PredicateBuilder(rowType);
            int keyIdx = fieldNames.indexOf(key);
            ValueSet valSet = valSetMap.get(key);

            logger.info("generatePredicate: valSet:{}", valSet);

            // if valSet is EquatableValueSet
            if (valSet instanceof EquatableValueSet) {

                // if valSet is single value style
                if (valSet.isSingleValue()) {

                    Object val = convertToPaimonType(valSet.getSingleValue());
                    Predicate tempPred = predicateBuilder.equal(keyIdx, val);
                    predicate = predicate == null ? tempPred : PredicateBuilder.or(predicate, tempPred);

                } else if (!valSet.isAll() && !valSet.isNone()) {
                    // multi value style=
                    Block valBlock = ((EquatableValueSet) valSet).getValues();
                    FieldReader blockReader = valBlock.getFieldReader(DEFAULT_COLUMN);
                    List<Object> valList = new ArrayList<>(valBlock.getRowCount());

                    for (int i = 0; i < valBlock.getRowCount(); i++) {
                        blockReader.setPosition(i);
                        Object val = convertToPaimonType(blockReader.readObject());
                        valList.add(val);
                    }

                    Predicate tempPred = predicateBuilder.in(keyIdx, valList);
                    predicate = predicate == null ? tempPred : PredicateBuilder.or(predicate, tempPred);
                }

            } else if (valSet instanceof SortedRangeSet) {

                if (valSet.isSingleValue()) {
                    Object val = convertToPaimonType(((SortedRangeSet) valSet).getSingleValue());
                    Predicate tempPred = predicateBuilder.equal(keyIdx, val);
                    predicate = predicate == null ? tempPred : PredicateBuilder.or(predicate, tempPred);

                } else if (!valSet.isAll() && !valSet.isNone()) {
                    List<Range> orderedRanges = ((SortedRangeSet) valSet).getOrderedRanges();
                    for (Range range : orderedRanges) {

                        boolean hasLowBound = range.getLow() != null && !range.getLow().isNullValue();
                        boolean hasHighBound = range.getHigh() != null && !range.getHigh().isNullValue();

                        Marker.Bound lowBound = range.getLow() != null ? range.getLow().getBound() : null;
                        Marker.Bound highBound = range.getHigh() != null ? range.getHigh().getBound() : null;
                        Object lowVal = hasLowBound ? convertToPaimonType(range.getLow().getValue()) : null;
                        Object highVal = hasHighBound ? convertToPaimonType(range.getHigh().getValue()) : null;

                        PredicateBuilder lowPreBuilder = new PredicateBuilder(rowType);
                        PredicateBuilder highPreBuilder = new PredicateBuilder(rowType);

                        Predicate lowPredict = !hasLowBound ? null : (Marker.Bound.EXACTLY.equals(lowBound) ?
                                lowPreBuilder.greaterOrEqual(keyIdx, lowVal) : lowPreBuilder.greaterThan(keyIdx, lowVal));
                        Predicate highPredict = !hasHighBound ? null : (Marker.Bound.EXACTLY.equals(highBound) ?
                                highPreBuilder.lessOrEqual(keyIdx, highVal) : highPreBuilder.lessThan(keyIdx, highVal));

                        Predicate tempPredict = (lowPredict !=null && highPredict !=null) ?
                                PredicateBuilder.and(lowPredict, highPredict) : (lowPredict !=null ? lowPredict : highPredict);

                        predicate = predicate == null ? tempPredict :
                                PredicateBuilder.or(predicate, tempPredict);

                    }
                }
            }
        }
        return predicate;
    }

    public static void writeFilteredPartitionData(BlockWriter blockWriter,
                                                  Table paimonTable, List<BinaryRow> parRows) {
        if (parRows == null || parRows.isEmpty()) {
            return;
        }

        RowType rowType = paimonTable.rowType();

        List<String> parKeys = paimonTable.partitionKeys();

        for (BinaryRow parRow : parRows) {
            blockWriter.writeRows((row, rowNum) -> {

                for (int i = 0; i < parRow.getFieldCount(); i++) {
                    String parkey = parKeys.get(i);
                    int keyIdx = rowType.getFieldIndex(parkey);
                    DataType keyType = rowType.getTypeAt(keyIdx);
                    Object val = ValueExtractUtils.extractValueFromBinaryRow(parRow, i, keyType);
                    row.setValue(parkey, rowNum, val);
                }
                return 1;
            });
        }
    }


    private static Object convertToPaimonType(Object val) {
//        Objects.requireNonNull(val);
        if (val == null) {
            return val;
        }

        if (val instanceof Text) {
            return BinaryString.fromBytes(((Text) val).getBytes());
        }
        return val;
    }
}
