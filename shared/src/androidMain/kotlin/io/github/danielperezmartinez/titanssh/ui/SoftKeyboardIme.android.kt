package io.github.danielperezmartinez.titanssh.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Compose exposes neither flag, so the request that opens the input
 * connection is wrapped to edit the [EditorInfo] Compose fills: always
 * `IME_FLAG_NO_PERSONALIZED_LEARNING`, and without [suggestions] the
 * `NO_SUGGESTIONS` flag plus the visible-password variation, as terminal
 * apps such as Termux do, since not every keyboard honours the flag. The field
 * restarts its input when its keyboard options change, so a flip of
 * [suggestions] reaches the keyboard at once.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun SoftKeyboardIme(suggestions: Boolean, content: @Composable () -> Unit) {
    val current by rememberUpdatedState(suggestions)
    val interceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            val modified = PlatformTextInputMethodRequest { outAttributes ->
                request.createInputConnection(outAttributes).also {
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    if (!current) {
                        val type = outAttributes.inputType
                        if (type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
                            outAttributes.inputType = (type and InputType.TYPE_MASK_VARIATION.inv()) or
                                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }
                    }
                }
            }
            nextHandler.startInputMethod(modified)
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}
