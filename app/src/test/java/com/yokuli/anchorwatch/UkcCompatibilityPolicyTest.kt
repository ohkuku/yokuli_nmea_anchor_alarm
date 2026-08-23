package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.domain.vessel.UkcCompatibilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UkcCompatibilityPolicyTest{
    @Test fun surfaceReferencedDepthCanProduceUkc(){assertEquals(3.2,UkcCompatibilityPolicy.calculate(5.0,DepthReference.BELOW_SURFACE,1.8)!!,.001)}
    @Test fun unknownTransducerOrKeelReferenceNeverInventsUkc(){
        assertNull(UkcCompatibilityPolicy.calculate(5.0,DepthReference.UNKNOWN,1.8))
        assertNull(UkcCompatibilityPolicy.calculate(5.0,DepthReference.BELOW_TRANSDUCER,1.8))
        assertNull(UkcCompatibilityPolicy.calculate(5.0,DepthReference.BELOW_KEEL,1.8))
    }
}
