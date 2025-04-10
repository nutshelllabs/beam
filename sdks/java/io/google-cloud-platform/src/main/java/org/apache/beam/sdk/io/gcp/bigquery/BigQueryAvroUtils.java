/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.gcp.bigquery;

import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.NANO_OF_SECOND;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkNotNull;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Verify.verify;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.avro.Conversions;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Type;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.io.BaseEncoding;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/** A set of utilities for working with Avro files. */
class BigQueryAvroUtils {

  private static final String VERSION_AVRO =
      Optional.ofNullable(Schema.class.getPackage())
          .map(Package::getImplementationVersion)
          .orElse("");

  // org.apache.avro.LogicalType
  static class DateTimeLogicalType extends LogicalType {
    public DateTimeLogicalType() {
      super("datetime");
    }
  }

  static final DateTimeLogicalType DATETIME_LOGICAL_TYPE = new DateTimeLogicalType();

  /**
   * Defines the valid mapping between BigQuery types and native Avro types.
   *
   * @see <a href=https://cloud.google.com/bigquery/docs/exporting-data#avro_export_details>BQ avro
   *     export</a>
   * @see <a href=https://cloud.google.com/bigquery/docs/reference/storage#avro_schema_details>BQ
   *     avro storage</a>
   * @see <a href=https://cloud.google.com/bigquery/docs/loading-data-cloud-storage-avro>BQ avro
   *     load</a>
   */
  static Schema getPrimitiveType(TableFieldSchema schema, Boolean useAvroLogicalTypes) {
    String bqType = schema.getType();
    // see
    // https://googleapis.dev/java/google-api-services-bigquery/latest/com/google/api/services/bigquery/model/TableFieldSchema.html#getType--
    switch (bqType) {
      case "STRING":
        // string
        return SchemaBuilder.builder().stringType();
      case "BYTES":
        // bytes
        return SchemaBuilder.builder().bytesType();
      case "INTEGER":
      case "INT64":
        // long
        return SchemaBuilder.builder().longType();
      case "FLOAT":
      case "FLOAT64":
        // double
        return SchemaBuilder.builder().doubleType();
      case "BOOLEAN":
      case "BOOL":
        // boolean
        return SchemaBuilder.builder().booleanType();
      case "TIMESTAMP":
        // in Extract Jobs, it always uses the Avro logical type
        // we may have to change this if we move to EXPORT DATA
        return LogicalTypes.timestampMicros().addToSchema(SchemaBuilder.builder().longType());
      case "DATE":
        if (useAvroLogicalTypes) {
          return LogicalTypes.date().addToSchema(SchemaBuilder.builder().intType());
        } else {
          return SchemaBuilder.builder().stringBuilder().prop("sqlType", bqType).endString();
        }
      case "TIME":
        if (useAvroLogicalTypes) {
          return LogicalTypes.timeMicros().addToSchema(SchemaBuilder.builder().longType());
        } else {
          return SchemaBuilder.builder().stringBuilder().prop("sqlType", bqType).endString();
        }
      case "DATETIME":
        if (useAvroLogicalTypes) {
          // BQ export uses a custom logical type
          // TODO for load/storage use
          // LogicalTypes.date().addToSchema(SchemaBuilder.builder().intType())
          return DATETIME_LOGICAL_TYPE.addToSchema(SchemaBuilder.builder().stringType());
        } else {
          return SchemaBuilder.builder().stringBuilder().prop("sqlType", bqType).endString();
        }
      case "NUMERIC":
      case "BIGNUMERIC":
        // decimal
        LogicalType logicalType;
        if (schema.getScale() != null) {
          logicalType =
              LogicalTypes.decimal(schema.getPrecision().intValue(), schema.getScale().intValue());
        } else if (schema.getPrecision() != null) {
          logicalType = LogicalTypes.decimal(schema.getPrecision().intValue());
        } else if (bqType.equals("NUMERIC")) {
          logicalType = LogicalTypes.decimal(38, 9);
        } else {
          // BIGNUMERIC
          logicalType = LogicalTypes.decimal(77, 38);
        }
        return logicalType.addToSchema(SchemaBuilder.builder().bytesType());
      case "GEOGRAPHY":
      case "JSON":
        return SchemaBuilder.builder().stringBuilder().prop("sqlType", bqType).endString();
      case "RECORD":
      case "STRUCT":
        // record
        throw new IllegalArgumentException("RECORD/STRUCT are not primitive types");
      case "RANGE": // TODO add support for range type
      default:
        throw new IllegalArgumentException("Unknown BigQuery type: " + bqType);
    }
  }

  /**
   * Formats BigQuery seconds-since-epoch into String matching JSON export. Thread-safe and
   * immutable.
   */
  private static final DateTimeFormatter DATE_AND_SECONDS_FORMATTER =
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").withZoneUTC();

  @VisibleForTesting
  static String formatTimestamp(Long timestampMicro) {
    String dateTime = formatDatetime(timestampMicro);
    return dateTime + " UTC";
  }

  @VisibleForTesting
  static String formatDatetime(Long timestampMicro) {
    // timestampMicro is in "microseconds since epoch" format,
    // e.g., 1452062291123456L means "2016-01-06 06:38:11.123456 UTC".
    // Separate into seconds and microseconds.
    long timestampSec = timestampMicro / 1_000_000;
    long micros = timestampMicro % 1_000_000;
    if (micros < 0) {
      micros += 1_000_000;
      timestampSec -= 1;
    }
    String dayAndTime = DATE_AND_SECONDS_FORMATTER.print(timestampSec * 1000);
    if (micros == 0) {
      return dayAndTime;
    } else if (micros % 1000 == 0) {
      return String.format("%s.%03d", dayAndTime, micros / 1000);
    } else {
      return String.format("%s.%06d", dayAndTime, micros);
    }
  }

  /**
   * This method formats a BigQuery DATE value into a String matching the format used by JSON
   * export. Date records are stored in "days since epoch" format, and BigQuery uses the proleptic
   * Gregorian calendar.
   */
  private static String formatDate(int date) {
    return LocalDate.ofEpochDay(date).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
  }

  private static final java.time.format.DateTimeFormatter ISO_LOCAL_TIME_FORMATTER_MICROS =
      new DateTimeFormatterBuilder()
          .appendValue(HOUR_OF_DAY, 2)
          .appendLiteral(':')
          .appendValue(MINUTE_OF_HOUR, 2)
          .appendLiteral(':')
          .appendValue(SECOND_OF_MINUTE, 2)
          .appendLiteral('.')
          .appendFraction(NANO_OF_SECOND, 6, 6, false)
          .toFormatter();

  private static final java.time.format.DateTimeFormatter ISO_LOCAL_TIME_FORMATTER_MILLIS =
      new DateTimeFormatterBuilder()
          .appendValue(HOUR_OF_DAY, 2)
          .appendLiteral(':')
          .appendValue(MINUTE_OF_HOUR, 2)
          .appendLiteral(':')
          .appendValue(SECOND_OF_MINUTE, 2)
          .appendLiteral('.')
          .appendFraction(NANO_OF_SECOND, 3, 3, false)
          .toFormatter();

  private static final java.time.format.DateTimeFormatter ISO_LOCAL_TIME_FORMATTER_SECONDS =
      new DateTimeFormatterBuilder()
          .appendValue(HOUR_OF_DAY, 2)
          .appendLiteral(':')
          .appendValue(MINUTE_OF_HOUR, 2)
          .appendLiteral(':')
          .appendValue(SECOND_OF_MINUTE, 2)
          .toFormatter();

  /**
   * This method formats a BigQuery TIME value into a String matching the format used by JSON
   * export. Time records are stored in "microseconds since midnight" format.
   */
  private static String formatTime(long timeMicros) {
    java.time.format.DateTimeFormatter formatter;
    if (timeMicros % 1000000 == 0) {
      formatter = ISO_LOCAL_TIME_FORMATTER_SECONDS;
    } else if (timeMicros % 1000 == 0) {
      formatter = ISO_LOCAL_TIME_FORMATTER_MILLIS;
    } else {
      formatter = ISO_LOCAL_TIME_FORMATTER_MICROS;
    }
    return LocalTime.ofNanoOfDay(timeMicros * 1000).format(formatter);
  }

  /**
   * Utility function to convert from an Avro {@link GenericRecord} to a BigQuery {@link TableRow}.
   *
   * <p>See <a href="https://cloud.google.com/bigquery/exporting-data-from-bigquery#config">"Avro
   * format"</a> for more information.
   *
   * @deprecated Only kept for previous TableRowParser implementation
   */
  @Deprecated
  static TableRow convertGenericRecordToTableRow(GenericRecord record, TableSchema schema) {
    return convertGenericRecordToTableRow(record);
  }

  /**
   * Utility function to convert from an Avro {@link GenericRecord} to a BigQuery {@link TableRow}.
   *
   * <p>See <a href="https://cloud.google.com/bigquery/exporting-data-from-bigquery#config">"Avro
   * format"</a> for more information.
   */
  static TableRow convertGenericRecordToTableRow(GenericRecord record) {
    TableRow row = new TableRow();
    Schema schema = record.getSchema();

    for (Field field : schema.getFields()) {
      Object convertedValue =
          getTypedCellValue(field.name(), field.schema(), record.get(field.pos()));
      if (convertedValue != null) {
        // To match the JSON files exported by BigQuery, do not include null values in the output.
        row.set(field.name(), convertedValue);
      }
    }

    return row;
  }

  private static @Nullable Object getTypedCellValue(String name, Schema schema, Object v) {
    Type type = schema.getType();
    switch (type) {
      case ARRAY:
        return convertRepeatedField(name, schema.getElementType(), v);
      case UNION:
        return convertNullableField(name, schema, v);
      case MAP:
        return convertMapField(name, schema, v);
      default:
        return convertRequiredField(name, schema, v);
    }
  }

  private static List<Object> convertRepeatedField(String name, Schema elementType, Object v) {
    // REPEATED fields are represented as Avro arrays.
    if (v == null) {
      // Handle the case of an empty repeated field.
      return new ArrayList<>();
    }
    @SuppressWarnings("unchecked")
    List<Object> elements = (List<Object>) v;
    ArrayList<Object> values = new ArrayList<>();
    for (Object element : elements) {
      values.add(convertRequiredField(name, elementType, element));
    }
    return values;
  }

  private static List<TableRow> convertMapField(String name, Schema map, Object v) {
    // Avro maps are represented as key/value RECORD.
    if (v == null) {
      // Handle the case of an empty map.
      return new ArrayList<>();
    }

    Schema type = map.getValueType();
    Map<String, Object> elements = (Map<String, Object>) v;
    ArrayList<TableRow> values = new ArrayList<>();
    for (Map.Entry<String, Object> element : elements.entrySet()) {
      TableRow row =
          new TableRow()
              .set("key", element.getKey())
              .set("value", convertRequiredField(name, type, element.getValue()));
      values.add(row);
    }
    return values;
  }

  private static Object convertRequiredField(String name, Schema schema, Object v) {
    // REQUIRED fields are represented as the corresponding Avro types. For example, a BigQuery
    // INTEGER type maps to an Avro LONG type.
    checkNotNull(v, "REQUIRED field %s should not be null", name);

    Type type = schema.getType();
    LogicalType logicalType = schema.getLogicalType();
    switch (type) {
      case BOOLEAN:
        // SQL type BOOL (BOOLEAN)
        return v;
      case INT:
        if (logicalType instanceof LogicalTypes.Date) {
          // SQL type DATE
          // ideally LocalDate but TableRowJsonCoder encodes as String
          return formatDate((Integer) v);
        } else if (logicalType instanceof LogicalTypes.TimeMillis) {
          // Write only: SQL type TIME
          // ideally LocalTime but TableRowJsonCoder encodes as String
          return formatTime(((Integer) v) * 1000L);
        } else {
          // Write only: SQL type INT64 (INT, SMALLINT, INTEGER, BIGINT, TINYINT, BYTEINT)
          // ideally Integer but keep consistency with BQ JSON export that uses String
          return ((Integer) v).toString();
        }
      case LONG:
        if (logicalType instanceof LogicalTypes.TimeMicros) {
          // SQL type TIME
          // ideally LocalTime but TableRowJsonCoder encodes as String
          return formatTime((Long) v);
        } else if (logicalType instanceof LogicalTypes.TimestampMillis) {
          // Write only: SQL type TIMESTAMP
          // ideally Instant but TableRowJsonCoder encodes as String
          return formatTimestamp((Long) v * 1000L);
        } else if (logicalType instanceof LogicalTypes.TimestampMicros) {
          // SQL type TIMESTAMP
          // ideally Instant but TableRowJsonCoder encodes as String
          return formatTimestamp((Long) v);
        } else if (!(VERSION_AVRO.startsWith("1.8") || VERSION_AVRO.startsWith("1.9"))
            && logicalType instanceof LogicalTypes.LocalTimestampMillis) {
          // Write only: SQL type DATETIME
          // ideally LocalDateTime but TableRowJsonCoder encodes as String
          return formatDatetime(((Long) v) * 1000);
        } else if (!(VERSION_AVRO.startsWith("1.8") || VERSION_AVRO.startsWith("1.9"))
            && logicalType instanceof LogicalTypes.LocalTimestampMicros) {
          // Write only: SQL type DATETIME
          // ideally LocalDateTime but TableRowJsonCoder encodes as String
          return formatDatetime((Long) v);
        } else {
          // SQL type INT64 (INT, SMALLINT, INTEGER, BIGINT, TINYINT, BYTEINT)
          // ideally Long if in [2^53+1, 2^53-1] but keep consistency with BQ JSON export that uses
          // String
          return ((Long) v).toString();
        }
      case FLOAT:
        // Write only: SQL type FLOAT64
        // ideally Float but TableRowJsonCoder decodes as Double
        return Double.valueOf(v.toString());
      case DOUBLE:
        // SQL type FLOAT64
        return v;
      case BYTES:
        if (logicalType instanceof LogicalTypes.Decimal) {
          // SQL tpe NUMERIC, BIGNUMERIC
          // ideally BigDecimal but TableRowJsonCoder encodes as String
          return new Conversions.DecimalConversion()
              .fromBytes((ByteBuffer) v, schema, logicalType)
              .toString();
        } else {
          // SQL type BYTES
          // ideally byte[] but TableRowJsonCoder encodes as String
          return BaseEncoding.base64().encode(((ByteBuffer) v).array());
        }
      case STRING:
        // SQL types STRING, DATETIME, GEOGRAPHY, JSON
        // when not using logical type DATE, TIME too
        return v.toString();
      case ENUM:
        // SQL types STRING
        return v.toString();
      case FIXED:
        // SQL type BYTES
        // ideally byte[] but TableRowJsonCoder encodes as String
        return BaseEncoding.base64().encode(((ByteBuffer) v).array());
      case RECORD:
        // SQL types RECORD
        return convertGenericRecordToTableRow((GenericRecord) v);
      default:
        throw new UnsupportedOperationException(
            String.format("Unexpected Avro field schema type %s for field named %s", type, name));
    }
  }

  private static @Nullable Object convertNullableField(String name, Schema union, Object v) {
    // NULLABLE fields are represented as an Avro Union of the corresponding type and "null".
    verify(
        union.getType() == Type.UNION,
        "Expected Avro schema type UNION, not %s, for BigQuery NULLABLE field %s",
        union.getType(),
        name);
    List<Schema> unionTypes = union.getTypes();
    verify(
        unionTypes.size() == 2,
        "BigQuery NULLABLE field %s should be an Avro UNION of NULL and another type, not %s",
        name,
        union);

    Schema type = union.getTypes().get(GenericData.get().resolveUnion(union, v));
    if (type.getType() == Type.NULL) {
      return null;
    } else {
      return convertRequiredField(name, type, v);
    }
  }

  private static Schema toGenericAvroSchema(
      String schemaName,
      List<TableFieldSchema> fieldSchemas,
      Boolean useAvroLogicalTypes,
      @Nullable String namespace) {

    String nextNamespace = namespace == null ? null : String.format("%s.%s", namespace, schemaName);

    List<Field> avroFields = new ArrayList<>();
    for (TableFieldSchema bigQueryField : fieldSchemas) {
      avroFields.add(convertField(bigQueryField, useAvroLogicalTypes, nextNamespace));
    }
    return Schema.createRecord(
        schemaName,
        "Translated Avro Schema for " + schemaName,
        namespace == null ? "org.apache.beam.sdk.io.gcp.bigquery" : namespace,
        false,
        avroFields);
  }

  static Schema toGenericAvroSchema(TableSchema tableSchema) {
    return toGenericAvroSchema("root", tableSchema.getFields(), true);
  }

  static Schema toGenericAvroSchema(TableSchema tableSchema, Boolean useAvroLogicalTypes) {
    return toGenericAvroSchema("root", tableSchema.getFields(), useAvroLogicalTypes);
  }

  static Schema toGenericAvroSchema(
      String schemaName, List<TableFieldSchema> fieldSchemas, Boolean useAvroLogicalTypes) {
    String namespace =
        hasNamespaceCollision(fieldSchemas) ? "org.apache.beam.sdk.io.gcp.bigquery" : null;
    return toGenericAvroSchema(schemaName, fieldSchemas, useAvroLogicalTypes, namespace);
  }

  // To maintain backwards compatibility we only disambiguate collisions in the field namespaces as
  // these never worked with this piece of code.
  private static boolean hasNamespaceCollision(List<TableFieldSchema> fieldSchemas) {
    Set<String> recordTypeFieldNames = new HashSet<>();

    List<TableFieldSchema> fieldsToCheck = new ArrayList<>();
    for (fieldsToCheck.addAll(fieldSchemas); !fieldsToCheck.isEmpty(); ) {
      TableFieldSchema field = fieldsToCheck.remove(0);
      if ("STRUCT".equals(field.getType()) || "RECORD".equals(field.getType())) {
        if (recordTypeFieldNames.contains(field.getName())) {
          return true;
        }
        recordTypeFieldNames.add(field.getName());
        fieldsToCheck.addAll(field.getFields());
      }
    }

    // No collisions present
    return false;
  }

  @SuppressWarnings({
    "nullness" // Avro library not annotated
  })
  private static Field convertField(
      TableFieldSchema bigQueryField, Boolean useAvroLogicalTypes, @Nullable String namespace) {
    String fieldName = bigQueryField.getName();
    Schema fieldSchema;
    String bqType = bigQueryField.getType();
    if ("RECORD".equals(bqType) || "STRUCT".equals(bqType)) {
      fieldSchema =
          toGenericAvroSchema(fieldName, bigQueryField.getFields(), useAvroLogicalTypes, namespace);
    } else {
      fieldSchema = getPrimitiveType(bigQueryField, useAvroLogicalTypes);
    }

    String bqMode = bigQueryField.getMode();
    if (bqMode == null || "NULLABLE".equals(bqMode)) {
      fieldSchema = SchemaBuilder.unionOf().nullType().and().type(fieldSchema).endUnion();
    } else if ("REPEATED".equals(bqMode)) {
      fieldSchema = SchemaBuilder.array().items(fieldSchema);
    } else if (!"REQUIRED".equals(bqMode)) {
      throw new IllegalArgumentException(String.format("Unknown BigQuery Field Mode: %s", bqMode));
    }
    return new Field(
        fieldName,
        fieldSchema,
        bigQueryField.getDescription(),
        (Object) null /* Cast to avoid deprecated JsonNode constructor. */);
  }

  static TableSchema fromGenericAvroSchema(Schema schema) {
    return fromGenericAvroSchema(schema, true);
  }

  static TableSchema fromGenericAvroSchema(Schema schema, Boolean useAvroLogicalTypes) {
    verify(
        schema.getType() == Type.RECORD,
        "Expected Avro schema type RECORD, not %s",
        schema.getType());

    List<TableFieldSchema> fields =
        schema.getFields().stream()
            .map(f -> fromAvroFieldSchema(f, useAvroLogicalTypes))
            .collect(Collectors.toList());
    return new TableSchema().setFields(fields);
  }

  private static TableFieldSchema fromAvroFieldSchema(
      Schema.Field avrofield, Boolean useAvroLogicalTypes) {
    Schema fieldSchema = avrofield.schema();
    TableFieldSchema field;
    switch (fieldSchema.getType()) {
      case UNION:
        List<Schema> types = fieldSchema.getTypes();
        verify(
            types.size() == 2 && types.get(0).getType() == Type.NULL,
            "Avro union field %s should be of null and another type, not %s",
            avrofield.name(),
            fieldSchema);
        field = typedTableFieldSchema(types.get(1), useAvroLogicalTypes).setMode("NULLABLE");
        break;
      case ARRAY:
        field =
            typedTableFieldSchema(fieldSchema.getElementType(), useAvroLogicalTypes)
                .setMode("REPEATED");
        break;
      case MAP:
        TableFieldSchema key =
            new TableFieldSchema().setType("STRING").setName("key").setMode("REQUIRED");
        TableFieldSchema value =
            typedTableFieldSchema(fieldSchema.getValueType(), useAvroLogicalTypes)
                .setName("value")
                .setMode("REQUIRED");
        List<TableFieldSchema> mapTableSchema = new ArrayList<>();
        mapTableSchema.add(key);
        mapTableSchema.add(value);
        field =
            new TableFieldSchema().setType("RECORD").setFields(mapTableSchema).setMode("REPEATED");
        break;
      default:
        field = typedTableFieldSchema(fieldSchema, useAvroLogicalTypes).setMode("REQUIRED");
    }

    return field.setName(avrofield.name()).setDescription(avrofield.doc());
  }

  private static TableFieldSchema typedTableFieldSchema(Schema type, Boolean useAvroLogicalTypes) {
    TableFieldSchema fieldSchema = new TableFieldSchema();
    LogicalType logicalType = useAvroLogicalTypes ? type.getLogicalType() : null;
    String sqlType = useAvroLogicalTypes ? type.getProp("sqlType") : null;
    switch (type.getType()) {
      case INT:
        if (logicalType instanceof LogicalTypes.Date) {
          return fieldSchema.setType("DATE");
        } else if (logicalType instanceof LogicalTypes.TimeMillis) {
          return fieldSchema.setType("TIME");
        } else {
          return fieldSchema.setType("INTEGER");
        }
      case LONG:
        if (logicalType instanceof LogicalTypes.TimeMicros) {
          return fieldSchema.setType("TIME");
        } else if (!(VERSION_AVRO.startsWith("1.8") || VERSION_AVRO.startsWith("1.9"))
            && (logicalType instanceof LogicalTypes.LocalTimestampMillis
                || logicalType instanceof LogicalTypes.LocalTimestampMicros)) {
          return fieldSchema.setType("DATETIME");
        } else if (logicalType instanceof LogicalTypes.TimestampMillis
            || logicalType instanceof LogicalTypes.TimestampMicros) {
          return fieldSchema.setType("TIMESTAMP");
        } else {
          return fieldSchema.setType("INTEGER");
        }
      case FLOAT:
      case DOUBLE:
        return fieldSchema.setType("FLOAT");
      case BOOLEAN:
        return fieldSchema.setType("BOOLEAN");
      case STRING:
        if ("GEOGRAPHY".equals(sqlType)) {
          return fieldSchema.setType("GEOGRAPHY");
        } else if ("JSON".equals(sqlType)) {
          return fieldSchema.setType("JSON");
        } else {
          return fieldSchema.setType("STRING");
        }
      case BYTES:
        if (logicalType instanceof LogicalTypes.Decimal) {
          LogicalTypes.Decimal decimal = (LogicalTypes.Decimal) logicalType;
          int precision = decimal.getPrecision();
          int scale = decimal.getScale();
          if (scale <= 9 && precision - scale <= 29) {
            fieldSchema.setType("NUMERIC");
            if (!(precision == 38 && scale == 9)) {
              fieldSchema.setPrecision((long) precision);
              if (scale != 0) {
                fieldSchema.setScale((long) scale);
              }
            }
          } else {
            fieldSchema.setType("BIGNUMERIC");
            if (!(precision == 77 && scale == 38)) {
              fieldSchema.setPrecision((long) precision);
              if (scale != 0) {
                fieldSchema.setScale((long) scale);
              }
            }
          }
          return fieldSchema;
        } else {
          return fieldSchema.setType("BYTES");
        }
      case ENUM:
        return fieldSchema.setType("STRING");
      case FIXED:
        return fieldSchema.setType("BYTES");
      case RECORD:
        List<TableFieldSchema> recordFields =
            type.getFields().stream()
                .map(f -> fromAvroFieldSchema(f, useAvroLogicalTypes))
                .collect(Collectors.toList());
        return new TableFieldSchema().setType("RECORD").setFields(recordFields);
      default:
        throw new IllegalArgumentException("Unknown Avro type: " + type.getType());
    }
  }
}
