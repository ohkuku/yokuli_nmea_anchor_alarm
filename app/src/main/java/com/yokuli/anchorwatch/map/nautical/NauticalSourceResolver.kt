package com.yokuli.anchorwatch.map.nautical

import com.yokuli.anchorwatch.map.style.BaseMapStyle

enum class NauticalPrimarySource { USER_MBTILES, STANDARD_NAUTICAL }

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
        userChartEnabled:Boolean,
        userChartName:String?,
    ):NauticalSourceState{
        val active=style==BaseMapStyle.NAUTICAL
        val useUser=active&&userChartInstalled&&userChartEnabled
        return NauticalSourceState(
            active=active,
            primary=if(useUser)NauticalPrimarySource.USER_MBTILES else NauticalPrimarySource.STANDARD_NAUTICAL,
            userChartName=userChartName.takeIf{useUser},
            userChartEnabled=useUser,
        )
    }
}
