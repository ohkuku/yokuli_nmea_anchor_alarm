package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.ui.about.ExternalLinkPolicy
import com.yokuli.anchorwatch.ui.about.FeedbackEmailComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackEmailComposerTest {
    @Test fun createsAValidEncodedMailtoDraft() {
        val result = FeedbackEmailComposer.mailto(
            recipient = "kuku.the.developer@gmail.com",
            subject = "Anchor Watch feature request",
            body = "Please add: wind history & notes 中文",
        )

        assertEquals(
            "mailto:kuku.the.developer@gmail.com?subject=Anchor%20Watch%20feature%20request&body=Please%20add%3A%20wind%20history%20%26%20notes%20%E4%B8%AD%E6%96%87",
            result,
        )
        assertTrue(ExternalLinkPolicy.isAllowed(requireNotNull(result)))
    }

    @Test fun rejectsAnythingThatIsNotOnePlainEmailAddress() {
        assertNull(FeedbackEmailComposer.mailto("", "Subject", "Body"))
        assertNull(FeedbackEmailComposer.mailto("https://example.com", "Subject", "Body"))
        assertNull(FeedbackEmailComposer.mailto("first@example.com,second@example.com", "Subject", "Body"))
        assertNull(FeedbackEmailComposer.mailto("a@example.com?bcc=x@example.com", "Subject", "Body"))
    }

    @Test fun blankDraftStillOpensOnlyTheConfiguredRecipient() {
        assertEquals(
            "mailto:kuku.the.developer@gmail.com",
            FeedbackEmailComposer.mailto(" kuku.the.developer@gmail.com ", " ", ""),
        )
    }

    @Test fun feedbackMetadataContainsNoOperationalOrLocationData() {
        val body = FeedbackEmailComposer.requestBody(
            details = "Please make the alarm card easier to read.",
            versionName = "1.0.0",
            androidVersion = "16",
            deviceModel = "Example Phone",
        )

        assertTrue(body.contains("Please make the alarm card easier to read."))
        assertTrue(body.contains("Boat Watch version: 1.0.0"))
        assertTrue(body.contains("Android version: 16"))
        assertTrue(body.contains("Device model: Example Phone"))
        assertTrue(!body.contains("latitude", ignoreCase = true))
        assertTrue(!body.contains("NMEA host", ignoreCase = true))
        assertTrue(!body.contains("raw NMEA", ignoreCase = true))
    }
}
