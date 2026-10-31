package com.v1per.sxdc

import org.junit.Assert.fail
import java.io.File
import java.util.Properties

/**
 * Reads tests/golden/ytdlp-options.properties, shared with the Python suite.
 *
 * Gradle runs module unit tests with app/ as the working directory, hence the "../".
 */
object GoldenOptions {

    private const val RELATIVE_PATH = "../tests/golden/ytdlp-options.properties"

    val properties: Properties by lazy {
        val file = File(RELATIVE_PATH)
        if (!file.isFile) {
            fail("Golden file not found at ${file.absolutePath} (working dir ${File("").absolutePath})")
        }
        Properties().apply { file.inputStream().use { load(it) } }
    }

    fun string(key: String): String =
        properties.getProperty(key) ?: throw AssertionError("Golden key '$key' missing")

    fun flag(key: String): Boolean = when (val v = string(key)) {
        "true" -> true
        "false" -> false
        else -> throw AssertionError("Golden key '$key' is not a boolean: $v")
    }

    val errorKinds: List<String> get() = string("errorKinds").split(',').map { it.trim() }
}
