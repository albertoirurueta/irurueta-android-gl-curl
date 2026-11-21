package <namespace>

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import <namespace>.testing.UiScreenshots
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi")
class MainActivityScreenTest {
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun mainActivity_capturesEveryDirectionAndTheme() {
        for (variant in UiScreenshots.matrix) {
            val direction = if (variant.isRtl) "ldrtl" else "ldltr"
            val night = if (variant.isDark) "night" else "notnight"
            RuntimeEnvironment.setQualifiers("+$direction-$night")
            ActivityScenario.launch(MainActivity::class.java).use {
                val file = UiScreenshots.file(UiScreenshots.Kind.SCREEN, "MainActivity", "default", variant)
                onView(isRoot()).captureRoboImage(file.path, roborazziOptions = UiScreenshots.recordOptions)
                UiScreenshots.accessibilityOptions?.let { onView(isRoot()).checkRoboAccessibility(it) }
            }
        }
    }
}
