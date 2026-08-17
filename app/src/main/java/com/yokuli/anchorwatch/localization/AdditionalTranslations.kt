package com.yokuli.anchorwatch.localization

import com.yokuli.anchorwatch.domain.model.AppLanguage

/**
 * Safety-first supplemental catalogue for UI that still uses the legacy two-string
 * `tr(english, simplifiedChinese)` call. Missing entries deliberately fall back to
 * English rather than presenting an incorrect machine translation.
 */
internal object AdditionalTranslations {
    private val japanese = mapOf(
        "Watch" to "アンカー監視", "Data" to "データ", "History" to "履歴", "Settings" to "設定",
        "Language" to "言語", "Back" to "戻る", "Close" to "閉じる", "Cancel" to "キャンセル",
        "Continue" to "続行", "Save" to "保存", "Stop" to "停止", "Details" to "詳細",
        "View details" to "詳細を見る", "Open details" to "詳細を開く", "Approach" to "接近ガイド",
        "Set anchor" to "アンカーを設定", "Pause" to "一時停止", "Resume" to "再開",
        "Adjust range" to "範囲を調整", "Lift anchor" to "揚錨", "Acknowledge" to "確認",
        "ANCHOR WATCH OFF" to "アンカー監視オフ", "Ready to set anchor" to "アンカー設定可能",
        "Continuous watch health" to "継続監視の状態", "Open every live safety check" to "すべての安全チェックを表示",
        "DEPTH" to "水深", "Live sonar" to "ライブソナー", "Personal sonar map" to "個人ソナーマップ",
        "Saved anchoring area nearby" to "保存済みの錨泊エリアが近くにあります",
        "Saved anchorages nearby" to "近くの保存済み錨泊地", "SAVED ANCHORAGE NEARBY" to "保存済み錨泊地が近くにあります",
        "Direct reference only · not navigation" to "直線の参考のみ・航法ではありません",
        "Alarm & notifications" to "アラームと通知", "Vessel profile" to "船舶プロフィール",
        "Depth sounder" to "測深機", "Positioning" to "測位", "Map data" to "地図データ",
        "Background reliability" to "バックグラウンド信頼性", "Data & backup" to "データとバックアップ",
        "Storage & support" to "ストレージとサポート", "Developer" to "開発者",
        "About & support" to "このアプリとサポート", "Feedback & feature requests" to "フィードバックと機能要望",
        "Support Yokuli" to "Yokuliを支援", "Made aboard Yokuli" to "Yokuli船上で開発",
        "ALARM & WATCH" to "アラームと監視", "VESSEL & SENSORS" to "船舶とセンサー",
        "POSITION & MAP" to "測位と地図", "DEVICE & DATA" to "端末とデータ", "ADVANCED" to "詳細設定", "ABOUT" to "情報",
    )

    private val french = mapOf(
        "Watch" to "Mouillage", "Data" to "Données", "History" to "Historique", "Settings" to "Réglages",
        "Language" to "Langue", "Back" to "Retour", "Close" to "Fermer", "Cancel" to "Annuler",
        "Continue" to "Continuer", "Save" to "Enregistrer", "Stop" to "Arrêter", "Details" to "Détails",
        "View details" to "Voir les détails", "Open details" to "Ouvrir les détails", "Approach" to "Guidage d’approche",
        "Set anchor" to "Définir le mouillage", "Pause" to "Suspendre", "Resume" to "Reprendre",
        "Adjust range" to "Ajuster le rayon", "Lift anchor" to "Lever l’ancre", "Acknowledge" to "Acquitter",
        "ANCHOR WATCH OFF" to "SURVEILLANCE DÉSACTIVÉE", "Ready to set anchor" to "Prêt à définir le mouillage",
        "Continuous watch health" to "État continu de la surveillance", "Open every live safety check" to "Voir tous les contrôles de sécurité",
        "DEPTH" to "PROFONDEUR", "Live sonar" to "Sondeur en direct", "Personal sonar map" to "Carte sonar personnelle",
        "Saved anchoring area nearby" to "Zone de mouillage enregistrée à proximité",
        "Saved anchorages nearby" to "Mouillages enregistrés à proximité", "SAVED ANCHORAGE NEARBY" to "MOUILLAGE ENREGISTRÉ À PROXIMITÉ",
        "Direct reference only · not navigation" to "Référence directe uniquement · pas de navigation",
        "Alarm & notifications" to "Alarme et notifications", "Vessel profile" to "Profil du bateau",
        "Depth sounder" to "Sondeur", "Positioning" to "Positionnement", "Map data" to "Données cartographiques",
        "Background reliability" to "Fiabilité en arrière-plan", "Data & backup" to "Données et sauvegarde",
        "Storage & support" to "Stockage et assistance", "Developer" to "Développeur",
        "About & support" to "À propos et assistance", "Feedback & feature requests" to "Retours et demandes de fonctionnalités",
        "Support Yokuli" to "Soutenir Yokuli", "Made aboard Yokuli" to "Créé à bord de Yokuli",
        "ALARM & WATCH" to "ALARME ET SURVEILLANCE", "VESSEL & SENSORS" to "BATEAU ET CAPTEURS",
        "POSITION & MAP" to "POSITION ET CARTE", "DEVICE & DATA" to "APPAREIL ET DONNÉES", "ADVANCED" to "AVANCÉ", "ABOUT" to "À PROPOS",
    )

    private val spanish = mapOf(
        "Watch" to "Fondeo", "Data" to "Datos", "History" to "Historial", "Settings" to "Ajustes",
        "Language" to "Idioma", "Back" to "Atrás", "Close" to "Cerrar", "Cancel" to "Cancelar",
        "Continue" to "Continuar", "Save" to "Guardar", "Stop" to "Detener", "Details" to "Detalles",
        "View details" to "Ver detalles", "Open details" to "Abrir detalles", "Approach" to "Guía de aproximación",
        "Set anchor" to "Fijar ancla", "Pause" to "Pausar", "Resume" to "Reanudar",
        "Adjust range" to "Ajustar radio", "Lift anchor" to "Levar ancla", "Acknowledge" to "Confirmar",
        "ANCHOR WATCH OFF" to "VIGILANCIA DESACTIVADA", "Ready to set anchor" to "Listo para fijar el ancla",
        "Continuous watch health" to "Estado continuo de vigilancia", "Open every live safety check" to "Ver todas las comprobaciones de seguridad",
        "DEPTH" to "PROFUNDIDAD", "Live sonar" to "Sonda en directo", "Personal sonar map" to "Mapa de sonda personal",
        "Saved anchoring area nearby" to "Zona de fondeo guardada cerca",
        "Saved anchorages nearby" to "Fondeaderos guardados cercanos", "SAVED ANCHORAGE NEARBY" to "FONDEADERO GUARDADO CERCA",
        "Direct reference only · not navigation" to "Solo referencia directa · no es navegación",
        "Alarm & notifications" to "Alarma y notificaciones", "Vessel profile" to "Perfil del barco",
        "Depth sounder" to "Sonda", "Positioning" to "Posicionamiento", "Map data" to "Datos del mapa",
        "Background reliability" to "Fiabilidad en segundo plano", "Data & backup" to "Datos y copia de seguridad",
        "Storage & support" to "Almacenamiento y soporte", "Developer" to "Desarrollador",
        "About & support" to "Acerca de y soporte", "Feedback & feature requests" to "Comentarios y solicitudes de funciones",
        "Support Yokuli" to "Apoyar a Yokuli", "Made aboard Yokuli" to "Creado a bordo de Yokuli",
        "ALARM & WATCH" to "ALARMA Y VIGILANCIA", "VESSEL & SENSORS" to "BARCO Y SENSORES",
        "POSITION & MAP" to "POSICIÓN Y MAPA", "DEVICE & DATA" to "DISPOSITIVO Y DATOS", "ADVANCED" to "AVANZADO", "ABOUT" to "ACERCA DE",
    )

    fun translate(language: AppLanguage, english: String): String? = when (language) {
        AppLanguage.JAPANESE -> japanese[english]
        AppLanguage.FRENCH -> french[english]
        AppLanguage.SPANISH -> spanish[english]
        else -> null
    }
}

internal object TraditionalChinese {
    private val phrases = linkedMapOf(
        "简体中文" to "簡體中文", "程序员" to "程式設計師", "软件" to "軟體", "默认" to "預設",
        "数据" to "資料", "信息" to "資訊", "视频" to "影片", "设置" to "設定", "地图" to "地圖",
        "导航" to "導航", "锚地" to "錨地", "锚点" to "錨點", "锚警" to "錨警", "声呐" to "聲納",
    )
    private val characters = mapOf(
        '锚' to '錨', '警' to '警', '设' to '設', '置' to '置', '数' to '數', '据' to '據', '历' to '歷',
        '图' to '圖', '导' to '導', '航' to '航', '声' to '聲', '呐' to '納', '简' to '簡', '体' to '體',
        '中' to '中', '文' to '文', '开' to '開', '关' to '關', '闭' to '閉', '启' to '啟', '发' to '發',
        '现' to '現', '连' to '連', '接' to '接', '断' to '斷', '线' to '線', '经' to '經', '过' to '過',
        '这' to '這', '个' to '個', '为' to '為', '与' to '與', '后' to '後', '会' to '會', '时' to '時',
        '间' to '間', '钟' to '鐘', '点' to '點', '围' to '圍', '范' to '範', '测' to '測', '试' to '試',
        '记' to '記', '录' to '錄', '储' to '儲', '备' to '備', '份' to '份', '复' to '復', '换' to '換',
        '选' to '選', '择' to '擇', '显' to '顯', '应' to '應', '实' to '實', '际' to '際', '参' to '參',
        '数' to '數', '长' to '長', '链' to '鏈', '风' to '風', '机' to '機', '电' to '電', '话' to '話',
        '报' to '報', '统' to '統', '认' to '認', '证' to '證', '权' to '權', '限' to '限', '处' to '處',
        '万' to '萬', '亿' to '億', '丢' to '丟', '两' to '兩', '严' to '嚴', '临' to '臨', '乐' to '樂',
        '书' to '書', '买' to '買', '乱' to '亂', '亲' to '親', '仅' to '僅', '从' to '從', '优' to '優',
        '传' to '傳', '伤' to '傷', '众' to '眾', '写' to '寫', '农' to '農', '冲' to '衝', '决' to '決',
        '况' to '況', '净' to '淨', '减' to '減', '刚' to '剛', '创' to '創', '删' to '刪', '别' to '別',
        '务' to '務', '动' to '動', '劳' to '勞', '区' to '區', '华' to '華', '单' to '單', '卖' to '賣',
        '卫' to '衛', '压' to '壓', '县' to '縣', '双' to '雙', '变' to '變', '叶' to '葉', '号' to '號',
        '听' to '聽', '员' to '員', '响' to '響', '园' to '園', '圆' to '圓', '场' to '場', '块' to '塊',
        '头' to '頭', '夹' to '夾', '奖' to '獎', '妇' to '婦', '妈' to '媽', '孙' to '孫', '学' to '學',
        '宁' to '寧', '宝' to '寶', '审' to '審', '宽' to '寬', '对' to '對', '寻' to '尋', '将' to '將',
        '层' to '層', '岁' to '歲', '岛' to '島', '币' to '幣', '师' to '師', '帐' to '帳', '带' to '帶',
        '帮' to '幫', '库' to '庫', '废' to '廢', '张' to '張', '强' to '強', '归' to '歸', '当' to '當',
        '总' to '總', '态' to '態', '悬' to '懸', '惊' to '驚', '惧' to '懼', '戏' to '戲', '户' to '戶',
        '执' to '執', '扩' to '擴', '扫' to '掃', '扬' to '揚', '护' to '護', '报' to '報', '拟' to '擬',
        '挡' to '擋', '损' to '損', '换' to '換', '摇' to '搖', '摆' to '擺', '无' to '無', '旧' to '舊',
        '术' to '術', '权' to '權', '条' to '條', '来' to '來', '极' to '極', '构' to '構', '标' to '標',
        '栋' to '棟', '栏' to '欄', '树' to '樹', '样' to '樣', '档' to '檔', '桥' to '橋', '检' to '檢',
        '欢' to '歡', '气' to '氣', '汉' to '漢', '沟' to '溝', '没' to '沒', '洁' to '潔', '浅' to '淺',
        '浊' to '濁', '济' to '濟', '浓' to '濃', '涛' to '濤', '润' to '潤', '涨' to '漲', '湾' to '灣',
        '湿' to '濕', '满' to '滿', '滤' to '濾', '滨' to '濱', '滩' to '灘', '潜' to '潛', '灯' to '燈',
        '灵' to '靈', '灾' to '災', '炉' to '爐', '炼' to '煉', '热' to '熱', '爱' to '愛', '牵' to '牽',
        '独' to '獨', '狮' to '獅', '猫' to '貓', '献' to '獻', '环' to '環', '玛' to '瑪', '畅' to '暢',
        '疗' to '療', '盘' to '盤', '稳' to '穩', '积' to '積', '称' to '稱', '签' to '簽', '类' to '類',
        '粮' to '糧', '紧' to '緊', '级' to '級', '纯' to '純', '纳' to '納', '纸' to '紙', '纹' to '紋',
        '组' to '組', '细' to '細', '织' to '織', '终' to '終', '绍' to '紹', '结' to '結', '绕' to '繞',
        '给' to '給', '绝' to '絕', '统' to '統', '继' to '繼', '续' to '續', '维' to '維', '绿' to '綠',
        '缓' to '緩', '编' to '編', '缩' to '縮', '缴' to '繳', '网' to '網', '罚' to '罰', '职' to '職',
        '联' to '聯', '聪' to '聰', '肃' to '肅', '肠' to '腸', '肤' to '膚', '肿' to '腫', '脑' to '腦',
        '脸' to '臉', '舰' to '艦', '舱' to '艙', '艺' to '藝', '节' to '節', '苏' to '蘇', '蓝' to '藍',
        '虽' to '雖', '补' to '補', '装' to '裝', '见' to '見', '观' to '觀', '规' to '規', '视' to '視',
        '览' to '覽', '觉' to '覺', '订' to '訂', '计' to '計', '讯' to '訊', '讨' to '討', '让' to '讓',
        '训' to '訓', '议' to '議', '许' to '許', '论' to '論', '设' to '設', '访' to '訪', '评' to '評',
        '识' to '識', '诉' to '訴', '词' to '詞', '译' to '譯', '诚' to '誠', '话' to '話', '询' to '詢',
        '详' to '詳', '语' to '語', '误' to '誤', '说' to '說', '请' to '請', '读' to '讀', '课' to '課',
        '谁' to '誰', '调' to '調', '谈' to '談', '谢' to '謝', '谨' to '謹', '贝' to '貝', '财' to '財',
        '责' to '責', '账' to '賬', '质' to '質', '购' to '購', '费' to '費', '贺' to '賀', '资' to '資',
        '赔' to '賠', '赞' to '贊', '赠' to '贈', '车' to '車', '转' to '轉', '轮' to '輪', '软' to '軟',
        '轴' to '軸', '轻' to '輕', '载' to '載', '输' to '輸', '边' to '邊', '达' to '達', '迁' to '遷',
        '运' to '運', '还' to '還', '进' to '進', '远' to '遠', '迟' to '遲', '适' to '適', '递' to '遞',
        '逻' to '邏', '邮' to '郵', '邻' to '鄰', '释' to '釋', '里' to '裡', '钟' to '鐘', '钢' to '鋼',
        '钥' to '鑰', '钱' to '錢', '铁' to '鐵', '铃' to '鈴', '铜' to '銅', '铝' to '鋁', '铭' to '銘',
        '银' to '銀', '铺' to '鋪', '链' to '鏈', '锁' to '鎖', '锅' to '鍋', '错' to '錯', '锋' to '鋒',
        '镜' to '鏡', '门' to '門', '问' to '問', '闲' to '閒', '闻' to '聞', '阅' to '閱', '队' to '隊',
        '阳' to '陽', '阴' to '陰', '阵' to '陣', '阶' to '階', '际' to '際', '陆' to '陸', '陈' to '陳',
        '险' to '險', '随' to '隨', '隐' to '隱', '难' to '難', '雾' to '霧', '静' to '靜', '页' to '頁',
        '顶' to '頂', '项' to '項', '顺' to '順', '须' to '須', '顾' to '顧', '领' to '領', '预' to '預',
        '频' to '頻', '题' to '題', '额' to '額', '颜' to '顏', '风' to '風', '飞' to '飛', '饭' to '飯',
        '饮' to '飲', '馆' to '館', '马' to '馬', '驱' to '驅', '驻' to '駐', '驾' to '駕', '验' to '驗',
        '鱼' to '魚', '鲜' to '鮮', '鲸' to '鯨', '鸟' to '鳥', '鸡' to '雞', '鸣' to '鳴', '黄' to '黃',
        '齐' to '齊', '齿' to '齒', '龄' to '齡', '龙' to '龍', '龟' to '龜',
    )

    fun convert(value: String): String {
        var result = value
        phrases.forEach { (simplified, traditional) -> result = result.replace(simplified, traditional) }
        return buildString(result.length) { result.forEach { append(characters[it] ?: it) } }
    }
}
