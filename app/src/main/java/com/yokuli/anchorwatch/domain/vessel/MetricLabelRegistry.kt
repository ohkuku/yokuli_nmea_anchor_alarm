package com.yokuli.anchorwatch.domain.vessel

import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.localization.resolved

data class MetricLabel(val acronym:String,val english:String,val simplifiedChinese:String)

/** One naming authority for Data, Sail, reports and exports. Acronyms remain
 * standard marine abbreviations while descriptive names may be localized. */
object MetricLabelRegistry{
    private val labels=mapOf(
        InstrumentTileId.SOG to MetricLabel("SOG","Speed over ground","对地航速"),
        InstrumentTileId.COG to MetricLabel("COG","Course over ground","对地航向"),
        InstrumentTileId.HEADING to MetricLabel("HDG/HDT","Vessel heading","船首向"),
        InstrumentTileId.BOAT_SPEED to MetricLabel("STW","Speed through water","对水航速"),
        InstrumentTileId.DEPTH to MetricLabel("DPT/DBT","Water depth","水深"),
        InstrumentTileId.UKC to MetricLabel("UKC","Under-keel clearance","龙骨下净空"),
        InstrumentTileId.APPARENT_WIND_SPEED to MetricLabel("AWS","Apparent wind speed","视风速"),
        InstrumentTileId.APPARENT_WIND_ANGLE to MetricLabel("AWA","Apparent wind angle","视风角"),
        InstrumentTileId.TRUE_WIND_SPEED to MetricLabel("TWS","True wind speed","真风速"),
        InstrumentTileId.TRUE_WIND_DIRECTION to MetricLabel("TWD","True wind direction","真风向"),
        InstrumentTileId.TRUE_WIND_ANGLE to MetricLabel("TWA","True wind angle","真风角"),
        InstrumentTileId.RATE_OF_TURN to MetricLabel("ROT","Rate of turn","转向率"),
        InstrumentTileId.VMG to MetricLabel("VMG","Velocity made good","有效迎风航速"),
        InstrumentTileId.VMC to MetricLabel("VMC","Velocity made good to waypoint","航点有效航速"),
        InstrumentTileId.POSITION to MetricLabel("GPS","Position","船位"),
        InstrumentTileId.HEEL to MetricLabel("HEEL","Heel angle","横倾角"),
        InstrumentTileId.PITCH to MetricLabel("PITCH","Pitch angle","纵倾角"),
        InstrumentTileId.ROLL_RATE to MetricLabel("ROLL","Roll rate","横摇角速度"),
        InstrumentTileId.PITCH_RATE to MetricLabel("PITCH RATE","Pitch rate","纵摇角速度"),
        InstrumentTileId.ROLL_PERIOD to MetricLabel("ROLL PERIOD","Roll period","横摇周期"),
        InstrumentTileId.MOTION_SCORE to MetricLabel("MOTION","Motion score","运动评分"),
        InstrumentTileId.IMPACT_COUNT to MetricLabel("IMPACT","Impact candidates","冲击候选"),
        InstrumentTileId.PRESSURE to MetricLabel("BARO","Barometric pressure","气压"),
        InstrumentTileId.PRESSURE_TREND_1H to MetricLabel("BARO 1H","One-hour pressure trend","一小时气压趋势"),
        InstrumentTileId.PRESSURE_TREND_3H to MetricLabel("BARO 3H","Three-hour pressure trend","三小时气压趋势"),
        InstrumentTileId.PRESSURE_TREND_6H to MetricLabel("BARO 6H","Six-hour pressure trend","六小时气压趋势"),
        InstrumentTileId.RUDDER_ANGLE to MetricLabel("RUDDER","Rudder angle","舵角"),
        InstrumentTileId.WATER_TEMPERATURE to MetricLabel("WATER TEMP","Water temperature","水温"),
        InstrumentTileId.AIR_TEMPERATURE to MetricLabel("AIR TEMP","Air temperature","气温"),
        InstrumentTileId.CURRENT_SET to MetricLabel("SET","Current set","流向"),
        InstrumentTileId.CURRENT_DRIFT to MetricLabel("DRIFT","Current drift","流速"),
        InstrumentTileId.CROSS_TRACK_ERROR to MetricLabel("XTE","Cross-track error","横向偏差"),
        InstrumentTileId.WAYPOINT_BEARING to MetricLabel("BTW","Bearing to waypoint","航点方位"),
        InstrumentTileId.WAYPOINT_DISTANCE to MetricLabel("DTW","Distance to waypoint","航点距离"),
        InstrumentTileId.TOTAL_LOG to MetricLabel("LOG","Total log","总航程计"),
        InstrumentTileId.TRIP_LOG to MetricLabel("TRIP LOG","Trip log","分段航程计"),
    )
    fun get(id:InstrumentTileId)=labels[id]?:MetricLabel(id.name,id.name.lowercase().replace('_',' '),id.name)
    fun get(id:VesselMetricId)=metricLabels[id]?:MetricLabel(id.name,id.name.lowercase().replace('_',' '),id.name)
    fun localizedName(id:InstrumentTileId,language:AppLanguage):String{
        val label=get(id)
        return when(language.resolved()){
            AppLanguage.JAPANESE->japanese[id]?:label.english
            AppLanguage.FRENCH->french[id]?:label.english
            AppLanguage.SPANISH->spanish[id]?:label.english
            else->localized(language,label.english,label.simplifiedChinese)
        }
    }
    fun localizedName(id:VesselMetricId,language:AppLanguage):String{
        val tile=metricTiles[id]
        if(tile!=null)return localizedName(tile,language)
        val label=get(id)
        return localized(language,label.english,label.simplifiedChinese)
    }

    private val metricTiles=mapOf(
        VesselMetricId.POSITION to InstrumentTileId.POSITION,VesselMetricId.SOG to InstrumentTileId.SOG,VesselMetricId.COG to InstrumentTileId.COG,
        VesselMetricId.HEADING_TRUE to InstrumentTileId.HEADING,VesselMetricId.SPEED_THROUGH_WATER to InstrumentTileId.BOAT_SPEED,VesselMetricId.DEPTH to InstrumentTileId.DEPTH,VesselMetricId.UKC to InstrumentTileId.UKC,
        VesselMetricId.APPARENT_WIND_ANGLE to InstrumentTileId.APPARENT_WIND_ANGLE,VesselMetricId.APPARENT_WIND_SPEED to InstrumentTileId.APPARENT_WIND_SPEED,VesselMetricId.TRUE_WIND_ANGLE to InstrumentTileId.TRUE_WIND_ANGLE,VesselMetricId.TRUE_WIND_SPEED to InstrumentTileId.TRUE_WIND_SPEED,VesselMetricId.TRUE_WIND_DIRECTION to InstrumentTileId.TRUE_WIND_DIRECTION,
        VesselMetricId.RATE_OF_TURN to InstrumentTileId.RATE_OF_TURN,VesselMetricId.RUDDER_ANGLE to InstrumentTileId.RUDDER_ANGLE,VesselMetricId.HEEL to InstrumentTileId.HEEL,VesselMetricId.PITCH to InstrumentTileId.PITCH,VesselMetricId.ROLL_RATE to InstrumentTileId.ROLL_RATE,VesselMetricId.PITCH_RATE to InstrumentTileId.PITCH_RATE,VesselMetricId.PRESSURE to InstrumentTileId.PRESSURE,
        VesselMetricId.WATER_TEMPERATURE to InstrumentTileId.WATER_TEMPERATURE,VesselMetricId.AIR_TEMPERATURE to InstrumentTileId.AIR_TEMPERATURE,VesselMetricId.CURRENT_SET to InstrumentTileId.CURRENT_SET,VesselMetricId.CURRENT_DRIFT to InstrumentTileId.CURRENT_DRIFT,VesselMetricId.XTE to InstrumentTileId.CROSS_TRACK_ERROR,VesselMetricId.WAYPOINT_BEARING to InstrumentTileId.WAYPOINT_BEARING,VesselMetricId.WAYPOINT_DISTANCE to InstrumentTileId.WAYPOINT_DISTANCE,VesselMetricId.TOTAL_LOG to InstrumentTileId.TOTAL_LOG,VesselMetricId.TRIP_LOG to InstrumentTileId.TRIP_LOG,VesselMetricId.VMG_WIND to InstrumentTileId.VMG,VesselMetricId.VMC_WAYPOINT to InstrumentTileId.VMC,VesselMetricId.MOTION_SCORE to InstrumentTileId.MOTION_SCORE,VesselMetricId.ROLL_PERIOD to InstrumentTileId.ROLL_PERIOD,
    )
    private val metricLabels=mapOf(
        VesselMetricId.HEADING_MAGNETIC to MetricLabel("HDG/HDM","Magnetic vessel heading","磁船首向"),
        VesselMetricId.DEVICE_HEADING_TRUE to MetricLabel("DEVICE °T","Handheld device direction","手持设备真方位"),
        VesselMetricId.DEVICE_HEADING_MAGNETIC to MetricLabel("DEVICE °M","Handheld device magnetic direction","手持设备磁方位"),
        VesselMetricId.YAW_RATE to MetricLabel("YAW RATE","Yaw rate","艏摇角速度"),
        VesselMetricId.DESTINATION_WAYPOINT to MetricLabel("WPT","Destination waypoint","目标航点"),
    )

    private val japanese=mapOf(
        InstrumentTileId.SOG to "対地速力",InstrumentTileId.COG to "対地針路",InstrumentTileId.HEADING to "船首方位",InstrumentTileId.BOAT_SPEED to "対水速力",InstrumentTileId.DEPTH to "水深",InstrumentTileId.UKC to "船底余裕",
        InstrumentTileId.APPARENT_WIND_SPEED to "見かけの風速",InstrumentTileId.APPARENT_WIND_ANGLE to "見かけの風向角",InstrumentTileId.TRUE_WIND_SPEED to "真風速",InstrumentTileId.TRUE_WIND_DIRECTION to "真風向",InstrumentTileId.TRUE_WIND_ANGLE to "真風向角",InstrumentTileId.RATE_OF_TURN to "旋回率",
        InstrumentTileId.VMG to "有効風上速力",InstrumentTileId.VMC to "ウェイポイント有効速力",InstrumentTileId.POSITION to "位置",InstrumentTileId.HEEL to "ヒール角",InstrumentTileId.PITCH to "ピッチ角",InstrumentTileId.ROLL_RATE to "ロール速度",InstrumentTileId.PITCH_RATE to "ピッチ速度",InstrumentTileId.ROLL_PERIOD to "ロール周期",InstrumentTileId.MOTION_SCORE to "動揺スコア",InstrumentTileId.IMPACT_COUNT to "衝撃候補",InstrumentTileId.PRESSURE to "気圧",InstrumentTileId.PRESSURE_TREND_1H to "1時間気圧傾向",InstrumentTileId.PRESSURE_TREND_3H to "3時間気圧傾向",InstrumentTileId.PRESSURE_TREND_6H to "6時間気圧傾向",InstrumentTileId.RUDDER_ANGLE to "舵角",InstrumentTileId.WATER_TEMPERATURE to "水温",InstrumentTileId.AIR_TEMPERATURE to "気温",InstrumentTileId.CURRENT_SET to "潮流方向",InstrumentTileId.CURRENT_DRIFT to "潮流速度",InstrumentTileId.CROSS_TRACK_ERROR to "横偏位",InstrumentTileId.WAYPOINT_BEARING to "ウェイポイント方位",InstrumentTileId.WAYPOINT_DISTANCE to "ウェイポイント距離",InstrumentTileId.TOTAL_LOG to "総航程",InstrumentTileId.TRIP_LOG to "区間航程",
    )
    private val french=mapOf(
        InstrumentTileId.SOG to "Vitesse fond",InstrumentTileId.COG to "Route fond",InstrumentTileId.HEADING to "Cap du navire",InstrumentTileId.BOAT_SPEED to "Vitesse surface",InstrumentTileId.DEPTH to "Profondeur",InstrumentTileId.UKC to "Pied de pilote",
        InstrumentTileId.APPARENT_WIND_SPEED to "Vitesse du vent apparent",InstrumentTileId.APPARENT_WIND_ANGLE to "Angle du vent apparent",InstrumentTileId.TRUE_WIND_SPEED to "Vitesse du vent vrai",InstrumentTileId.TRUE_WIND_DIRECTION to "Direction du vent vrai",InstrumentTileId.TRUE_WIND_ANGLE to "Angle du vent vrai",InstrumentTileId.RATE_OF_TURN to "Taux de giration",InstrumentTileId.VMG to "Gain au vent",InstrumentTileId.VMC to "Gain vers le waypoint",InstrumentTileId.POSITION to "Position",InstrumentTileId.HEEL to "Angle de gîte",InstrumentTileId.PITCH to "Angle de tangage",InstrumentTileId.ROLL_RATE to "Vitesse de roulis",InstrumentTileId.PITCH_RATE to "Vitesse de tangage",InstrumentTileId.ROLL_PERIOD to "Période de roulis",InstrumentTileId.MOTION_SCORE to "Indice de mouvement",InstrumentTileId.IMPACT_COUNT to "Impacts candidats",InstrumentTileId.PRESSURE to "Pression barométrique",InstrumentTileId.PRESSURE_TREND_1H to "Tendance de pression sur 1 h",InstrumentTileId.PRESSURE_TREND_3H to "Tendance de pression sur 3 h",InstrumentTileId.PRESSURE_TREND_6H to "Tendance de pression sur 6 h",InstrumentTileId.RUDDER_ANGLE to "Angle de barre",InstrumentTileId.WATER_TEMPERATURE to "Température de l’eau",InstrumentTileId.AIR_TEMPERATURE to "Température de l’air",InstrumentTileId.CURRENT_SET to "Direction du courant",InstrumentTileId.CURRENT_DRIFT to "Vitesse du courant",InstrumentTileId.CROSS_TRACK_ERROR to "Écart de route",InstrumentTileId.WAYPOINT_BEARING to "Relèvement du waypoint",InstrumentTileId.WAYPOINT_DISTANCE to "Distance au waypoint",InstrumentTileId.TOTAL_LOG to "Loch total",InstrumentTileId.TRIP_LOG to "Loch journalier",
    )
    private val spanish=mapOf(
        InstrumentTileId.SOG to "Velocidad sobre el fondo",InstrumentTileId.COG to "Rumbo sobre el fondo",InstrumentTileId.HEADING to "Proa del barco",InstrumentTileId.BOAT_SPEED to "Velocidad sobre el agua",InstrumentTileId.DEPTH to "Profundidad",InstrumentTileId.UKC to "Margen bajo la quilla",
        InstrumentTileId.APPARENT_WIND_SPEED to "Velocidad del viento aparente",InstrumentTileId.APPARENT_WIND_ANGLE to "Ángulo del viento aparente",InstrumentTileId.TRUE_WIND_SPEED to "Velocidad del viento real",InstrumentTileId.TRUE_WIND_DIRECTION to "Dirección del viento real",InstrumentTileId.TRUE_WIND_ANGLE to "Ángulo del viento real",InstrumentTileId.RATE_OF_TURN to "Velocidad de giro",InstrumentTileId.VMG to "Velocidad efectiva al viento",InstrumentTileId.VMC to "Velocidad efectiva al waypoint",InstrumentTileId.POSITION to "Posición",InstrumentTileId.HEEL to "Ángulo de escora",InstrumentTileId.PITCH to "Ángulo de cabeceo",InstrumentTileId.ROLL_RATE to "Velocidad de balance",InstrumentTileId.PITCH_RATE to "Velocidad de cabeceo",InstrumentTileId.ROLL_PERIOD to "Período de balance",InstrumentTileId.MOTION_SCORE to "Índice de movimiento",InstrumentTileId.IMPACT_COUNT to "Impactos candidatos",InstrumentTileId.PRESSURE to "Presión barométrica",InstrumentTileId.PRESSURE_TREND_1H to "Tendencia de presión de 1 h",InstrumentTileId.PRESSURE_TREND_3H to "Tendencia de presión de 3 h",InstrumentTileId.PRESSURE_TREND_6H to "Tendencia de presión de 6 h",InstrumentTileId.RUDDER_ANGLE to "Ángulo de timón",InstrumentTileId.WATER_TEMPERATURE to "Temperatura del agua",InstrumentTileId.AIR_TEMPERATURE to "Temperatura del aire",InstrumentTileId.CURRENT_SET to "Dirección de la corriente",InstrumentTileId.CURRENT_DRIFT to "Velocidad de la corriente",InstrumentTileId.CROSS_TRACK_ERROR to "Error transversal",InstrumentTileId.WAYPOINT_BEARING to "Demora al waypoint",InstrumentTileId.WAYPOINT_DISTANCE to "Distancia al waypoint",InstrumentTileId.TOTAL_LOG to "Corredera total",InstrumentTileId.TRIP_LOG to "Corredera parcial",
    )
}
