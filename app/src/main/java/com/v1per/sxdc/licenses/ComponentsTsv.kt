package com.v1per.sxdc.licenses

/** One shipped third-party component, as listed in licenses/components.tsv. */
data class LicenseComponent(
    val name: String,
    val version: String,
    val license: String,
    val url: String,
    /** License text files under assets/licenses/, in display order. Empty when none is bundled. */
    val files: List<String>,
    val notes: String,
)

/**
 * Parser for licenses/components.tsv (tab-separated, header row first).
 *
 * Columns are found by header name, so their order does not matter and extra columns (such as
 * `maven`, used only by scripts/check_license_inventory.py) are ignored. `name`, `version` and
 * `license` are required; every other column is optional, and a row that stops early (trailing
 * empty cells trimmed) reads the missing cells as empty. Blank lines are ignored. The `file`
 * cell may hold several comma-separated file names.
 */
object ComponentsTsv {

    const val ASSET_DIR = "licenses"
    const val ASSET_PATH = "$ASSET_DIR/components.tsv"

    private val REQUIRED = listOf("name", "version", "license")

    /** @throws IllegalArgumentException if the header lacks a required column or a row has no name. */
    fun parse(text: String): List<LicenseComponent> {
        val lines = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()
        require(lines.isNotEmpty()) { "components.tsv is empty" }

        val header = lines.first().split('\t').map { it.trim().lowercase() }
        REQUIRED.forEach { require(it in header) { "components.tsv has no '$it' column" } }

        return lines.drop(1).mapIndexed { index, line ->
            val cells = line.split('\t')
            fun cell(column: String): String {
                val i = header.indexOf(column)
                return if (i < 0) "" else cells.getOrNull(i)?.trim().orEmpty()
            }
            val name = cell("name")
            require(name.isNotEmpty()) { "components.tsv row ${index + 2} has no name" }
            LicenseComponent(
                name = name,
                version = cell("version"),
                license = cell("license"),
                url = cell("url"),
                files = cell("file").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                notes = cell("notes"),
            )
        }
    }
}
