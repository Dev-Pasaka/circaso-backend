package com.circaso.infrastructure.impl.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import net.logstash.logback.composite.AbstractFieldJsonProvider
import net.logstash.logback.composite.JsonWritingUtils
import tools.jackson.core.JsonGenerator
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The conversion words declared in logback.xml: %lvl, %rid, %mod, %line.

private val formatter by lazy { LineFormatter(Palette.detected) }

private val timeFormat =
    DateTimeFormatter
        .ofPattern(
            "HH:mm:ss.SSS",
        ).withZone(ZoneId.systemDefault())

/** The console time, `18:07:12.347` in local time, dim. Logback's own `%gray` ignores whether stdout is a terminal. */
class DimTime : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = Palette.detected.gray(timeFormat.format(event.instant))
}

class LevelColor : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = formatter.level(event)
}

class ShortRequestId : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = formatter.requestId(event)
}

class ModuleName : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = formatter.module(event)
}

class ColoredLine : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = formatter.line(event)
}

/** The JSON format's `module` field, from the logger name `com.circaso.<module>…` (a Loki label). */
class ModuleJsonProvider : AbstractFieldJsonProvider<ILoggingEvent>() {
    init {
        fieldName = "module"
    }

    override fun writeTo(generator: JsonGenerator, event: ILoggingEvent) {
        JsonWritingUtils.writeStringField(generator, fieldName, LineFormatter.moduleOf(event.loggerName))
    }
}
