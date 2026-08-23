package com.yokuli.anchorwatch

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.domain.model.AlarmState
import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.ui.about.OnboardingMakerScreen

private data class Destination(val label:String,val icon:ImageVector)
internal val LocalAppLanguage=compositionLocalOf{AppLanguage.ENGLISH}

@Composable internal fun tr(english:String,chinese:String)=localized(LocalAppLanguage.current,english,chinese)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AnchorApp(vm:MainViewModel){
    val state by vm.ui.collectAsState()
    CompositionLocalProvider(LocalAppLanguage provides state.settings.appLanguage){
        if(!state.settingsReady){
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            return@CompositionLocalProvider
        }
        if(!state.settings.onboardingCompleted){
            OnboardingMakerScreen(vm::completeOnboarding){language->vm.updateSettings(state.settings.copy(appLanguage=language))}
            return@CompositionLocalProvider
        }
        val destinations=listOf(
            Destination(tr("Watch","监控"),Icons.Default.Map),
            Destination(tr("Data","数据"),Icons.Default.DataObject),
            Destination(tr("History","历史"),Icons.AutoMirrored.Filled.List),
            Destination(tr("Settings","设置"),Icons.Default.Settings),
        )
        val destinationTags=listOf("nav_watch","nav_data","nav_history","nav_settings")
        Scaffold(bottomBar={NavigationBar{destinations.forEachIndexed{index,item->NavigationBarItem(state.page==index,{vm.page(index)},{Icon(item.icon,item.label)},modifier=Modifier.testTag(destinationTags[index]),label={Text(item.label)})}}}){padding->
            Box(Modifier.fillMaxSize().padding(padding)){
                when(state.page){0->WatchPage(state,vm);1->DataPage(state,vm);2->HistoryPage(state,vm);else->SettingsScreen(state,vm)}
                AlarmTestBanner(state,vm,Modifier.align(Alignment.TopCenter))
            }
        }
        AnchorDragAlarmDialog(state,vm)
    }
}

@Composable
private fun AlarmTestBanner(state:MainUiState,vm:MainViewModel,modifier:Modifier=Modifier){
    if(state.alarmSnapshot.state!=AlarmState.ALARM||state.alarmSnapshot.type!=AlarmType.ALARM_TEST)return
    ElevatedCard(modifier.padding(12.dp).fillMaxWidth().testTag("alarm_test_banner")){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(tr("Alarm test is sounding","警报测试正在响铃"),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.error)
            Text(tr("The test continues in the background. Confirm that it is audible, or stop it now.","测试会在后台继续。请确认能否听见，或立即停止。"),style=MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button({vm.confirmAlarmAudible();vm.stopAlarmTest()},Modifier.weight(1f).testTag("confirm_alarm_audible_banner")){
                    Icon(Icons.Default.Hearing,null);Spacer(Modifier.width(6.dp));Text(tr("I can hear it","我能听见"))
                }
                OutlinedButton(vm::stopAlarmTest,Modifier.weight(1f).testTag("stop_alarm_test_banner")){
                    Icon(Icons.Default.StopCircle,null);Spacer(Modifier.width(6.dp));Text(tr("Stop","停止"))
                }
            }
        }
    }
}
