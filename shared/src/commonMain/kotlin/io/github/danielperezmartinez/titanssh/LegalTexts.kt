package io.github.danielperezmartinez.titanssh

/** Legal documents bundled with the app, by their file name in the repository root. */
enum class LegalText(val fileName: String) {
    LICENSE("LICENSE"),
    THIRD_PARTY_NOTICES("THIRD_PARTY_NOTICES.md"),
}

/**
 * Text of a bundled legal document, or null if the build did not package it.
 * The `jvmShared` actual reads the copies that the `bundleLegalTexts` Gradle
 * task places under the `/legal/` resources, so the About screen works offline.
 */
expect fun readLegalText(text: LegalText): String?
