/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.spotify.scio.bigquery

import java.math.MathContext
import java.nio.ByteBuffer

import com.spotify.scio.ScioContext
import com.spotify.scio.values.SCollection
import com.google.api.services.bigquery.model.{
  TableRow => GTableRow,
  TimePartitioning => GTimePartitioning,
  TableReference => GTableReference
}
import org.apache.beam.sdk.io.gcp.bigquery.{BigQueryHelpers, BigQueryInsertError, WriteResult}
import org.apache.avro.Conversions.DecimalConversion
import org.apache.avro.LogicalTypes
import org.joda.time.format.{DateTimeFormat, DateTimeFormatterBuilder}
import org.joda.time.DateTimeZone
import org.joda.time.{Instant, LocalDate, LocalDateTime, LocalTime}

sealed trait Source

final case class Query(underlying: String) extends Source

sealed trait Table extends Source {
  def spec: String

  def ref: GTableReference
}

object Table {
  final case class Ref(ref: GTableReference) extends Table {
    override lazy val spec: String = BigQueryHelpers.toTableSpec(ref)
  }
  final case class Spec(spec: String) extends Table {
    override val ref: GTableReference = BigQueryHelpers.parseTableSpec(spec)
  }
}

sealed trait ExtendedErrorInfo {
  type Info

  private[scio] def coll(sc: ScioContext, wr: WriteResult): SCollection[Info]
}

object ExtendedErrorInfo {
  final case object Enabled extends ExtendedErrorInfo {
    override type Info = BigQueryInsertError

    override private[scio] def coll(sc: ScioContext, wr: WriteResult): SCollection[Info] =
      sc.wrap(wr.getFailedInsertsWithErr())
  }

  final case object Disabled extends ExtendedErrorInfo {
    override type Info = TableRow

    override private[scio] def coll(sc: ScioContext, wr: WriteResult): SCollection[Info] =
      sc.wrap(wr.getFailedInserts())
  }
}

/**
 * Create a [[TableRow]] with `Map`-like syntax. For example:
 *
 * {{{
 * val r = TableRow("name" -> "Alice", "score" -> 100)
 * }}}
 */
object TableRow {
  @inline def apply(fields: (String, _)*): TableRow =
    fields.foldLeft(new GTableRow())((r, kv) => r.set(kv._1, kv._2))
}

/** Utility for BigQuery `TIMESTAMP` type. */
object Timestamp {
  // YYYY-[M]M-[D]D[( |T)[H]H:[M]M:[S]S[.DDDDDD]][time zone]
  private[this] val Formatter =
    DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS ZZZ")

  private[this] val Parser = new DateTimeFormatterBuilder()
    .append(DateTimeFormat.forPattern("yyyy-MM-dd"))
    .appendOptional(
      new DateTimeFormatterBuilder()
        .append(DateTimeFormat.forPattern(" HH:mm:ss").getParser)
        .appendOptional(DateTimeFormat.forPattern(".SSSSSS").getParser)
        .toParser
    )
    .appendOptional(
      new DateTimeFormatterBuilder()
        .append(DateTimeFormat.forPattern("'T'HH:mm:ss").getParser)
        .appendOptional(DateTimeFormat.forPattern(".SSSSSS").getParser)
        .toParser
    )
    .appendOptional(
      new DateTimeFormatterBuilder()
        .append(null, Array(" ZZZ", "ZZ").map(p => DateTimeFormat.forPattern(p).getParser))
        .toParser
    )
    .toFormatter
    .withZoneUTC()

  /** Convert `Instant` to BigQuery `TIMESTAMP` string. */
  def apply(instant: Instant): String = Formatter.print(instant)

  /** Convert millisecond instant to BigQuery `TIMESTAMP` string. */
  def apply(instant: Long): String = Formatter.print(instant)

  /** Convert BigQuery `TIMESTAMP` string to `Instant`. */
  def parse(timestamp: String): Instant =
    Parser.parseDateTime(timestamp).toInstant

  // For BigQueryType macros only, do not use directly
  def parse(timestamp: Any): Instant = timestamp match {
    case t: Long => new Instant(t / 1000)
    case _       => parse(timestamp.toString)
  }
}

/** Utility for BigQuery `DATE` type. */
object Date {
  // YYYY-[M]M-[D]D
  private[this] val Formatter =
    DateTimeFormat.forPattern("yyyy-MM-dd").withZoneUTC()

  /** Convert `LocalDate` to BigQuery `DATE` string. */
  def apply(date: LocalDate): String = Formatter.print(date)

  /** Convert BigQuery `DATE` string to `LocalDate`. */
  def parse(date: String): LocalDate = LocalDate.parse(date, Formatter)

  // For BigQueryType macros only, do not use directly
  def parse(date: Any): LocalDate = date match {
    case d: Int => new LocalDate(0, DateTimeZone.UTC).plusDays(d)
    case _      => parse(date.toString)
  }
}

/** Utility for BigQuery `TIME` type. */
object Time {
  // [H]H:[M]M:[S]S[.DDDDDD]
  private[this] val Formatter =
    DateTimeFormat.forPattern("HH:mm:ss.SSSSSS").withZoneUTC()
  private[this] val Parser = new DateTimeFormatterBuilder()
    .append(DateTimeFormat.forPattern("HH:mm:ss").getParser)
    .appendOptional(DateTimeFormat.forPattern(".SSSSSS").getParser)
    .toFormatter
    .withZoneUTC()

  /** Convert `LocalTime` to BigQuery `TIME` string. */
  def apply(time: LocalTime): String = Formatter.print(time)

  /** Convert BigQuery `TIME` string to `LocalTime`. */
  def parse(time: String): LocalTime = Parser.parseLocalTime(time)

  // For BigQueryType macros only, do not use directly
  def parse(time: Any): LocalTime = time match {
    case t: Long => new LocalTime(t / 1000, DateTimeZone.UTC)
    case _       => parse(time.toString)
  }
}

/** Utility for BigQuery `DATETIME` type. */
object DateTime {
  // YYYY-[M]M-[D]D[( |T)[H]H:[M]M:[S]S[.DDDDDD]]
  private[this] val Formatter =
    DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS")

  private[this] val Parser = new DateTimeFormatterBuilder()
    .append(DateTimeFormat.forPattern("yyyy-MM-dd"))
    .appendOptional(
      new DateTimeFormatterBuilder()
        .append(DateTimeFormat.forPattern(" HH:mm:ss").getParser)
        .appendOptional(DateTimeFormat.forPattern(".SSSSSS").getParser)
        .toParser
    )
    .appendOptional(
      new DateTimeFormatterBuilder()
        .append(DateTimeFormat.forPattern("'T'HH:mm:ss").getParser)
        .appendOptional(DateTimeFormat.forPattern(".SSSSSS").getParser)
        .toParser
    )
    .toFormatter
    .withZoneUTC()

  /** Convert `LocalDateTime` to BigQuery `DATETIME` string. */
  def apply(datetime: LocalDateTime): String = Formatter.print(datetime)

  /** Convert BigQuery `DATETIME` string to `LocalDateTime`. */
  def parse(datetime: String): LocalDateTime =
    Parser.parseLocalDateTime(datetime)
}

/** Scala wrapper for [[com.google.api.services.bigquery.model.TimePartitioning]]. */
case class TimePartitioning(
  `type`: String,
  field: String = null,
  expirationMs: Long = 0,
  requirePartitionFilter: Boolean = false
) {
  def asJava: GTimePartitioning = {
    var p = new GTimePartitioning()
      .setType(`type`)
      .setRequirePartitionFilter(requirePartitionFilter)
    if (field != null) p = p.setField(field)
    if (expirationMs > 0) p = p.setExpirationMs(expirationMs)
    p
  }
}

object Numeric {
  val MaxNumericPrecision = 38
  val MaxNumericScale = 9

  private[this] val DecimalConverter = new DecimalConversion
  private[this] val DecimalLogicalType = LogicalTypes.decimal(MaxNumericPrecision, MaxNumericScale)

  def apply(value: String): BigDecimal = apply(BigDecimal(value))

  def apply(value: BigDecimal): BigDecimal = {
    // NUMERIC's max scale is 9, precision is 38
    val scaled = if (value.scale > MaxNumericScale) {
      value.setScale(MaxNumericScale, scala.math.BigDecimal.RoundingMode.HALF_UP)
    } else {
      value
    }
    require(
      scaled.precision <= MaxNumericPrecision,
      s"max allowed precision is $MaxNumericPrecision"
    )

    BigDecimal(scaled.toString, new MathContext(MaxNumericPrecision))
  }

  // For BigQueryType macros only, do not use directly
  def parse(value: Any): BigDecimal = value match {
    case b: ByteBuffer => DecimalConverter.fromBytes(b, null, DecimalLogicalType)
    case _             => apply(value.toString)
  }
}
