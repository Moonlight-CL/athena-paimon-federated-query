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

import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.paimon.types.*;

import java.util.TimeZone;

import static org.apache.arrow.vector.types.FloatingPointPrecision.DOUBLE;
import static org.apache.arrow.vector.types.FloatingPointPrecision.SINGLE;

public class AthenaTypeUtils {

    public static ArrowType fromPaimonType(DataType paimonType) {
        return paimonType.accept(PaimonAthenaTypeVisitor.INSTNACE);
    }

    private static class PaimonAthenaTypeVisitor extends DataTypeDefaultVisitor<ArrowType> {

        public static PaimonAthenaTypeVisitor INSTNACE = new PaimonAthenaTypeVisitor();

        private PaimonAthenaTypeVisitor(){}

        @Override
        public ArrowType visit(CharType charType) {
            return ArrowType.Utf8.INSTANCE;
        }

        @Override
        public ArrowType visit(VarCharType varCharType) {
            return ArrowType.Utf8.INSTANCE;
        }

        @Override
        public ArrowType visit(BooleanType booleanType) {
            return ArrowType.Bool.INSTANCE;
        }

        @Override
        public ArrowType visit(BinaryType binaryType) {
            return ArrowType.Binary.INSTANCE;
        }

        @Override
        public ArrowType visit(VarBinaryType varBinaryType) {
            return ArrowType.Binary.INSTANCE;
        }

        @Override
        public ArrowType visit(DecimalType decimalType) {
            int precision = decimalType.getPrecision();
            int scale = decimalType.getScale();
            if (precision > 38) {
                return new ArrowType.Decimal(precision, scale, 256);
            } else {
                return new ArrowType.Decimal(precision, scale, 128);
            }
        }

        @Override
        public ArrowType visit(TinyIntType tinyIntType) {
            return new ArrowType.Int(8, true);
        }

        @Override
        public ArrowType visit(SmallIntType smallIntType) {
            return new ArrowType.Int(16, true);
        }

        @Override
        public ArrowType visit(IntType intType) {
            return new ArrowType.Int(32, true);
        }

        @Override
        public ArrowType visit(BigIntType bigIntType) {
            return new ArrowType.Int(64, true);
        }

        @Override
        public ArrowType visit(FloatType floatType) {
            return new ArrowType.FloatingPoint(SINGLE);
        }

        @Override
        public ArrowType visit(DoubleType doubleType) {
            return new ArrowType.FloatingPoint(DOUBLE);
        }

        @Override
        public ArrowType visit(DateType dateType) {
            return new ArrowType.Date(DateUnit.DAY);
        }

        @Override
        public ArrowType visit(TimeType timeType) {
            return new ArrowType.Time(TimeUnit.MILLISECOND, 32);
        }

        @Override
        public ArrowType visit(TimestampType timestampType) {
//            String timezone = TimeZone.getDefault().getID();
            return new ArrowType.Date(DateUnit.MILLISECOND);
        }

        @Override
        public ArrowType visit(LocalZonedTimestampType localZonedTimestampType) {
            String timezone = TimeZone.getDefault().getID();
            return new ArrowType.Timestamp(TimeUnit.MILLISECOND, timezone);
        }

        @Override
        public ArrowType visit(ArrayType arrayType) {
            return new ArrowType.List();
        }

        @Override
        public ArrowType visit(MultisetType multisetType) {
            throw new UnsupportedOperationException("Unmapped type: " + multisetType.toString());
        }

        @Override
        public ArrowType visit(MapType mapType) {
            return new ArrowType.Map(false);
        }

        @Override
        public ArrowType visit(RowType rowType) {
            throw new UnsupportedOperationException("Unmapped type: " + rowType.toString());
        }

        @Override
        protected ArrowType defaultMethod(DataType dataType) {
            throw new UnsupportedOperationException("Unsupported type: " + dataType);
        }
    }
}
