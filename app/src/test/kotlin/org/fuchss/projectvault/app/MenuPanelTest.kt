package org.fuchss.projectvault.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * Layout checks for [MenuPanel] under the constraints a real popup imposes.
 *
 * `ScreenshotTest` renders whole screens but **cannot** cover this: popups aren't capturable
 * headlessly, which is why the panel is a standalone composable in the first place. So the popup's
 * measurement environment is recreated here instead.
 */
class MenuPanelTest {
    private val longList = listOf(
        "Abos & Digitales", "Bargeld", "Drogerie & Gesundheit", "Events & Kultur", "Freizeit & Sport",
        "Lebensmittel", "Mobilität", "Reisen & Urlaub", "Restaurant & Café", "Shopping",
        "Sonstiges", "Strom & Nebenkosten", "Tanken", "Telefon & Internet", "Versicherung", "Wohnen & Miete",
    )

    /**
     * Regression: the category picker crashed on open with
     * `IllegalStateException: Size(8 x 2147483647) is out of range`.
     *
     * Material's `DropdownMenuContent` wraps its content in `Modifier.width(IntrinsicSize.Max)`,
     * which runs an intrinsic measure pass that hands children *infinite* height constraints. Compose
     * Desktop's `VerticalScrollbar` takes `constraints.maxHeight` verbatim and throws on it, so the
     * panel's scroll indicator must survive an unbounded measurement.
     */
    @Test
    fun `a scrolling menu survives a dropdown's intrinsic-width measurement`() {
        renderInDropdownEnvironment {
            MenuPanel {
                longList.forEach { VaultMenuItem(it, leadingDot = Color(0xFF8E24AA), onClick = {}) }
                VaultMenuDivider()
                VaultMenuItem("Manage categories…", emphasis = true, onClick = {})
            }
        }
    }

    /** The same path with nothing to scroll — the indicator must stay out of the way. */
    @Test
    fun `a short menu measures cleanly too`() {
        renderInDropdownEnvironment {
            MenuPanel {
                listOf("All", "Uncategorized", "To review").forEach { VaultMenuItem(it, onClick = {}) }
            }
        }
    }

    /**
     * Recreates `androidx.compose.material3.DropdownMenuContent`'s modifier chain — the intrinsic
     * width query plus the outer vertical scroll — and renders a few frames so layout settles.
     * Throws if composition, measurement or drawing fails.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderInDropdownEnvironment(content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 900, height = 800, density = Density(2f)) {
            VaultTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier
                            .padding(vertical = 8.dp)
                            .width(IntrinsicSize.Max)
                            .verticalScroll(rememberScrollState()),
                    ) { content() }
                }
            }
        }
        try {
            // Several frames: the indicator only draws once layout has published ScrollState.maxValue.
            scene.render(0L)
            scene.render(16_000_000L)
            scene.render(32_000_000L)
        } finally {
            scene.close()
        }
    }
}
