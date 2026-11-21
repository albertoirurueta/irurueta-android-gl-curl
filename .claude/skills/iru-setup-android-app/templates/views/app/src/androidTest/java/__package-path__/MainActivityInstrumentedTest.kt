package <namespace>

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @get:Rule
    val scenarioRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("<application-id>", appContext.packageName)
    }

    @Test
    fun mainActivity_isAccessible() {
        // Level from the `uiAccessibility` runner argument iru-android-test passes (.ui-quality.yml).
        val level = InstrumentationRegistry.getArguments().getString("uiAccessibility", "enforce")
        if (level == "off" || level == "none") return
        AccessibilityChecks.enable().setRunChecksFromRootView(true).apply {
            if (level == "report") setThrowExceptionFor(null)
        }
        onView(isRoot()).check(AccessibilityChecks.accessibilityAssertion())
    }
}
