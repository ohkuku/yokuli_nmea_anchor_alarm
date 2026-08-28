package com.yokuli.anchorwatch.map

import com.google.maps.android.compose.MapUiSettings

enum class MarineMapContext{ANCHOR_WATCH,SAIL_PREVIEW,LIVE_TRIP,TRIP_HISTORY,ANCHORAGE_DETAIL}

data class MarineMapCapabilities(
    val interactive:Boolean,
    val ruler:Boolean=false,
    val followVessel:Boolean=false,
    val fitContent:Boolean=false,
    val layerPicker:Boolean=false,
    val selectedDetails:Boolean=false,
)

object MarineMapPolicy{
    fun capabilities(context:MarineMapContext)=when(context){
        MarineMapContext.SAIL_PREVIEW->MarineMapCapabilities(interactive=false)
        MarineMapContext.LIVE_TRIP->MarineMapCapabilities(true,true,true,true,true,true)
        MarineMapContext.TRIP_HISTORY->MarineMapCapabilities(true,true,false,true,true,true)
        MarineMapContext.ANCHORAGE_DETAIL->MarineMapCapabilities(true,true,true,true,true,true)
        // Anchor Watch keeps its existing safety-owned controls. This shared
        // policy is additive and must not become the owner of alarm state.
        MarineMapContext.ANCHOR_WATCH->MarineMapCapabilities(true,true,true,true,true,true)
    }

    fun uiSettings(context:MarineMapContext):MapUiSettings{
        val interactive=capabilities(context).interactive
        return MapUiSettings(
            compassEnabled=interactive,mapToolbarEnabled=false,myLocationButtonEnabled=false,zoomControlsEnabled=false,
            scrollGesturesEnabled=interactive,zoomGesturesEnabled=interactive,rotationGesturesEnabled=interactive,tiltGesturesEnabled=interactive,
        )
    }
}
