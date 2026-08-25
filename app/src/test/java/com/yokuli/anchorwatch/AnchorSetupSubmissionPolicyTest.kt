package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.runtime.RuntimeFeedbackContext
import com.yokuli.anchorwatch.runtime.RuntimeUserFeedback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorSetupSubmissionPolicyTest {
    @Test fun localizedArmFailureEndsSpinnerWithoutMatchingEnglishTitle(){
        val feedback=RuntimeUserFeedback(12,"锚警未启动","没有可用的手机 GNSS 定位",true,100,RuntimeFeedbackContext.ARM_WATCH)
        assertTrue(AnchorSetupSubmissionPolicy.isFailure(feedback,11))
        assertFalse(AnchorSetupSubmissionPolicy.isFailure(feedback,12))
    }
}
