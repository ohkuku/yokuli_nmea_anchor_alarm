package com.yokuli.anchorwatch.map

/**
 * Keeps state-machine/UI integration tests independent from the proprietary
 * Google renderer, network and emulator GPU. Production never changes this
 * flag; device tests explicitly restore it after each story.
 */
object MapRuntimePolicy {
    @Volatile var renderGoogleEngine: Boolean = true
}
