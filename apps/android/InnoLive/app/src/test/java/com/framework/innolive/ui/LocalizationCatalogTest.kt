package com.framework.innolive.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class LocalizationCatalogTest {
    private val resourceDirectory = File(
        checkNotNull(System.getProperty("innolive.android.resDir")) {
            "The Gradle test task must provide the Android resource directory."
        },
    )

    @Test
    fun translatedCatalogsMatchKoreanKeysAndFormatArguments() {
        val koreanCatalog = readCatalog("values")

        listOf("en", "ja").forEach { language ->
            val translatedCatalog = readCatalog("values-$language")

            assertEquals(
                "$language string keys must match the Korean catalog",
                koreanCatalog.strings.keys,
                translatedCatalog.strings.keys,
            )
            assertEquals(
                "$language plural keys must match the Korean catalog",
                koreanCatalog.plurals.keys,
                translatedCatalog.plurals.keys,
            )

            koreanCatalog.strings.forEach { (name, koreanValue) ->
                assertEquals(
                    "$language string '$name' has different format arguments",
                    formatArguments(koreanValue),
                    formatArguments(checkNotNull(translatedCatalog.strings[name])),
                )
            }
            koreanCatalog.plurals.forEach { (name, koreanValues) ->
                val translatedValues = checkNotNull(translatedCatalog.plurals[name])
                assertEquals(
                    "Korean plural '$name' must use one format contract for every quantity",
                    formatContract(koreanValues),
                    formatContract(translatedValues),
                )
            }
        }
    }

    private fun readCatalog(directoryName: String): Catalog {
        val directory = File(resourceDirectory, directoryName)
        check(directory.isDirectory) { "Missing localization catalog: ${directory.absolutePath}" }
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, List<String>>()
        val files = directory.listFiles { file -> file.extension == "xml" }
            ?.sortedBy { it.name }
            .orEmpty()
        check(files.isNotEmpty()) { "No XML resources in ${directory.absolutePath}" }

        files.forEach { file ->
            val document = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder().parse(file)
            val resourceNodes = document.documentElement.childNodes
            for (index in 0 until resourceNodes.length) {
                val node = resourceNodes.item(index) as? Element ?: continue
                when (node.tagName) {
                    "string" -> strings.putUnique(node.getAttribute("name"), node.textContent)
                    "plurals" -> plurals.putUnique(
                        node.getAttribute("name"),
                        buildList {
                            val itemNodes = node.childNodes
                            for (itemIndex in 0 until itemNodes.length) {
                                val item = itemNodes.item(itemIndex) as? Element ?: continue
                                if (item.tagName == "item") add(item.textContent)
                            }
                        },
                    )
                }
            }
        }
        return Catalog(strings, plurals)
    }

    private fun <T> MutableMap<String, T>.putUnique(name: String, value: T) {
        require(name.isNotBlank()) { "Localization resource name is required." }
        require(put(name, value) == null) { "Duplicate localization resource: $name" }
    }

    private fun formatContract(values: List<String>): Map<Int, String> {
        val contracts = values.map(::formatArguments).distinct()
        require(contracts.size == 1) { "Plural resource has inconsistent format arguments: $values" }
        return contracts.single()
    }

    private fun formatArguments(value: String): Map<Int, String> {
        var nextArgumentIndex = 1
        var previousArgumentIndex: Int? = null
        val arguments = sortedMapOf<Int, String>()

        formatSpecifier.findAll(value).forEach { match ->
            val conversion = match.groups[4]?.value.orEmpty()
            if (conversion == "%" || conversion == "n") return@forEach

            val argumentIndex = match.groups[1]?.value?.toIntOrNull()
                ?: if (match.groups[2]?.value?.contains('<') == true) {
                    checkNotNull(previousArgumentIndex) { "Format reuses a missing argument in '$value'" }
                } else {
                    nextArgumentIndex++ - 1
                }
            val type = (match.groups[3]?.value.orEmpty() + conversion).lowercase()
            val existingType = arguments.put(argumentIndex, type)
            require(existingType == null || existingType == type) {
                "Format argument $argumentIndex has conflicting types in '$value'"
            }
            previousArgumentIndex = argumentIndex
        }
        return arguments
    }

    private data class Catalog(
        val strings: Map<String, String>,
        val plurals: Map<String, List<String>>,
    )

    private companion object {
        val formatSpecifier = Regex(
            "%(?:(\\d+)\\$)?([-#+ 0,(<]*)(?:\\d+)?(?:\\.\\d+)?([tT]?)([a-zA-Z])",
        )
    }
}
