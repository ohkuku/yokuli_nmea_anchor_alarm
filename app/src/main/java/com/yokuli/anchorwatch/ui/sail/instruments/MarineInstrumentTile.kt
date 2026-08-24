package com.yokuli.anchorwatch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yokuli.anchorwatch.data.trip.InstrumentTileSize

/**
 * Sunlight-readable MFD primitive. The parent owns metric/source routing while
 * this component owns the consistent visual hierarchy and tile gestures.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun MarineInstrumentTile(
    title:String,
    value:String,
    sourceState:String,
    fresh:Boolean,
    receivedElapsedRealtime:Long?,
    size:InstrumentTileSize=InstrumentTileSize.MEDIUM,
    modifier:Modifier=Modifier,
    onClick:()->Unit={},
    onLongClick:()->Unit={},
){
    Surface(
        modifier=modifier
            .testTag("marine_instrument_$title")
            .heightIn(min=when(size){
                InstrumentTileSize.SMALL->72.dp
                InstrumentTileSize.MEDIUM->102.dp
                InstrumentTileSize.WIDE->112.dp
                InstrumentTileSize.LARGE->146.dp
                InstrumentTileSize.HERO->190.dp
            })
            .combinedClickable(onClick=onClick,onLongClick=onLongClick),
        color=MaterialTheme.colorScheme.surface,
        tonalElevation=0.dp,
        shadowElevation=0.dp,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
        shape=MaterialTheme.shapes.small,
    ){
        Column(
            // fillMaxSize() lets the first row in a bounded MFD grid consume
            // all remaining vertical space, collapsing every later row to
            // zero height. The Surface already owns the requested minimum;
            // content should wrap within it instead of claiming the viewport.
            Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(2.dp),
        ){
            Text(title,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style=when(size){
                    InstrumentTileSize.SMALL->MaterialTheme.typography.titleLarge
                    InstrumentTileSize.MEDIUM,InstrumentTileSize.WIDE->MaterialTheme.typography.headlineSmall
                    InstrumentTileSize.LARGE,InstrumentTileSize.HERO->MaterialTheme.typography.displaySmall
                },
                fontWeight=FontWeight.Bold,
            )
            val age=receivedElapsedRealtime?.let{(android.os.SystemClock.elapsedRealtime()-it).coerceAtLeast(0L)/1_000.0}
            Text(
                if(age!=null)sourceState.substringBeforeLast(" · ")+" · %.1fs".format(age) else sourceState,
                style=MaterialTheme.typography.labelSmall,
                color=if(fresh)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
