package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import io.github.danielperezmartinez.titanssh.BuildInfo
import io.github.danielperezmartinez.titanssh.LegalText
import io.github.danielperezmartinez.titanssh.platformName
import io.github.danielperezmartinez.titanssh.readLegalText
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

private const val REPOSITORY_URL = "https://github.com/danielperezmartinez/titan-ssh"

/**
 * Source code of exactly this build: the release tag for a published version,
 * the repository itself for a development build.
 */
private fun sourceUrl(): String =
    if (!BuildInfo.IS_RELEASE) REPOSITORY_URL else "$REPOSITORY_URL/tree/v${BuildInfo.VERSION}"

/**
 * "Acerca de": version, copyright and the legal notices the GPLv3 (§0, §5.d)
 * asks an interactive program to show — copyright, no warranty, the license and
 * how to read it — plus the source code link and the third-party licenses. Both
 * documents open in an in-app viewer from the bundled copies ([readLegalText]).
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    var document by remember { mutableStateOf<LegalText?>(null) }

    when (val current = document) {
        null -> AboutSummary(onBack = onBack, onOpen = { document = it })
        else -> LegalTextViewer(current, onBack = { document = null })
    }
}

@Composable
private fun AboutSummary(onBack: () -> Unit, onOpen: (LegalText) -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        TopBar("Acerca de", onBack)
        Hairline()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bodyPadding())) {
            Text("titan-ssh", style = MaterialTheme.typography.headlineSmall, color = TitanColors.Ink)
            Caption("Versión ${BuildInfo.VERSION} · ${platformName()}")
            Spacer(Modifier.height(TitanDimens.SpaceLg))
            Text(
                "Copyright (C) 2026 Daniel Pérez Martínez",
                style = MaterialTheme.typography.bodyMedium,
                color = TitanColors.Body,
            )
            Spacer(Modifier.height(TitanDimens.SpaceMd))
            Text(
                "Este programa es software libre: puede redistribuirlo y/o modificarlo " +
                    "según los términos de la Licencia Pública General de GNU (GPL) publicada " +
                    "por la Free Software Foundation, ya sea la versión 3 de la licencia o, a " +
                    "su elección, cualquier versión posterior (GPL-3.0-or-later).",
                style = MaterialTheme.typography.bodyMedium,
                color = TitanColors.Body,
            )
            Spacer(Modifier.height(TitanDimens.SpaceMd))
            Text(
                "Se distribuye SIN NINGUNA GARANTÍA, ni siquiera la garantía implícita de " +
                    "COMERCIABILIDAD o IDONEIDAD PARA UN PROPÓSITO PARTICULAR. Consulte la " +
                    "licencia para más detalles.",
                style = MaterialTheme.typography.bodyMedium,
                color = TitanColors.Body,
            )

            SectionHeader("Código fuente")
            Link(sourceUrl().removePrefix("https://")) {
                runCatching { uriHandler.openUri(sourceUrl()) }
            }

            SectionHeader("Licencias")
            ListRow(
                marker = "[=]",
                title = "Licencia de titan-ssh",
                subtitle = "GPL-3.0-or-later · texto íntegro",
                onClick = { onOpen(LegalText.LICENSE) },
            )
            ListRow(
                marker = "[=]",
                title = "Componentes de terceros",
                subtitle = "Bibliotecas, fuentes y sus licencias",
                onClick = { onOpen(LegalText.THIRD_PARTY_NOTICES) },
            )
        }
    }
}

/** Full text of a bundled legal document, selectable, in the mono body style. */
@Composable
private fun LegalTextViewer(text: LegalText, onBack: () -> Unit) {
    val content = remember(text) { readLegalText(text) }
    Column(Modifier.fillMaxSize()) {
        TopBar(text.fileName, onBack)
        Hairline()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bodyPadding())) {
            if (content == null) {
                EmptyState("Este build no incluye ${text.fileName}. Está en $REPOSITORY_URL")
            } else {
                SelectionContainer {
                    Text(content, style = MaterialTheme.typography.labelSmall, color = TitanColors.Body)
                }
            }
        }
    }
}

/** A `[<]` back action and a title, the read-only counterpart of [EditorScaffold]'s bar. */
@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = TitanDimens.SpaceSm, vertical = TitanDimens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton("[<]", onClick = onBack, color = TitanColors.Body)
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = TitanColors.Ink,
            modifier = Modifier.weight(1f).padding(start = TitanDimens.SpaceSm),
        )
    }
}

/** An external link: accent text (links are an interaction, visual decision) with a touch-sized target. */
@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TitanDimens.TouchTarget)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TitanColors.Accent)
    }
}
