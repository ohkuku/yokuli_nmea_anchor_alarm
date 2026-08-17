package com.yokuli.anchorwatch.data.linz

import com.yokuli.anchorwatch.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.cos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class LinzWfsResult(
    val soundings: List<HydroFeature>,
    val areas: List<HydroFeature>,
    val contours: List<HydroFeature>,
    val requestCount: Int,
    val lastHttpCode: Int?,
    val errors: List<String>,
)

internal data class LinzHttpResponse(val code: Int, val body: String)

/** Small injectable boundary: unit tests never need a live LINZ service or a real key. */
open class LinzWfsHttpTransport @Inject constructor() {
    internal open suspend fun get(url: URL, accept: String, maxBytes: Int): LinzHttpResponse =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", accept)
                    setRequestProperty("User-Agent", "Anchor-Watch/${BuildConfig.VERSION_NAME}")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        if (output.size() > maxBytes) throw IOException("LINZ response too large")
                    }
                    output.toByteArray()
                } ?: ByteArray(0)
                LinzHttpResponse(code, bytes.toString(Charsets.UTF_8))
            } finally {
                connection?.disconnect()
            }
        }
}

internal object LinzWfsRequestBuilder {
    fun describe(serviceRoot: String, layerIds: List<String>): URL = url(
        serviceRoot,
        linkedMapOf(
            "service" to "WFS",
            "version" to "2.0.0",
            "request" to "DescribeFeatureType",
            "typeNames" to layerIds.joinToString(",") { "layer-$it" },
        ),
    )

    fun features(
        serviceRoot: String,
        layerIds: List<String>,
        geometryProperty: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
    ): URL {
        require(geometryProperty.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*")))
        val latDelta = radiusMeters / 111_320.0
        val lonDelta = radiusMeters / (111_320.0 * cos(Math.toRadians(latitude)).coerceAtLeast(.15))
        // LINZ WFS 2.0.0 advertises EPSG:4326 in latitude/longitude axis order.
        val bbox = String.format(
            Locale.US,
            "bbox(%s,%.8f,%.8f,%.8f,%.8f,'EPSG:4326')",
            geometryProperty,
            latitude - latDelta,
            longitude - lonDelta,
            latitude + latDelta,
            longitude + lonDelta,
        )
        return url(
            serviceRoot,
            linkedMapOf(
                "service" to "WFS",
                "version" to "2.0.0",
                "request" to "GetFeature",
                "typeNames" to layerIds.joinToString(",") { "layer-$it" },
                "count" to "400",
                "srsName" to "EPSG:4326",
                "outputFormat" to "json",
                "cql_filter" to layerIds.joinToString(";") { bbox },
            ),
        )
    }

    private fun url(root: String, parameters: Map<String, String>): URL {
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return URL("$root?$query")
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

/** Parses the actual geometry property advertised by each LINZ layer. */
internal object LinzFeatureTypeSchemaParser {
    fun geometryProperties(xml: String, expectedLayerIds: List<String>): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            setExpandEntityReferences(false)
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            // Android's XMLConstants omits these two desktop-JAXP fields, but
            // DocumentBuilderFactory accepts their standard property URIs.
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val geometryByType = mutableMapOf<String, String>()
        val complexTypes = document.getElementsByTagNameNS("*", "complexType")
        for (index in 0 until complexTypes.length) {
            val complexType = complexTypes.item(index) as? Element ?: continue
            val typeName = complexType.getAttribute("name").substringAfter(':')
            val elements = complexType.getElementsByTagNameNS("*", "element")
            for (elementIndex in 0 until elements.length) {
                val element = elements.item(elementIndex) as? Element ?: continue
                val propertyName = element.getAttribute("name")
                val propertyType = element.getAttribute("type")
                if (propertyName.isNotBlank() && isGeometryType(propertyType)) {
                    geometryByType[typeName] = propertyName
                    break
                }
            }
        }

        val result = mutableMapOf<String, String>()
        val expected = expectedLayerIds.toSet()
        val rootChildren = document.documentElement.childNodes
        for (index in 0 until rootChildren.length) {
            val element = rootChildren.item(index) as? Element ?: continue
            if (element.localName != "element") continue
            val layerId = element.getAttribute("name").removePrefix("layer-")
            if (layerId !in expected) continue
            val typeName = element.getAttribute("type").substringAfter(':')
            geometryByType[typeName]?.let { result[layerId] = it }
        }
        if (result.size != expected.size && geometryByType.values.distinct().size == 1) {
            val onlyProperty = geometryByType.values.single()
            expected.forEach { result.putIfAbsent(it, onlyProperty) }
        }
        return result
    }

    private fun isGeometryType(type: String): Boolean {
        val local = type.substringAfter(':')
        return type.startsWith("gml:") && local.endsWith("PropertyType") && listOf(
            "Geometry", "Point", "Curve", "LineString", "Surface", "Polygon", "Multi",
        ).any(local::contains)
    }
}

@Singleton
open class LinzWfsClient @Inject constructor(
    private val transport: LinzWfsHttpTransport,
) {
    /** Test subclasses can replace [query] without participating in DI. */
    protected constructor() : this(LinzWfsHttpTransport())
    val soundingLayerIds = ids(BuildConfig.LINZ_SOUNDING_LAYER_IDS)
    val areaLayerIds = ids(BuildConfig.LINZ_DEPTH_AREA_LAYER_IDS)
    val contourLayerIds = ids(BuildConfig.LINZ_DEPTH_CONTOUR_LAYER_IDS)
    open val allLayerIds = soundingLayerIds + areaLayerIds + contourLayerIds
    open val configured get() = BuildConfig.LINZ_API_KEY.isNotBlank() && allLayerIds.isNotEmpty()
    private val geometryPropertyCache = ConcurrentHashMap<String, String>()

    open suspend fun query(latitude: Double, longitude: Double): LinzWfsResult = coroutineScope {
        if (!configured) throw IllegalStateException("LINZ vector depth is not configured")
        val sounding = async { fetch(soundingLayerIds, latitude, longitude, 250.0, HydroFeatureKind.SOUNDING) }
        val area = async { fetch(areaLayerIds, latitude, longitude, 20.0, HydroFeatureKind.DEPTH_AREA) }
        val contour = async { fetch(contourLayerIds, latitude, longitude, 100.0, HydroFeatureKind.DEPTH_CONTOUR) }
        val results = listOf(sounding.await(), area.await(), contour.await())
        if (results.all { it.error != null }) throw IOException("LINZ WFS unavailable")
        LinzWfsResult(
            soundings = results[0].features,
            areas = results[1].features,
            contours = results[2].features,
            requestCount = results.sumOf(FetchResult::requestCount),
            lastHttpCode = results.mapNotNull(FetchResult::code).lastOrNull(),
            errors = results.mapNotNull(FetchResult::error),
        )
    }

    private data class FetchResult(
        val features: List<HydroFeature>,
        val code: Int?,
        val error: String?,
        val requestCount: Int,
    )

    private suspend fun fetch(
        layers: List<String>,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        kind: HydroFeatureKind,
    ): FetchResult {
        if (layers.isEmpty()) return FetchResult(emptyList(), null, null, 0)
        var requestCount = 0
        return try {
            val missing = layers.filterNot(geometryPropertyCache::containsKey)
            if (missing.isNotEmpty()) {
                val response = transport.get(
                    LinzWfsRequestBuilder.describe(serviceRoot(), missing),
                    "application/xml,text/xml",
                    1_000_000,
                )
                requestCount++
                if (response.code !in 200..299) {
                    return FetchResult(emptyList(), response.code, "DescribeFeatureType HTTP ${response.code}", requestCount)
                }
                val properties = LinzFeatureTypeSchemaParser.geometryProperties(response.body, missing)
                val unresolved = missing - properties.keys
                if (unresolved.isNotEmpty()) {
                    return FetchResult(emptyList(), response.code, "Geometry schema unavailable", requestCount)
                }
                geometryPropertyCache.putAll(properties)
            }

            val features = mutableListOf<HydroFeature>()
            var lastCode: Int? = null
            val errors = mutableListOf<String>()
            layers.groupBy { geometryPropertyCache.getValue(it) }.forEach { (geometryProperty, groupedLayers) ->
                val response = transport.get(
                    LinzWfsRequestBuilder.features(
                        serviceRoot(), groupedLayers, geometryProperty, latitude, longitude, radiusMeters,
                    ),
                    "application/json",
                    4_000_000,
                )
                requestCount++
                lastCode = response.code
                if (response.code in 200..299) {
                    features += LinzHydroFeatureParser.parse(response.body, kind)
                } else {
                    errors += "HTTP ${response.code}"
                }
            }
            FetchResult(features, lastCode, errors.takeIf { it.isNotEmpty() }?.joinToString(), requestCount)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            FetchResult(emptyList(), null, error.javaClass.simpleName, requestCount)
        }
    }

    private fun serviceRoot() = "https://data.linz.govt.nz/services;key=${BuildConfig.LINZ_API_KEY}/wfs"
    private fun ids(value: String) = value.split('|').map(String::trim).filter { candidate ->
        candidate.isNotEmpty() && candidate.all(Char::isDigit)
    }
}
