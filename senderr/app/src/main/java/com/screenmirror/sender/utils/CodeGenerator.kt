package com.screenmirror.sender.utils

import java.security.SecureRandom

/**
 * Generates a 6-digit pairing code.
 * Uses SecureRandom to avoid predictable sequences.
 */
object CodeGenerator {
    private val random = SecureRandom()

    fun generate6DigitCode(): String {
        val code = random.nextInt(1_000_000)
        return String.format("%06d", code)
    }
}
