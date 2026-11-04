package com.v1per.sxdc.licenses

import com.v1per.sxdc.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Checks the checked-in licenses/components.tsv against the repository. Gradle runs module unit
 * tests with app/ as the working directory, hence the "../".
 */
class LicenseInventoryRepoTest {
    private val repo = File("..")
    private val licensesDir = File(repo, "licenses")

    private fun readRepoFile(relative: String): String {
        val file = File(repo, relative)
        if (!file.isFile) fail("$relative not found at ${file.absolutePath}")
        return file.readText()
    }

    private val components by lazy { ComponentsTsv.parse(readRepoFile("licenses/components.tsv")) }

    /** LICENSE is copied into assets/licenses/ from the repo root; every other file from licenses/. */
    private fun sourceOf(name: String): File = if (name == "LICENSE") File(repo, "LICENSE") else File(licensesDir, name)

    @Test
    fun everyReferencedFileExists() {
        val missing = components.flatMap { c -> c.files.filterNot { sourceOf(it).isFile }.map { "${c.name}: $it" } }
        assertEquals("License files referenced in components.tsv but missing", emptyList<String>(), missing)
    }

    @Test
    fun everyLicenseTextIsReferenced() {
        val referenced = components.flatMap { it.files }.toSet()
        val unreferenced = licensesDir.listFiles().orEmpty()
            .map { it.name }
            .filter { it != "components.tsv" && it !in referenced }
        assertEquals("Files in licenses/ that no component references", emptyList<String>(), unreferenced)
    }

    @Test
    fun appRowIsGpl3OrLaterWithTheRootLicense() {
        val app = components.single { it.name == "sXdc" }
        assertEquals("GPL-3.0-or-later", app.license)
        assertEquals(listOf("LICENSE"), app.files)
        assertEquals("sXdc row version must equal versionName", BuildConfig.VERSION_NAME, app.version)
    }

    @Test
    fun pythonPinsMatchComponentVersions() {
        val pins = readRepoFile("app/requirements-android.txt").lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .associate { line ->
                val spec = line.substringBefore(' ')
                assertTrue("Not an exact pin: $spec", spec.contains("=="))
                spec.substringBefore("==") to spec.substringAfter("==")
            }
        assertEquals(setOf("yt-dlp", "mutagen"), pins.keys)
        pins.forEach { (name, version) ->
            val row = components.singleOrNull { it.name == name }
            assertEquals("Version of $name in components.tsv", version, row?.version)
        }
    }

    @Test
    fun everyPinCarriesASha256Hash() {
        readRepoFile("app/requirements-android.txt").lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .forEach { assertTrue("Unhashed pin: $it", Regex("--hash=sha256:[0-9a-f]{64}").containsMatchIn(it)) }
    }
}
