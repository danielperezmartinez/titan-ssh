package io.github.danielperezmartinez.titanssh

actual fun readLegalText(text: LegalText): String? =
    LegalText::class.java.getResourceAsStream("/legal/${text.fileName}")
        ?.use { it.readBytes().decodeToString() }
