package com.yokuli.anchorwatch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
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
    val pager=rememberPagerState(initialPage=state.anchorSection,pageCount={titles.size});val scope=rememberCoroutineScope()
    LaunchedEffect(state.anchorSection){if(pager.currentPage!=state.anchorSection)pager.scrollToPage(state.anchorSection)}
    LaunchedEffect(pager.currentPage){vm.rememberAnchorSection(pager.currentPage)}
    Column(Modifier.fillMaxSize().testTag("anchor_root")){
        PrimaryTabRow(pager.currentPage,Modifier.fillMaxWidth()){titles.forEachIndexed{index,title->Tab(pager.currentPage==index,{scope.launch{pager.animateScrollToPage(index)}},text={Text(title)},modifier=Modifier.testTag("anchor_tab_$index"))}}
        ClickOnlyWorkspacePager(pager,Modifier.weight(1f)){page->when(page){0->AnchorWatchPage(state,vm);1->AnchorHistoryPage(state,vm);else->AnchorageLibraryPage(state,vm)}}
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SailRootPage(state:MainUiState,vm:MainViewModel){
    val titles=listOf(tr("Live","实时"),tr("Trips","航程"))
    val pager=rememberPagerState(initialPage=state.sailSection,pageCount={titles.size});val scope=rememberCoroutineScope()
    val cockpit=LocalSailCockpitMode.current?.value==true
    LaunchedEffect(cockpit){if(cockpit&&pager.currentPage!=0)pager.scrollToPage(0)}
    LaunchedEffect(state.sailSection){if(!cockpit&&pager.currentPage!=state.sailSection)pager.scrollToPage(state.sailSection)}
    LaunchedEffect(pager.currentPage){vm.rememberSailSection(pager.currentPage)}
    Column(Modifier.fillMaxSize().testTag("sail_root")){
        if(!cockpit)PrimaryTabRow(pager.currentPage,Modifier.fillMaxWidth()){titles.forEachIndexed{index,title->Tab(pager.currentPage==index,{scope.launch{pager.animateScrollToPage(index)}},text={Text(title)},modifier=Modifier.testTag("sail_tab_$index"))}}
        ClickOnlyWorkspacePager(pager,Modifier.weight(1f)){page->if(page==0)TripWatchPage(state,vm)else TripHistoryPage(state,vm)}
    }
}

/** Root workspace sections are navigation destinations, not gesture owners.
 * Interactive maps, nested instrument pagers, sliders and sheets retain their
 * own touch streams; section changes happen only through the visible tabs. */
@Composable
internal fun ClickOnlyWorkspacePager(state:PagerState,modifier:Modifier=Modifier,content:@Composable PagerScope.(Int)->Unit){
    HorizontalPager(state=state,modifier=modifier,userScrollEnabled=false,pageContent=content)
}
