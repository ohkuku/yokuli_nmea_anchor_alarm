package com.yokuli.anchorwatch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.SystemClock
import com.yokuli.anchorwatch.map.MapRuntimePolicy
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class) class AppLaunchTest{
    @Before fun disableExternalMapRenderer(){MapRuntimePolicy.renderGoogleEngine=false;runBlocking{SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext).save(AppSettings())}}
    @After fun restoreExternalMapRenderer(){MapRuntimePolicy.renderGoogleEngine=true}
    @Test fun launches(){ActivityScenario.launch(MainActivity::class.java).use{it.onActivity{a->check(!a.isFinishing)}}}
    @Test fun coldStartWithRealGoogleMapDoesNotRaceDescriptorFactory(){
        MapRuntimePolicy.renderGoogleEngine=true
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            SystemClock.sleep(2_000)
            scenario.onActivity{activity->check(!activity.isFinishing&&!activity.isDestroyed)}
        }
    }
}
