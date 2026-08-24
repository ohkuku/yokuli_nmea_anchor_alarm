package com.yokuli.anchorwatch.map.nautical

import com.yokuli.anchorwatch.map.style.BaseMapStyle

enum class NauticalPrimarySource { USER_MBTILES, STANDARD_NAUTICAL }
enum class NauticalSourcePreference { DEFAULT_ONLINE, USER_MBTILES }

data class MapOverlayPreferences(
    val linzNzChartEnabled:Boolean,
    val linzNzChartOpacity:Double,
    val personalSonarEnabled:Boolean,
    val showCurrentLinzDepth:Boolean,
    val showCurrentPersonalDepth:Boolean,
)

data class NauticalSourceState(
    val active:Boolean,
    val primary:NauticalPrimarySource,
    val userChartName:String?=null,
    val userChartEnabled:Boolean=false,
)

object NauticalSourceResolver {
    fun resolve(
        style:BaseMapStyle,
        userChartInstalled:Boolean,
        preference:NauticalSourcePreference,
        userChartName:String?,
    ):NauticalSourceState{
        val active=style==BaseMapStyle.NAUTICAL
        val useUser=active&&userChartInstalled&&preference==NauticalSourcePreference.USER_MBTILES
        return NauticalSourceState(
            active=active,
            primary=if(useUser)NauticalPrimarySource.USER_MBTILES else NauticalPrimarySource.STANDARD_NAUTICAL,
            userChartName=userChartName.takeIf{useUser},
            userChartEnabled=useUser,
        )
    }

    fun resolve(style:BaseMapStyle,userChartInstalled:Boolean,userChartEnabled:Boolean,userChartName:String?)=resolve(style,userChartInstalled,if(userChartEnabled)NauticalSourcePreference.USER_MBTILES else NauticalSourcePreference.DEFAULT_ONLINE,userChartName)
}
