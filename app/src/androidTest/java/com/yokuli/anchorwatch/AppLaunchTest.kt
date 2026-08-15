package com.yokuli.anchorwatch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class) class AppLaunchTest{@Test fun launches(){ActivityScenario.launch(MainActivity::class.java).use{it.onActivity{a->check(!a.isFinishing)}}}}
