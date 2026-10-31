package com.v1per.sxdc.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Colors live in ui/theme/. Gradle runs module unit tests with app/ as the working directory. */
class NoRawColorsTest {

    private val raw = Regex(
        """Color\(\s*0x|Color\.(Red|Green|Blue|Black|White|Gray|DarkGray|LightGray|Yellow|Cyan|Magenta|Transparent|Unspecified)\b""",
    )

    @Test
    fun noRawColorsOutsideTheme() {
        val root = File("src/main/java")
        if (!root.isDirectory) {
            fail("Source root not found at ${root.absolutePath} (working dir ${File("").absolutePath})")
        }
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "/ui/theme/" !in it.invariantSeparatorsPath }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { raw.containsMatchIn(it.value) }
                    .map { "${f.path}:${it.index + 1}" }
            }
            .toList()
        assertTrue("Raw colors outside ui/theme/: $offenders", offenders.isEmpty())
    }
}
