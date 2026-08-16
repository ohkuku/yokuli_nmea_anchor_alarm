package com.yokuli.anchorwatch

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.localization.localized

private data class Destination(val label:String,val icon:ImageVector)
internal val LocalAppLanguage=compositionLocalOf{AppLanguage.SYSTEM}

@Composable internal fun tr(english:String,chinese:String)=localized(LocalAppLanguage.current,english,chinese)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AnchorApp(vm:MainViewModel){
    val state by vm.ui.collectAsState()
    CompositionLocalProvider(LocalAppLanguage provides state.settings.appLanguage){
        val destinations=listOf(
            Destination(tr("Watch","锚警"),Icons.Default.Map),
            Destination(tr("Data","数据"),Icons.Default.DataObject),
            Destination(tr("History","历史"),Icons.AutoMirrored.Filled.List),
            Destination(tr("Settings","设置"),Icons.Default.Settings),
        )
        Scaffold(bottomBar={NavigationBar{destinations.forEachIndexed{index,item->NavigationBarItem(state.page==index,{vm.page(index)},{Icon(item.icon,item.label)},label={Text(item.label)})}}}){padding->
            Box(Modifier.padding(padding)){when(state.page){0->WatchPage(state,vm);1->DataPage(state,vm);2->HistoryPage(state,vm);else->SettingsScreen(state,vm)}}
        }
        AnchorDragAlarmDialog(state,vm)
    }
}
