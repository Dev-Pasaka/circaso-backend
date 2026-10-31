package com.circaso.assistant.data.blocks

import com.circaso.assistant.domain.ports.BlockKind
import com.circaso.assistant.domain.ports.ReplyBlocks
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The blocks' documented shapes (docs/mobile-integration.md → Asha → Rich replies), read leniently from what the model
 * wrote and written back strictly. Repairs are the safe ones only: case, trimming, caps, dropping the points, rows,
 * nodes or edges that don't read. Anything without its essentials is dropped, never guessed.
 */
class JsonReplyBlocks : ReplyBlocks {
    override fun clean(kind: BlockKind, body: String): String? {
        if (body.length > MAX_BODY_CHARS) return null
        return runCatching {
            when (kind) {
                BlockKind.CHART -> chart(body)
                BlockKind.STATEMENT -> statement(body)
                BlockKind.DIAGRAM -> diagram(body)
            }
        }.getOrNull()
    }

    private fun chart(body: String): String? {
        val raw = lenient.decodeFromString(RawChart.serializer(), body)
        val type =
            raw.type
                ?.trim()
                ?.lowercase()
                ?.takeIf { it in CHART_TYPES } ?: "bar"
        val series =
            raw.series
                .orEmpty()
                .mapNotNull { it.clean(allowNegative = type != "pie") }
                .take(if (type == "pie") 1 else MAX_SERIES)
        if (series.isEmpty()) return null
        val chart =
            Chart(
                type = type,
                title =
                    raw.title
                        ?.trim()
                        ?.take(MAX_TITLE)
                        .orEmpty(),
                unit =
                    raw.unit
                        ?.trim()
                        ?.take(MAX_UNIT)
                        ?.takeIf { it.isNotEmpty() },
                series = series,
            )
        return strict.encodeToString(Chart.serializer(), chart)
    }

    private fun RawSeries.clean(allowNegative: Boolean): Series? {
        val kept =
            points
                .orEmpty()
                .mapNotNull { point ->
                    val label =
                        point.label
                            ?.trim()
                            ?.take(MAX_LABEL)
                            ?.takeIf { it.isNotEmpty() }
                    val value = point.value?.takeIf { it.isFinite() && (allowNegative || it >= 0) }
                    if (label == null || value == null) null else Point(label, value)
                }.take(MAX_POINTS)
        if (kept.isEmpty()) return null
        return Series(name?.trim()?.take(MAX_LABEL).orEmpty(), kept)
    }

    private fun statement(body: String): String? {
        val raw = lenient.decodeFromString(RawStatement.serializer(), body)
        val rows =
            raw.rows
                .orEmpty()
                .mapNotNull { it.clean() }
                .take(MAX_ROWS)
        val totals =
            raw.totals
                .orEmpty()
                .mapNotNull { it.clean() }
                .take(MAX_TOTALS)
        if (rows.isEmpty() && totals.isEmpty()) return null
        val statement =
            Statement(
                title =
                    raw.title
                        ?.trim()
                        ?.take(MAX_TITLE)
                        ?.takeIf { it.isNotEmpty() } ?: "Statement",
                period = raw.period?.let { Period(dateOf(it.from), dateOf(it.to)) }?.takeIf { it.isKnown },
                currency =
                    raw.currency
                        ?.trim()
                        ?.uppercase()
                        ?.takeIf { CURRENCY.matches(it) },
                rows = rows,
                totals = totals,
            )
        return strict.encodeToString(Statement.serializer(), statement)
    }

    private fun RawStatementLine.clean(): StatementLine? {
        val day = dateOf(date) ?: return null
        val amount = amountOf(amount) ?: return null
        val coin = asset?.trim()?.uppercase()?.takeIf { ASSET.matches(it) } ?: return null
        return StatementLine(
            date = day,
            description = description?.trim()?.take(MAX_DESCRIPTION).orEmpty(),
            direction = direction?.trim()?.uppercase()?.takeIf { it == "IN" || it == "OUT" },
            amount = amount,
            asset = coin,
            fiat = amountOf(fiat),
            status = status?.trim()?.uppercase()?.takeIf { it in STATUSES },
            reference = reference?.trim()?.take(MAX_REFERENCE)?.takeIf { it.isNotEmpty() },
        )
    }

    private fun RawTotal.clean(): Total? {
        val text = label?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() } ?: return null
        val sum = amountOf(amount) ?: return null
        return Total(text, sum, asset?.trim()?.uppercase()?.takeIf { ASSET.matches(it) }, amountOf(fiat))
    }

    private fun diagram(body: String): String? {
        val raw = lenient.decodeFromString(RawDiagram.serializer(), body)
        val nodes =
            raw.nodes
                .orEmpty()
                .mapNotNull { node ->
                    val id =
                        node.id
                            ?.trim()
                            ?.take(MAX_ID)
                            ?.takeIf { it.isNotEmpty() }
                    val label =
                        node.label
                            ?.trim()
                            ?.take(MAX_LABEL)
                            ?.takeIf { it.isNotEmpty() }
                    if (id == null || label == null) null else Node(id, label)
                }.distinctBy { it.id }
                .take(MAX_NODES)
        val ids = nodes.map { it.id }.toSet()
        val edges =
            raw.edges
                .orEmpty()
                .mapNotNull { edge ->
                    val from = edge.from?.trim()?.takeIf { it in ids }
                    val to = edge.to?.trim()?.takeIf { it in ids }
                    if (from == null || to == null || from == to) {
                        null
                    } else {
                        Edge(
                            from,
                            to,
                            edge.label
                                ?.trim()
                                ?.take(MAX_LABEL)
                                ?.takeIf { it.isNotEmpty() },
                        )
                    }
                }.take(MAX_EDGES)
        if (nodes.size < 2 || edges.isEmpty()) return null
        val diagram =
            Diagram(
                raw.title
                    ?.trim()
                    ?.take(MAX_TITLE)
                    .orEmpty(),
                nodes,
                edges,
            )
        return strict.encodeToString(Diagram.serializer(), diagram)
    }

    private fun dateOf(raw: String?): String? =
        raw
            ?.trim()
            ?.take(DATE_LENGTH)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.toString()

    /** A non-negative decimal written plainly ("1250.5"); thousands separators the model added are removed. */
    private fun amountOf(raw: String?): String? =
        raw
            ?.trim()
            ?.replace(",", "")
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?.takeIf { it.signum() >= 0 }
            ?.stripTrailingZeros()
            ?.toPlainString()

    private companion object {
        const val MAX_BODY_CHARS = 60_000
        const val MAX_SERIES = 6
        const val MAX_POINTS = 60
        const val MAX_ROWS = 200
        const val MAX_TOTALS = 10
        const val MAX_NODES = 8
        const val MAX_EDGES = 12
        const val MAX_TITLE = 80
        const val MAX_LABEL = 40
        const val MAX_UNIT = 12
        const val MAX_ID = 24
        const val MAX_DESCRIPTION = 120
        const val MAX_REFERENCE = 64
        const val DATE_LENGTH = 10
        val CHART_TYPES = setOf("bar", "line", "pie")
        val STATUSES = setOf("SUBMITTED", "CONFIRMED", "FAILED", "PENDING")
        val CURRENCY = Regex("^[A-Z]{3}$")
        val ASSET = Regex("^[A-Z0-9]{2,10}$")

        val lenient =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }
        val strict =
            Json {
                encodeDefaults = true
                explicitNulls = false
            }
    }
}

// ---- what the model wrote: every field optional, numbers and amounts read leniently ----

@Serializable
private data class RawChart(
    val type: String? = null,
    val title: String? = null,
    val unit: String? = null,
    val series: List<RawSeries>? = null,
)

@Serializable
private data class RawSeries(val name: String? = null, val points: List<RawPoint>? = null)

@Serializable
private data class RawPoint(
    @Serializable(with = LenientText::class) val label: String? = null,
    @Serializable(with = LenientNumber::class) val value: Double? = null,
)

@Serializable
private data class RawStatement(
    val title: String? = null,
    val period: RawPeriod? = null,
    val currency: String? = null,
    val rows: List<RawStatementLine>? = null,
    val totals: List<RawTotal>? = null,
)

@Serializable
private data class RawPeriod(val from: String? = null, val to: String? = null)

@Serializable
private data class RawStatementLine(
    val date: String? = null,
    val description: String? = null,
    val direction: String? = null,
    @Serializable(with = LenientText::class) val amount: String? = null,
    val asset: String? = null,
    @Serializable(with = LenientText::class) val fiat: String? = null,
    val status: String? = null,
    val reference: String? = null,
)

@Serializable
private data class RawTotal(
    val label: String? = null,
    @Serializable(with = LenientText::class) val amount: String? = null,
    val asset: String? = null,
    @Serializable(with = LenientText::class) val fiat: String? = null,
)

@Serializable
private data class RawDiagram(
    val title: String? = null,
    val nodes: List<RawNode>? = null,
    val edges: List<RawEdge>? = null,
)

@Serializable
private data class RawNode(
    @Serializable(with = LenientText::class) val id: String? = null,
    val label: String? = null,
)

@Serializable
private data class RawEdge(
    @Serializable(with = LenientText::class) val from: String? = null,
    @Serializable(with = LenientText::class) val to: String? = null,
    val label: String? = null,
)

// ---- what the app gets: the documented shapes ----

@Serializable
private data class Chart(
    val type: String,
    val title: String,
    val unit: String? = null,
    val series: List<Series>,
)

@Serializable
private data class Series(val name: String, val points: List<Point>)

@Serializable
private data class Point(val label: String, val value: Double)

@Serializable
private data class Statement(
    val title: String,
    val period: Period? = null,
    val currency: String? = null,
    val rows: List<StatementLine>,
    val totals: List<Total>,
)

@Serializable
private data class Period(val from: String? = null, val to: String? = null) {
    val isKnown: Boolean get() = from != null || to != null
}

@Serializable
private data class StatementLine(
    val date: String,
    val description: String,
    val direction: String? = null,
    val amount: String,
    val asset: String,
    val fiat: String? = null,
    val status: String? = null,
    val reference: String? = null,
)

@Serializable
private data class Total(
    val label: String,
    val amount: String,
    val asset: String? = null,
    val fiat: String? = null,
)

@Serializable
private data class Diagram(val title: String, val nodes: List<Node>, val edges: List<Edge>)

@Serializable
private data class Node(val id: String, val label: String)

@Serializable
private data class Edge(val from: String, val to: String, val label: String? = null)

/** A number the model may have written as a number or as text ("1,234.5"); null when it isn't one. */
private object LenientNumber : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNumber", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Double? =
        decoder
            .decodeString()
            .trim()
            .replace(",", "")
            .toDoubleOrNull()

    override fun serialize(encoder: Encoder, value: Double?) = encoder.encodeString(value.toString())
}

/** Text the model may have written as a string or a bare number (an amount, an id); kept as its text. */
private object LenientText : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientText", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? = decoder.decodeString()

    override fun serialize(encoder: Encoder, value: String?) = encoder.encodeString(value.orEmpty())
}
