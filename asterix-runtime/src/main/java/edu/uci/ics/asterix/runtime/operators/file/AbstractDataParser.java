/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.asterix.runtime.operators.file;

import java.io.DataOutput;

import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.om.base.ABoolean;
import edu.uci.ics.asterix.om.base.ACircle;
import edu.uci.ics.asterix.om.base.ADate;
import edu.uci.ics.asterix.om.base.ADateTime;
import edu.uci.ics.asterix.om.base.ADayTimeDuration;
import edu.uci.ics.asterix.om.base.ADouble;
import edu.uci.ics.asterix.om.base.ADuration;
import edu.uci.ics.asterix.om.base.AFloat;
import edu.uci.ics.asterix.om.base.AInt16;
import edu.uci.ics.asterix.om.base.AInt32;
import edu.uci.ics.asterix.om.base.AInt64;
import edu.uci.ics.asterix.om.base.AInt8;
import edu.uci.ics.asterix.om.base.ALine;
import edu.uci.ics.asterix.om.base.AMutableCircle;
import edu.uci.ics.asterix.om.base.AMutableDate;
import edu.uci.ics.asterix.om.base.AMutableDateTime;
import edu.uci.ics.asterix.om.base.AMutableDayTimeDuration;
import edu.uci.ics.asterix.om.base.AMutableDouble;
import edu.uci.ics.asterix.om.base.AMutableDuration;
import edu.uci.ics.asterix.om.base.AMutableFloat;
import edu.uci.ics.asterix.om.base.AMutableInt16;
import edu.uci.ics.asterix.om.base.AMutableInt32;
import edu.uci.ics.asterix.om.base.AMutableInt64;
import edu.uci.ics.asterix.om.base.AMutableInt8;
import edu.uci.ics.asterix.om.base.AMutableLine;
import edu.uci.ics.asterix.om.base.AMutablePoint;
import edu.uci.ics.asterix.om.base.AMutablePoint3D;
import edu.uci.ics.asterix.om.base.AMutableRectangle;
import edu.uci.ics.asterix.om.base.AMutableString;
import edu.uci.ics.asterix.om.base.AMutableTime;
import edu.uci.ics.asterix.om.base.AMutableYearMonthDuration;
import edu.uci.ics.asterix.om.base.ANull;
import edu.uci.ics.asterix.om.base.APoint;
import edu.uci.ics.asterix.om.base.APoint3D;
import edu.uci.ics.asterix.om.base.ARectangle;
import edu.uci.ics.asterix.om.base.AString;
import edu.uci.ics.asterix.om.base.ATime;
import edu.uci.ics.asterix.om.base.AYearMonthDuration;
import edu.uci.ics.asterix.om.base.temporal.ADateParserFactory;
import edu.uci.ics.asterix.om.base.temporal.ADurationParserFactory;
import edu.uci.ics.asterix.om.base.temporal.ADurationParserFactory.ADurationParseOption;
import edu.uci.ics.asterix.om.base.temporal.ATimeParserFactory;
import edu.uci.ics.asterix.om.base.temporal.GregorianCalendarSystem;
import edu.uci.ics.asterix.om.types.BuiltinType;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;

/**
 * Base class for data parsers. Includes the common set of definitions for
 * serializers/deserializers for built-in ADM types.
 */
public abstract class AbstractDataParser implements IDataParser {

    protected AMutableInt8 aInt8 = new AMutableInt8((byte) 0);
    protected AMutableInt16 aInt16 = new AMutableInt16((short) 0);
    protected AMutableInt32 aInt32 = new AMutableInt32(0);
    protected AMutableInt64 aInt64 = new AMutableInt64(0);
    protected AMutableDouble aDouble = new AMutableDouble(0);
    protected AMutableFloat aFloat = new AMutableFloat(0);
    protected AMutableString aString = new AMutableString("");
    protected AMutableString aStringFieldName = new AMutableString("");
    // For temporal and spatial data types
    protected AMutableTime aTime = new AMutableTime(0);
    protected AMutableDateTime aDateTime = new AMutableDateTime(0L);
    protected AMutableDuration aDuration = new AMutableDuration(0, 0);
    protected AMutableDayTimeDuration aDayTimeDuration = new AMutableDayTimeDuration(0);
    protected AMutableYearMonthDuration aYearMonthDuration = new AMutableYearMonthDuration(0);
    protected AMutablePoint aPoint = new AMutablePoint(0, 0);
    protected AMutablePoint3D aPoint3D = new AMutablePoint3D(0, 0, 0);
    protected AMutableCircle aCircle = new AMutableCircle(null, 0);
    protected AMutableRectangle aRectangle = new AMutableRectangle(null, null);
    protected AMutablePoint aPoint2 = new AMutablePoint(0, 0);
    protected AMutableLine aLine = new AMutableLine(null, null);
    protected AMutableDate aDate = new AMutableDate(0);

    // Serializers
    @SuppressWarnings("unchecked")
    protected ISerializerDeserializer<ADouble> doubleSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ADOUBLE);
    @SuppressWarnings("unchecked")
    protected ISerializerDeserializer<AString> stringSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ASTRING);
    @SuppressWarnings("unchecked")
    protected ISerializerDeserializer<AFloat> floatSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.AFLOAT);
    @SuppressWarnings("unchecked")
    protected ISerializerDeserializer<AInt8> int8Serde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.AINT8);
    @SuppressWarnings("unchecked")
    protected ISerializerDeserializer<AInt16> int16Serde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.AINT16);
    @SuppressWarnings("unchecked")
    protected ISerializerDeserializer<AInt32> int32Serde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.AINT32);
    @SuppressWarnings("unchecked")
    protected ISerializerDeserializer<AInt64> int64Serde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.AINT64);
    @SuppressWarnings("unchecked")
    protected ISerializerDeserializer<ABoolean> booleanSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ABOOLEAN);
    @SuppressWarnings("unchecked")
    protected ISerializerDeserializer<ANull> nullSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ANULL);

    // To avoid race conditions, the serdes for temporal and spatial data types needs to be one per parser
    @SuppressWarnings("unchecked")
    protected static final ISerializerDeserializer<ATime> timeSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ATIME);
    @SuppressWarnings("unchecked")
    protected static final ISerializerDeserializer<ADate> dateSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ADATE);
    @SuppressWarnings("unchecked")
    protected static final ISerializerDeserializer<ADateTime> datetimeSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ADATETIME);
    @SuppressWarnings("unchecked")
    protected static final ISerializerDeserializer<ADuration> durationSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ADURATION);
    @SuppressWarnings("unchecked")
    protected static final ISerializerDeserializer<ADayTimeDuration> dayTimeDurationSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ADAYTIMEDURATION);
    @SuppressWarnings("unchecked")
    protected static final ISerializerDeserializer<AYearMonthDuration> yearMonthDurationSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.AYEARMONTHDURATION);
    @SuppressWarnings("unchecked")
    protected final static ISerializerDeserializer<APoint> pointSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.APOINT);
    @SuppressWarnings("unchecked")
    protected final static ISerializerDeserializer<APoint3D> point3DSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.APOINT3D);
    @SuppressWarnings("unchecked")
    protected final static ISerializerDeserializer<ACircle> circleSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ACIRCLE);
    @SuppressWarnings("unchecked")
    protected final static ISerializerDeserializer<ARectangle> rectangleSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ARECTANGLE);
    @SuppressWarnings("unchecked")
    protected final static ISerializerDeserializer<ALine> lineSerde = AqlSerializerDeserializerProvider.INSTANCE
            .getSerializerDeserializer(BuiltinType.ALINE);

    protected String filename;

    void setFilename(String filename) {
        this.filename = filename;
    }

    protected void parseTime(String time, DataOutput out) throws HyracksDataException {
        int chrononTimeInMs;
        try {
            chrononTimeInMs = ATimeParserFactory.parseTimePart(time, 0, time.length());
        } catch (Exception e) {
            throw new HyracksDataException(e);
        }
        aTime.setValue(chrononTimeInMs);
        timeSerde.serialize(aTime, out);
    }

    protected void parseDate(String date, DataOutput out) throws HyracksDataException {
        long chrononTimeInMs = 0;
        try {
            chrononTimeInMs = ADateParserFactory.parseDatePart(date, 0, date.length());
        } catch (Exception e) {
            throw new HyracksDataException(e);
        }
        short temp = 0;
        if (chrononTimeInMs < 0 && chrononTimeInMs % GregorianCalendarSystem.CHRONON_OF_DAY != 0) {
            temp = 1;
        }
        aDate.setValue((int) (chrononTimeInMs / GregorianCalendarSystem.CHRONON_OF_DAY) - temp);
        dateSerde.serialize(aDate, out);
    }

    protected void parseDateTime(String datetime, DataOutput out) throws HyracksDataException {
        long chrononTimeInMs = 0;
        try {
            // +1 if it is negative (-)
            short timeOffset = (short) ((datetime.charAt(0) == '-') ? 1 : 0);

            timeOffset += 8;

            if (datetime.charAt(timeOffset) != 'T') {
                timeOffset += 2;
                if (datetime.charAt(timeOffset) != 'T') {
                    throw new AlgebricksException("This can not be an instance of datetime: missing T");
                }
            }
            chrononTimeInMs = ADateParserFactory.parseDatePart(datetime, 0, timeOffset);
            chrononTimeInMs += ATimeParserFactory.parseTimePart(datetime, timeOffset + 1, datetime.length()
                    - timeOffset - 1);
        } catch (Exception e) {
            throw new HyracksDataException(e);
        }
        aDateTime.setValue(chrononTimeInMs);
        datetimeSerde.serialize(aDateTime, out);
    }

    protected void parseDuration(String duration, DataOutput out) throws HyracksDataException {
        try {
            ADurationParserFactory.parseDuration(duration, 0, duration.length(), aDuration, ADurationParseOption.All);
            durationSerde.serialize(aDuration, out);
        } catch (Exception e) {
            throw new HyracksDataException(e);
        }
    }

    protected void parseDateTimeDuration(String durationString, DataOutput out) throws HyracksDataException {
        try {
            ADurationParserFactory.parseDuration(durationString, 0, durationString.length(), aDayTimeDuration,
                    ADurationParseOption.All);
            dayTimeDurationSerde.serialize(aDayTimeDuration, out);
        } catch (Exception e) {
            throw new HyracksDataException(e);
        }
    }

    protected void parseYearMonthDuration(String durationString, DataOutput out) throws HyracksDataException {
        try {
            ADurationParserFactory.parseDuration(durationString, 0, durationString.length(), aYearMonthDuration,
                    ADurationParseOption.All);
            yearMonthDurationSerde.serialize(aYearMonthDuration, out);
        } catch (Exception e) {
            throw new HyracksDataException(e);
        }
    }

    protected void parsePoint(String point, DataOutput out) throws HyracksDataException {
        try {
            aPoint.setValue(Double.parseDouble(point.substring(0, point.indexOf(','))),
                    Double.parseDouble(point.substring(point.indexOf(',') + 1, point.length())));
            pointSerde.serialize(aPoint, out);
        } catch (HyracksDataException e) {
            throw new HyracksDataException(point + " can not be an instance of point");
        }
    }

    protected void parse3DPoint(String point3d, DataOutput out) throws HyracksDataException {
        try {
            int firstCommaIndex = point3d.indexOf(',');
            int secondCommaIndex = point3d.indexOf(',', firstCommaIndex + 1);
            aPoint3D.setValue(Double.parseDouble(point3d.substring(0, firstCommaIndex)),
                    Double.parseDouble(point3d.substring(firstCommaIndex + 1, secondCommaIndex)),
                    Double.parseDouble(point3d.substring(secondCommaIndex + 1, point3d.length())));
            point3DSerde.serialize(aPoint3D, out);
        } catch (HyracksDataException e) {
            throw new HyracksDataException(point3d + " can not be an instance of point3d");
        }
    }

    protected void parseCircle(String circle, DataOutput out) throws HyracksDataException {
        try {
            String[] parts = circle.split(" ");
            aPoint.setValue(Double.parseDouble(parts[0].split(",")[0]), Double.parseDouble(parts[0].split(",")[1]));
            aCircle.setValue(aPoint, Double.parseDouble(parts[1].substring(0, parts[1].length())));
            circleSerde.serialize(aCircle, out);
        } catch (HyracksDataException e) {
            throw new HyracksDataException(circle + " can not be an instance of circle");
        }
    }

    protected void parseRectangle(String rectangle, DataOutput out) throws HyracksDataException {
        try {
            String[] points = rectangle.split(" ");
            if (points.length != 2)
                throw new HyracksDataException("rectangle consists of only 2 points.");
            aPoint.setValue(Double.parseDouble(points[0].split(",")[0]), Double.parseDouble(points[0].split(",")[1]));
            aPoint2.setValue(Double.parseDouble(points[1].split(",")[0]), Double.parseDouble(points[1].split(",")[1]));
            if (aPoint.getX() > aPoint2.getX() && aPoint.getY() > aPoint2.getY()) {
                aRectangle.setValue(aPoint2, aPoint);
            } else if (aPoint.getX() < aPoint2.getX() && aPoint.getY() < aPoint2.getY()) {
                aRectangle.setValue(aPoint, aPoint2);
            } else {
                throw new IllegalArgumentException(
                        "Rectangle arugment must be either (bottom left point, top right point) or (top right point, bottom left point)");
            }
            rectangleSerde.serialize(aRectangle, out);
        } catch (HyracksDataException e) {
            throw new HyracksDataException(rectangle + " can not be an instance of rectangle");
        }
    }

    protected void parseLine(String line, DataOutput out) throws HyracksDataException {
        try {
            String[] points = line.split(" ");
            if (points.length != 2)
                throw new HyracksDataException("line consists of only 2 points.");
            aPoint.setValue(Double.parseDouble(points[0].split(",")[0]), Double.parseDouble(points[0].split(",")[1]));
            aPoint2.setValue(Double.parseDouble(points[1].split(",")[0]), Double.parseDouble(points[1].split(",")[1]));
            aLine.setValue(aPoint, aPoint2);
            lineSerde.serialize(aLine, out);
        } catch (HyracksDataException e) {
            throw new HyracksDataException(line + " can not be an instance of line");
        }
    }

}
