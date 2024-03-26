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
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.Timestamp;
import org.apache.paimon.types.DataType;

public class ValueExtractUtils {

    public static Object paimonValueConvertToAthena(Object val) {
        if (val == null) {
            return val;
        }

        if (val instanceof BinaryString) {
            val = val.toString();
        } else if (val instanceof Timestamp) {
            return ((Timestamp) val).getMillisecond();
        }

        return val;

    }
    public  static <T extends InternalRow> Object extractValueFromBinaryRow(T parRow, int fieldIdx, DataType keyType) {
        InternalRow.FieldGetter fieldGetter = T.createFieldGetter(keyType, fieldIdx);
        Object result = fieldGetter.getFieldOrNull(parRow);
        return paimonValueConvertToAthena(result);

//        switch (keyType.getTypeRoot()) {
//            case CHAR:
//            case VARCHAR:
//                return parRow.getString(fieldIdx).toString();
//            case BOOLEAN:
//                return parRow.getBoolean(fieldIdx);
//            case BINARY:
//            case VARBINARY:
//                return parRow.getBinary(fieldIdx);
//            case DECIMAL:
//                return parRow.getDecimal(fieldIdx,
//                        ((DecimalType)keyType).getPrecision(),((DecimalType)keyType).getScale()).toBigDecimal();
//            case TINYINT:
//                return parRow.getByte(fieldIdx);
//            case SMALLINT:
//                return parRow.getShort(fieldIdx);
//            case INTEGER:
//            case DATE:
//            case TIME_WITHOUT_TIME_ZONE:
//                return parRow.getInt(fieldIdx);
//            case BIGINT:
//                return parRow.getLong(fieldIdx);
//            case FLOAT:
//                return parRow.getFloat(fieldIdx);
//            case DOUBLE:
//                return parRow.getDouble(fieldIdx);
//            case TIMESTAMP_WITHOUT_TIME_ZONE:
//            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
//                return parRow.getTimestamp(fieldIdx,
//                        ((TimestampType)keyType).getPrecision()).toLocalDateTime();
//            default:
//                throw new RuntimeException("extractValueFromBinaryRow: Type not support: " + keyType.toString());
//        }
    }


    public static Object extractValueFromBlock(Block block, String key, int rowNum, DataType dataType) {
        FieldReader fieldReader = block.getFieldReader(key);
        fieldReader.setPosition(rowNum);

        switch (dataType.getTypeRoot()) {
            case CHAR:
            case VARCHAR:
                return fieldReader.readText().toString();
            case BOOLEAN:
                return fieldReader.readBoolean();
            case BINARY:
            case VARBINARY:
                return fieldReader.readByteArray();
            case DECIMAL:
                return fieldReader.readBigDecimal();
            case TINYINT:
                 return fieldReader.readByte();
            case SMALLINT:
                return fieldReader.readShort();
            case INTEGER:
            case DATE:
            case TIME_WITHOUT_TIME_ZONE:
                return fieldReader.readInteger();
            case BIGINT:
                return fieldReader.readLong();
            case FLOAT:
                return fieldReader.readFloat();
            case DOUBLE:
                return fieldReader.readDouble();
            case TIMESTAMP_WITHOUT_TIME_ZONE:
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                return fieldReader.readLocalDateTime();
            default:
                throw new RuntimeException("extractValueFromBlock: Type not support: " + dataType.toString());
        }
    }

}
