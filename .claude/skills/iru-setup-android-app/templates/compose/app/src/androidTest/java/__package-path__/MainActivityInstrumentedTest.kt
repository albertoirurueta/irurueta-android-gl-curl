package <namespace>

import android.os.Build
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun enableAccessibilityChecks() {
        // Level from the `uiAccessibility` runner argument iru-android-test passes (.ui-quality.yml); API 34+ only.
        val level = InstrumentationRegistry.getArguments().getString("uiAccessibility", "enforce")
        if (level != "off" && level != "none" && Build.VERSION.SDK_INT >= 34) {
            composeRule.enableAccessibilityChecks(
                AccessibilityValidator().setRunChecksFromRootView(true).apply {
                    if (level == "report") setThrowExceptionFor(null)
                },
            )
        }
    }

    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("<application-id>", appContext.packageName)
    }

    @Test
    fun greeting_isShown_andAccessible() {
        val appName = composeRule.activity.getString(R.string.app_name)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.greeting, appName))
            .tryPerformAccessibilityChecks()
    }
}
