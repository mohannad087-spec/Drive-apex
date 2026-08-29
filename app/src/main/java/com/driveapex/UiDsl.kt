package com.driveapex

/**
 * Allows the compact UI profile declaration `"Name" to "Subtitle" to accentColor`
 * to remain a Triple without touching the standard Kotlin `Any.to` semantics elsewhere.
 */
infix fun Pair<String, String>.to(accentColor: Int): Triple<String, String, Int> =
    Triple(first, second, accentColor)
