package <namespace>

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import <namespace>.testing.UiScreenshots
import <namespace>.ui.theme.AppTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi")
class GreetingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun greeting_showsLocalizedName() {
        composeRule.setContent { AppTheme { Greeting("Ada") } }

        composeRule.onNodeWithText("Hello, Ada!").assertIsDisplayed()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun greeting_capturesEveryDirectionAndTheme() {
        var cell by mutableStateOf(UiScreenshots.matrix.first())
        composeRule.setContent {
            val direction = if (cell.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                AppTheme(darkTheme = cell.isDark) {
                    Surface { Greeting(if (cell.isRtl) "عائشة" else "Ada") }
                }
            }
        }
        for (variant in UiScreenshots.matrix) {
            cell = variant
            composeRule.waitForIdle()
            val file = UiScreenshots.file(UiScreenshots.Kind.COMPONENT, "Greeting", "default", variant)
            composeRule.onRoot().captureRoboImage(file.path, roborazziOptions = UiScreenshots.recordOptions)
            UiScreenshots.accessibilityOptions?.let { composeRule.onRoot().checkRoboAccessibility(it) }
        }
    }
}
