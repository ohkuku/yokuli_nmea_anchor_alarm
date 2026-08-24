package com.yokuli.anchorwatch.map.style

/** The only three mutually-exclusive base-map choices exposed by Anchor Watch. */
enum class BaseMapStyle(val persistedValue: Int) {
    STANDARD(1),
    SATELLITE(2),
    NAUTICAL(3);

    companion object {
        fun fromPersisted(value: Int): BaseMapStyle = entries.firstOrNull {
            it.persistedValue == value
        } ?: STANDARD
    }
}

enum class GoogleBaseMapKind { NORMAL, SATELLITE }

data class BaseMapRenderPolicy(
    val googleBaseMap: GoogleBaseMapKind,
    val applyNauticalStyle: Boolean,
    val showSeamarks: Boolean,
)

/** Pure policy kept outside Compose so switching and reset behaviour is testable. */
object MapStylePolicy {
    fun forStyle(style: BaseMapStyle): BaseMapRenderPolicy = when (style) {
        BaseMapStyle.STANDARD -> BaseMapRenderPolicy(GoogleBaseMapKind.NORMAL, false, false)
        BaseMapStyle.SATELLITE -> BaseMapRenderPolicy(GoogleBaseMapKind.SATELLITE, false, false)
        BaseMapStyle.NAUTICAL -> BaseMapRenderPolicy(GoogleBaseMapKind.NORMAL, true, true)
    }
}
