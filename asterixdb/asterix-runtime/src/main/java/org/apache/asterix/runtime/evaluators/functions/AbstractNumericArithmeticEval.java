/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.runtime.evaluators.functions;

import java.io.DataOutput;

import org.apache.asterix.dataflow.data.nontagged.serde.ADateSerializerDeserializer;
import org.apache.asterix.dataflow.data.nontagged.serde.ADateTimeSerializerDeserializer;
import org.apache.asterix.dataflow.data.nontagged.serde.ADayTimeDurationSerializerDeserializer;
import org.apache.asterix.dataflow.data.nontagged.serde.ADoubleSerializerDeserializer;
import org.apache.asterix.dataflow.data.nontagged.serde.ADurationSerializerDeserializer;
import org.apache.asterix.dataflow.data.nontagged.serde.AFloatSerializerDeserializer;
import org.apache.asterix.dataflow.data.nontagged.serde.AInt16SerializerDeserializer;
import org.apache.asterix.dataflow.data.nontagged.serde.AInt32SerializerDeserializer;
import org.apache.asterix.dataflow.data.nontagged.serde.AInt64SerializerDeserializer;
import org.apache.asterix.dataflow.data.nontagged.serde.AInt8SerializerDeserializer;
import org.apache.asterix.dataflow.data.nontagged.serde.ATimeSerializerDeserializer;
import org.apache.asterix.dataflow.data.nontagged.serde.AYearMonthDurationSerializerDeserializer;
import org.apache.asterix.formats.nontagged.SerializerDeserializerProvider;
import org.apache.asterix.om.base.AMutableDate;
import org.apache.asterix.om.base.AMutableDateTime;
import org.apache.asterix.om.base.AMutableDouble;
import org.apache.asterix.om.base.AMutableDuration;
import org.apache.asterix.om.base.AMutableFloat;
import org.apache.asterix.om.base.AMutableInt16;
import org.apache.asterix.om.base.AMutableInt32;
import org.apache.asterix.om.base.AMutableInt64;
import org.apache.asterix.om.base.AMutableInt8;
import org.apache.asterix.om.base.AMutableTime;
import org.apache.asterix.om.base.temporal.GregorianCalendarSystem;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.asterix.om.types.EnumDeserializer;
import org.apache.asterix.runtime.evaluators.base.AbstractScalarFunctionDynamicDescriptor;
import org.apache.asterix.runtime.exceptions.IncompatibleTypeException;
import org.apache.asterix.runtime.exceptions.OverflowException;
import org.apache.asterix.runtime.exceptions.TypeMismatchException;
import org.apache.asterix.runtime.exceptions.UnderflowException;
import org.apache.asterix.runtime.exceptions.UnsupportedTypeException;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluator;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.ISerializerDeserializer;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.data.std.api.IPointable;
import org.apache.hyracks.data.std.primitive.VoidPointable;
import org.apache.hyracks.data.std.util.ArrayBackedValueStorage;
import org.apache.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

@SuppressWarnings("serial")
public abstract class AbstractNumericArithmeticEval extends AbstractScalarFunctionDynamicDescriptor {

    abstract protected long evaluateInteger(long lhs, long rhs) throws HyracksDataException;

    abstract protected double evaluateDouble(double lhs, double rhs) throws HyracksDataException;

    /**
     * abstract method for arithmetic operation between a time instance (date/time/datetime)
     * and a duration (duration/year-month-duration/day-time-duration)
     *
     * @param chronon
     * @param yearMonth
     * @param dayTime
     * @return
     * @throws HyracksDataException
     */
    abstract protected long evaluateTimeDurationArithmetic(long chronon, int yearMonth, long dayTime,
            boolean isTimeOnly) throws HyracksDataException;

    /**
     * abstract method for arithmetic operation between two time instances (date/time/datetime)
     *
     * @param chronon0
     * @param chronon1
     * @return
     * @throws HyracksDataException
     */
    abstract protected long evaluateTimeInstanceArithmetic(long chronon0, long chronon1) throws HyracksDataException;

    @Override
    public IScalarEvaluatorFactory createEvaluatorFactory(final IScalarEvaluatorFactory[] args)
            throws AlgebricksException {
        return new IScalarEvaluatorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public IScalarEvaluator createScalarEvaluator(IHyracksTaskContext ctx) throws HyracksDataException {

                return new IScalarEvaluator() {
                    private ArrayBackedValueStorage resultStorage = new ArrayBackedValueStorage();
                    private DataOutput out = resultStorage.getDataOutput();
                    private IPointable argPtr0 = new VoidPointable();
                    private IPointable argPtr1 = new VoidPointable();
                    private IScalarEvaluator evalLeft = args[0].createScalarEvaluator(ctx);
                    private IScalarEvaluator evalRight = args[1].createScalarEvaluator(ctx);
                    private double[] operandsFloating = new double[args.length];
                    private long[] operandsInteger = new long[args.length];
                    private int resultType;
                    static protected final int typeInt8 = 1;
                    static protected final int typeInt16 = 2;
                    static protected final int typeInt32 = 3;
                    static protected final int typeInt64 = 4;
                    static protected final int typeFloat = 5;
                    static protected final int typeDouble = 6;

                    protected AMutableFloat aFloat = new AMutableFloat(0);
                    protected AMutableDouble aDouble = new AMutableDouble(0);
                    protected AMutableInt64 aInt64 = new AMutableInt64(0);
                    protected AMutableInt32 aInt32 = new AMutableInt32(0);
                    protected AMutableInt16 aInt16 = new AMutableInt16((short) 0);
                    protected AMutableInt8 aInt8 = new AMutableInt8((byte) 0);

                    protected AMutableDuration aDuration = new AMutableDuration(0, 0);
                    protected AMutableDate aDate = new AMutableDate(0);
                    protected AMutableTime aTime = new AMutableTime(0);
                    protected AMutableDateTime aDatetime = new AMutableDateTime(0);

                    private ATypeTag typeTag;
                    @SuppressWarnings("rawtypes")
                    private ISerializerDeserializer serde;

                    @SuppressWarnings("unchecked")
                    @Override
                    public void evaluate(IFrameTupleReference tuple, IPointable result) throws HyracksDataException {
                        resultStorage.reset();
                        resultType = 0;
                        int currentType;
                        evalLeft.evaluate(tuple, argPtr0);
                        evalRight.evaluate(tuple, argPtr1);

                        for (int i = 0; i < args.length; i++) {
                            IPointable argPtr = i == 0 ? argPtr0 : argPtr1;
                            byte[] bytes = argPtr.getByteArray();
                            int offset = argPtr.getStartOffset();

                            typeTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(bytes[offset]);
                            switch (typeTag) {
                                case TINYINT:
                                    currentType = typeInt8;
                                    operandsInteger[i] = AInt8SerializerDeserializer.getByte(bytes, offset + 1);
                                    operandsFloating[i] = operandsInteger[i];
                                    break;
                                case SMALLINT:
                                    currentType = typeInt16;
                                    operandsInteger[i] = AInt16SerializerDeserializer.getShort(bytes, offset + 1);
                                    operandsFloating[i] = operandsInteger[i];
                                    break;
                                case INTEGER:
                                    currentType = typeInt32;
                                    operandsInteger[i] = AInt32SerializerDeserializer.getInt(bytes, offset + 1);
                                    operandsFloating[i] = operandsInteger[i];
                                    break;
                                case BIGINT:
                                    currentType = typeInt64;
                                    operandsInteger[i] = AInt64SerializerDeserializer.getLong(bytes, offset + 1);
                                    operandsFloating[i] = operandsInteger[i];
                                    break;
                                case FLOAT:
                                    currentType = typeFloat;
                                    operandsFloating[i] = AFloatSerializerDeserializer.getFloat(bytes, offset + 1);
                                    break;
                                case DOUBLE:
                                    currentType = typeDouble;
                                    operandsFloating[i] = ADoubleSerializerDeserializer.getDouble(bytes, offset + 1);
                                    break;
                                case DATE:
                                case TIME:
                                case DATETIME:
                                case DURATION:
                                case YEARMONTHDURATION:
                                case DAYTIMEDURATION:
                                    evaluateTemporalArthmeticOperation(typeTag);
                                    result.set(resultStorage);
                                    return;
                                default:
                                    throw new TypeMismatchException(getIdentifier(), i, bytes[offset],
                                            ATypeTag.SERIALIZED_INT8_TYPE_TAG, ATypeTag.SERIALIZED_INT16_TYPE_TAG,
                                            ATypeTag.SERIALIZED_INT32_TYPE_TAG, ATypeTag.SERIALIZED_INT64_TYPE_TAG,
                                            ATypeTag.SERIALIZED_FLOAT_TYPE_TAG, ATypeTag.SERIALIZED_DOUBLE_TYPE_TAG,
                                            ATypeTag.SERIALIZED_DATE_TYPE_TAG, ATypeTag.SERIALIZED_TIME_TYPE_TAG,
                                            ATypeTag.SERIALIZED_DATETIME_TYPE_TAG,
                                            ATypeTag.SERIALIZED_DURATION_TYPE_TAG,
                                            ATypeTag.SERIALIZED_YEAR_MONTH_DURATION_TYPE_TAG,
                                            ATypeTag.SERIALIZED_DAY_TIME_DURATION_TYPE_TAG);
                            }

                            if (resultType < currentType) {
                                resultType = currentType;
                            }
                        }

                        long lres;
                        double dres;
                        switch (resultType) {
                            case typeInt8:
                                serde = SerializerDeserializerProvider.INSTANCE
                                        .getSerializerDeserializer(BuiltinType.AINT8);
                                lres = evaluateInteger(operandsInteger[0], operandsInteger[1]);
                                if (lres > Byte.MAX_VALUE) {
                                    throw new OverflowException(getIdentifier());
                                }
                                if (lres < Byte.MIN_VALUE) {
                                    throw new UnderflowException(getIdentifier());
                                }
                                aInt8.setValue((byte) lres);
                                serde.serialize(aInt8, out);
                                break;
                            case typeInt16:
                                serde = SerializerDeserializerProvider.INSTANCE
                                        .getSerializerDeserializer(BuiltinType.AINT16);
                                lres = evaluateInteger(operandsInteger[0], operandsInteger[1]);
                                if (lres > Short.MAX_VALUE) {
                                    throw new OverflowException(getIdentifier());
                                }
                                if (lres < Short.MIN_VALUE) {
                                    throw new UnderflowException(getIdentifier());
                                }
                                aInt16.setValue((short) lres);
                                serde.serialize(aInt16, out);
                                break;
                            case typeInt32:
                                serde = SerializerDeserializerProvider.INSTANCE
                                        .getSerializerDeserializer(BuiltinType.AINT32);
                                lres = evaluateInteger(operandsInteger[0], operandsInteger[1]);
                                if (lres > Integer.MAX_VALUE) {
                                    throw new OverflowException(getIdentifier());
                                }
                                if (lres < Integer.MIN_VALUE) {
                                    throw new UnderflowException(getIdentifier());
                                }
                                aInt32.setValue((int) lres);
                                serde.serialize(aInt32, out);
                                break;
                            case typeInt64:
                                serde = SerializerDeserializerProvider.INSTANCE
                                        .getSerializerDeserializer(BuiltinType.AINT64);
                                lres = evaluateInteger(operandsInteger[0], operandsInteger[1]);
                                aInt64.setValue(lres);
                                serde.serialize(aInt64, out);
                                break;
                            case typeFloat:
                                serde = SerializerDeserializerProvider.INSTANCE
                                        .getSerializerDeserializer(BuiltinType.AFLOAT);
                                dres = evaluateDouble(operandsFloating[0], operandsFloating[1]);
                                if (dres > Float.MAX_VALUE) {
                                    throw new OverflowException(getIdentifier());
                                }
                                if (dres < -Float.MAX_VALUE) {
                                    throw new UnderflowException(getIdentifier());
                                }
                                aFloat.setValue((float) dres);
                                serde.serialize(aFloat, out);
                                break;
                            case typeDouble:
                                serde = SerializerDeserializerProvider.INSTANCE
                                        .getSerializerDeserializer(BuiltinType.ADOUBLE);
                                aDouble.setValue(evaluateDouble(operandsFloating[0], operandsFloating[1]));
                                serde.serialize(aDouble, out);
                                break;
                        }
                        result.set(resultStorage);
                    }

                    @SuppressWarnings("unchecked")
                    private void evaluateTemporalArthmeticOperation(ATypeTag leftType) throws HyracksDataException {
                        byte[] bytes1 = argPtr1.getByteArray();
                        int offset1 = argPtr1.getStartOffset();
                        ATypeTag rightType = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(bytes1[offset1]);
                        byte[] bytes0 = argPtr0.getByteArray();
                        int offset0 = argPtr0.getStartOffset();

                        if (rightType == leftType) {

                            serde = SerializerDeserializerProvider.INSTANCE
                                    .getSerializerDeserializer(BuiltinType.ADURATION);

                            long leftChronon = 0, rightChronon = 0, dayTime = 0;

                            int yearMonth = 0;

                            switch (leftType) {
                                case DATE:
                                    leftChronon = ADateSerializerDeserializer.getChronon(bytes0, offset0 + 1)
                                            * GregorianCalendarSystem.CHRONON_OF_DAY;
                                    rightChronon = ADateSerializerDeserializer.getChronon(bytes1, offset1 + 1)
                                            * GregorianCalendarSystem.CHRONON_OF_DAY;

                                    break;
                                case TIME:
                                    leftChronon = ATimeSerializerDeserializer.getChronon(bytes0, offset0 + 1);
                                    rightChronon = ATimeSerializerDeserializer.getChronon(bytes1, offset1 + 1);
                                    break;
                                case DATETIME:
                                    leftChronon = ADateTimeSerializerDeserializer.getChronon(bytes0, offset0 + 1);
                                    rightChronon = ADateTimeSerializerDeserializer.getChronon(bytes1, offset1 + 1);
                                    break;
                                case YEARMONTHDURATION:
                                    yearMonth = (int) evaluateTimeInstanceArithmetic(
                                            AYearMonthDurationSerializerDeserializer.getYearMonth(bytes0, offset0 + 1),
                                            AYearMonthDurationSerializerDeserializer.getYearMonth(bytes1, offset1 + 1));
                                    break;
                                case DAYTIMEDURATION:
                                    leftChronon =
                                            ADayTimeDurationSerializerDeserializer.getDayTime(bytes0, offset0 + 1);
                                    rightChronon =
                                            ADayTimeDurationSerializerDeserializer.getDayTime(bytes1, offset1 + 1);
                                    break;
                                default:
                                    throw new UnsupportedTypeException(getIdentifier(), bytes1[offset1]);
                            }

                            dayTime = evaluateTimeInstanceArithmetic(leftChronon, rightChronon);

                            aDuration.setValue(yearMonth, dayTime);

                            serde.serialize(aDuration, out);

                        } else {
                            long chronon = 0, dayTime = 0;
                            int yearMonth = 0;
                            ATypeTag resultType = null;

                            boolean isTimeOnly = false;

                            switch (leftType) {
                                case TIME:
                                    serde = SerializerDeserializerProvider.INSTANCE
                                            .getSerializerDeserializer(BuiltinType.ATIME);
                                    chronon = ATimeSerializerDeserializer.getChronon(bytes0, offset0 + 1);
                                    isTimeOnly = true;
                                    resultType = ATypeTag.TIME;
                                    switch (rightType) {
                                        case DAYTIMEDURATION:
                                            dayTime = ADayTimeDurationSerializerDeserializer.getDayTime(bytes1,
                                                    offset1 + 1);
                                            break;
                                        case DURATION:
                                            dayTime = ADurationSerializerDeserializer.getDayTime(bytes1, offset1 + 1);
                                            yearMonth =
                                                    ADurationSerializerDeserializer.getYearMonth(bytes1, offset1 + 1);
                                            break;
                                        default:
                                            throw new IncompatibleTypeException(getIdentifier(), bytes0[offset0],
                                                    bytes1[offset1]);
                                    }
                                    break;
                                case DATE:
                                    serde = SerializerDeserializerProvider.INSTANCE
                                            .getSerializerDeserializer(BuiltinType.ADATE);
                                    resultType = ATypeTag.DATE;
                                    chronon = ADateSerializerDeserializer.getChronon(bytes0, offset0 + 1)
                                            * GregorianCalendarSystem.CHRONON_OF_DAY;
                                case DATETIME:
                                    if (leftType == ATypeTag.DATETIME) {
                                        serde = SerializerDeserializerProvider.INSTANCE
                                                .getSerializerDeserializer(BuiltinType.ADATETIME);
                                        resultType = ATypeTag.DATETIME;
                                        chronon = ADateTimeSerializerDeserializer.getChronon(bytes0, offset0 + 1);
                                    }
                                    switch (rightType) {
                                        case DURATION:
                                            yearMonth =
                                                    ADurationSerializerDeserializer.getYearMonth(bytes1, offset1 + 1);
                                            dayTime = ADurationSerializerDeserializer.getDayTime(bytes1, offset1 + 1);
                                            break;
                                        case YEARMONTHDURATION:
                                            yearMonth = AYearMonthDurationSerializerDeserializer.getYearMonth(bytes1,
                                                    offset1 + 1);
                                            break;
                                        case DAYTIMEDURATION:
                                            dayTime = ADayTimeDurationSerializerDeserializer.getDayTime(bytes1,
                                                    offset1 + 1);
                                            break;
                                        default:
                                            throw new IncompatibleTypeException(getIdentifier(), bytes0[offset0],
                                                    bytes1[offset1]);
                                    }
                                    break;
                                case YEARMONTHDURATION:
                                    yearMonth =
                                            AYearMonthDurationSerializerDeserializer.getYearMonth(bytes0, offset0 + 1);
                                    switch (rightType) {
                                        case DATETIME:
                                            serde = SerializerDeserializerProvider.INSTANCE
                                                    .getSerializerDeserializer(BuiltinType.ADATETIME);
                                            resultType = ATypeTag.DATETIME;
                                            chronon = ADateTimeSerializerDeserializer.getChronon(bytes1, offset1 + 1);
                                            break;
                                        case DATE:
                                            serde = SerializerDeserializerProvider.INSTANCE
                                                    .getSerializerDeserializer(BuiltinType.ADATE);
                                            resultType = ATypeTag.DATE;
                                            chronon = ADateSerializerDeserializer.getChronon(bytes1, offset1 + 1)
                                                    * GregorianCalendarSystem.CHRONON_OF_DAY;
                                            break;
                                        default:
                                            throw new IncompatibleTypeException(getIdentifier(), bytes0[offset0],
                                                    bytes1[offset1]);
                                    }
                                    break;
                                case DURATION:
                                    yearMonth = ADurationSerializerDeserializer.getYearMonth(bytes0, offset0 + 1);
                                    dayTime = ADurationSerializerDeserializer.getDayTime(bytes0, offset0 + 1);
                                case DAYTIMEDURATION:
                                    if (leftType == ATypeTag.DAYTIMEDURATION) {
                                        dayTime =
                                                ADayTimeDurationSerializerDeserializer.getDayTime(bytes0, offset0 + 1);
                                    }
                                    switch (rightType) {
                                        case DATETIME:
                                            serde = SerializerDeserializerProvider.INSTANCE
                                                    .getSerializerDeserializer(BuiltinType.ADATETIME);
                                            resultType = ATypeTag.DATETIME;
                                            chronon = ADateTimeSerializerDeserializer.getChronon(bytes1, offset1 + 1);
                                            break;
                                        case DATE:
                                            serde = SerializerDeserializerProvider.INSTANCE
                                                    .getSerializerDeserializer(BuiltinType.ADATE);
                                            resultType = ATypeTag.DATE;
                                            chronon = ADateSerializerDeserializer.getChronon(bytes1, offset1 + 1)
                                                    * GregorianCalendarSystem.CHRONON_OF_DAY;
                                            break;
                                        case TIME:
                                            if (yearMonth == 0) {
                                                serde = SerializerDeserializerProvider.INSTANCE
                                                        .getSerializerDeserializer(BuiltinType.ATIME);
                                                resultType = ATypeTag.TIME;
                                                chronon = ATimeSerializerDeserializer.getChronon(bytes1, offset1 + 1);
                                                isTimeOnly = true;
                                                break;
                                            }
                                        default:
                                            throw new IncompatibleTypeException(getIdentifier(), bytes0[offset0],
                                                    bytes1[offset1]);
                                    }
                                    break;
                                default:
                                    throw new IncompatibleTypeException(getIdentifier(), bytes0[offset0],
                                            bytes1[offset1]);
                            }

                            chronon = evaluateTimeDurationArithmetic(chronon, yearMonth, dayTime, isTimeOnly);

                            switch (resultType) {
                                case DATE:
                                    if (chronon < 0 && chronon % GregorianCalendarSystem.CHRONON_OF_DAY != 0) {
                                        chronon = chronon / GregorianCalendarSystem.CHRONON_OF_DAY - 1;
                                    } else {
                                        chronon = chronon / GregorianCalendarSystem.CHRONON_OF_DAY;
                                    }
                                    aDate.setValue((int) chronon);
                                    serde.serialize(aDate, out);
                                    break;
                                case TIME:
                                    aTime.setValue((int) chronon);
                                    serde.serialize(aTime, out);
                                    break;
                                case DATETIME:
                                    aDatetime.setValue(chronon);
                                    serde.serialize(aDatetime, out);
                                    break;
                                default:
                                    throw new IncompatibleTypeException(getIdentifier(), bytes0[offset0],
                                            bytes1[offset1]);
                            }
                        }
                    }
                };
            }
        };
    }
}
