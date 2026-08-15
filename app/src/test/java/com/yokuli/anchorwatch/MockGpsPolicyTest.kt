package com.yokuli.anchorwatch
import com.yokuli.anchorwatch.location.MockGpsPolicy
import org.junit.Assert.*
import org.junit.Test
class MockGpsPolicyTest{
 @Test fun throttlesToConfiguredRate(){val p=MockGpsPolicy(15_000,2);p.start(0);assertTrue(p.onValidFix(100));assertFalse(p.onValidFix(400));assertTrue(p.onValidFix(600))}
 @Test fun staleBeforeFirstAndAfterLastFix(){val p=MockGpsPolicy(15_000,1);p.start(1000);assertFalse(p.isStale(15_999));assertTrue(p.isStale(16_000));p.onValidFix(20_000);assertFalse(p.isStale(34_999));assertTrue(p.isStale(35_000))}
}
