package com.yokuli.anchorwatch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AnchorRootPage(state:MainUiState,vm:MainViewModel){
    val titles=listOf(tr("Current","当前"),tr("History","历史"),tr("Anchorages","收藏锚地"))
    val pager=rememberPagerState(pageCount={titles.size});val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxSize().testTag("anchor_root")){
        PrimaryTabRow(pager.currentPage,Modifier.fillMaxWidth()){titles.forEachIndexed{index,title->Tab(pager.currentPage==index,{scope.launch{pager.animateScrollToPage(index)}},text={Text(title)},modifier=Modifier.testTag("anchor_tab_$index"))}}
        HorizontalPager(pager,Modifier.weight(1f)){page->when(page){0->AnchorWatchPage(state,vm);1->HistoryPage(state,vm,fixedTab=0);else->HistoryPage(state,vm,fixedTab=1)}}
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SailRootPage(state:MainUiState,vm:MainViewModel){
    val titles=listOf(tr("Live","实时"),tr("Trips","航程"))
    val pager=rememberPagerState(pageCount={titles.size});val scope=rememberCoroutineScope()
    val cockpit=LocalSailCockpitMode.current?.value==true
    LaunchedEffect(cockpit){if(cockpit&&pager.currentPage!=0)pager.scrollToPage(0)}
    Column(Modifier.fillMaxSize().testTag("sail_root")){
        if(!cockpit)PrimaryTabRow(pager.currentPage,Modifier.fillMaxWidth()){titles.forEachIndexed{index,title->Tab(pager.currentPage==index,{scope.launch{pager.animateScrollToPage(index)}},text={Text(title)},modifier=Modifier.testTag("sail_tab_$index"))}}
        HorizontalPager(pager,Modifier.weight(1f),userScrollEnabled=!cockpit){page->if(page==0)TripWatchPage(state,vm)else HistoryPage(state,vm,fixedTab=2)}
    }
}
