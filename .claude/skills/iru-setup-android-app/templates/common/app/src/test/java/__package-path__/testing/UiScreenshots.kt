package <namespace>.testing

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import java.io.File

/**
 * Shared UI-test helper: screenshot paths, the `.ui-quality.yml` settings and the capture matrix.
 *
 * Screenshots go to `UI_SCREENSHOTS_DIR` when set, otherwise to `<repository root>/.ui-screenshots`, as
 * `<kind>/<Id>/<state>--<direction>--<theme>[--<variant>…].png`.
 */
object UiScreenshots {
    /** What a capture shows. */
    enum class Kind { COMPONENT, SCREEN, FLOW }

    /** Layout direction of a capture. */
    enum class Direction { LTR, RTL }

    /** Theme of a capture. */
    enum class Theme { LIGHT, DARK, HIGH_CONTRAST }

    /** Level of the automated accessibility checks. */
    enum class Accessibility { ENFORCE, REPORT, OFF, NONE }

    /** One cell of the capture matrix. */
    data class Variant(val direction: Direction, val theme: Theme) {
        /** Whether this cell renders right-to-left. */
        val isRtl: Boolean get() = direction == Direction.RTL

        /** Whether this cell renders the dark theme. */
        val isDark: Boolean get() = theme == Theme.DARK
    }

    private val segment = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val setting = Regex("""^\s*(accessibility|directions|themes)\s*:\s*([^#]*)""")

    /** Repository root: the nearest ancestor holding `.git`, else `settings.gradle(.kts)`, else the working dir. */
    val repositoryRoot: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        generateSequence(start) { it.parentFile }.firstOrNull { File(it, ".git").exists() }
            ?: generateSequence(start) { it.parentFile }.firstOrNull {
                File(it, "settings.gradle.kts").exists() || File(it, "settings.gradle").exists()
            }
            ?: start
    }

    /** Directory every capture is written under. */
    val rootDir: File by lazy {
        System.getenv("UI_SCREENSHOTS_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(repositoryRoot, ".ui-screenshots")
    }

    private val settings: Map<String, String> by lazy {
        val file = File(repositoryRoot, ".ui-quality.yml")
        if (!file.isFile) emptyMap()
        else file.readLines().mapNotNull { setting.find(it) }.associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private fun list(key: String): List<String>? =
        settings[key]?.trim('[', ']', ' ')?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

    /** Accessibility level from `.ui-quality.yml` (default `enforce`). */
    val accessibility: Accessibility by lazy {
        settings["accessibility"]?.let { Accessibility.valueOf(it.uppercase()) } ?: Accessibility.ENFORCE
    }

    /** Directions × themes to capture, from `.ui-quality.yml` (default `[ltr, rtl]` × `[light, dark]`). */
    val matrix: List<Variant> by lazy {
        val directions = list("directions")?.map { Direction.valueOf(it.uppercase()) } ?: Direction.entries
        val themes = list("themes")?.map { Theme.valueOf(it.uppercase().replace('-', '_')) }
            ?: listOf(Theme.LIGHT, Theme.DARK)
        directions.flatMap { d -> themes.map { t -> Variant(d, t) } }
    }

    /**
     * Returns (and creates the parent of) the PNG file for one capture.
     *
     * @param kind component, screen or flow.
     * @param id type name exactly as in code (`OrderRow`), or a kebab-case flow name.
     * @param state kebab-case visual state (`default`, `loading`, `01-cart`, …).
     * @param variant direction and theme of the capture.
     * @param extra optional further variants (`phone`, `large-text`, …).
     * @return the file to write.
     * @throws IllegalArgumentException if a segment is not kebab-case.
     */
    fun file(kind: Kind, id: String, state: String, variant: Variant, vararg extra: String): File {
        require(id.isNotBlank() && !id.contains('/')) { "Invalid id '$id'" }
        (listOf(state) + extra).forEach { require(segment.matches(it)) { "'$it' is not kebab-case" } }
        val name = (listOf(state, variant.direction.slug(), variant.theme.slug()) + extra).joinToString("--")
        return File(rootDir, "${kind.slug()}/$id/$name.png").also { it.parentFile?.mkdirs() }
    }

    /** Roborazzi options that always write the capture, whatever the `roborazzi.test.*` properties say. */
    @OptIn(ExperimentalRoborazziApi::class)
    val recordOptions: RoborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)

    /** Roborazzi ATF options for the configured level, or `null` when the checks are `off`. */
    @OptIn(ExperimentalRoborazziApi::class)
    val accessibilityOptions: RoborazziATFAccessibilityCheckOptions? by lazy {
        when (accessibility) {
            Accessibility.ENFORCE -> RoborazziATFAccessibilityCheckOptions(
                failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Error,
            )
            Accessibility.REPORT -> RoborazziATFAccessibilityCheckOptions(
                failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.LogOnly,
            )
            Accessibility.OFF, Accessibility.NONE -> null
        }
    }

    private fun Enum<*>.slug(): String = name.lowercase().replace('_', '-')
}
