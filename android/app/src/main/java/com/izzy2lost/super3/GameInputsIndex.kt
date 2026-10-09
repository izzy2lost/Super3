package com.izzy2lost.super3

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream

private data class GameInputs(
    val name: String,
    val parent: String?,
    val inputTypes: Set<String>,
)

object GameInputsIndex {
    @Volatile
    private var cachedFromPath: String? = null

    @Volatile
    private var cached: Map<String, GameInputs>? = null

    fun hasAnyInputType(gamesXmlPath: String, gameName: String, types: Set<String>): Boolean {
        val index = load(gamesXmlPath)
        val visited = HashSet<String>(8)
        var cur: String? = gameName
        while (cur != null && visited.add(cur)) {
            val def = index[cur]
            if (def != null) {
                if (def.inputTypes.any { it in types }) return true
                cur = def.parent
            } else {
                break
            }
        }
        return false
    }

    // Input types declared by the game, falling back to its parent's when it has none.
    fun inputTypes(gamesXmlPath: String, gameName: String): Set<String>? {
        val index = load(gamesXmlPath)
        val visited = HashSet<String>(8)
        var cur: String? = gameName
        while (cur != null && visited.add(cur)) {
            val def = index[cur] ?: return null
            if (def.inputTypes.isNotEmpty()) return def.inputTypes
            cur = def.parent
        }
        return null
    }

    @Synchronized
    private fun load(gamesXmlPath: String): Map<String, GameInputs> {
        val existingPath = cachedFromPath
        val existing = cached
        if (existing != null && existingPath == gamesXmlPath) return existing

        val file = File(gamesXmlPath)
        val parsed =
            if (file.exists()) {
                file.inputStream().use { parse(it) }
            } else {
                emptyMap()
            }
        cachedFromPath = gamesXmlPath
        cached = parsed
        return parsed
    }

    private fun parse(input: InputStream): Map<String, GameInputs> {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)

        val out = HashMap<String, GameInputs>(256)
        var event = parser.eventType

        var currentGameName: String? = null
        var currentParent: String? = null
        var inInputs = false
        var types: MutableSet<String>? = null

        fun finishGame() {
            val name = currentGameName ?: return
            out[name] = GameInputs(name = name, parent = currentParent, inputTypes = types?.toSet().orEmpty())
        }

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "game" -> {
                            currentGameName = parser.getAttributeValue(null, "name")
                            currentParent = parser.getAttributeValue(null, "parent")
                            inInputs = false
                            types = null
                        }

                        "inputs" -> {
                            inInputs = true
                            if (types == null) types = LinkedHashSet()
                        }

                        "input" -> {
                            if (inInputs) {
                                val t = parser.getAttributeValue(null, "type")?.trim().orEmpty()
                                if (t.isNotEmpty()) {
                                    if (types == null) types = LinkedHashSet()
                                    types?.add(t)
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "inputs" -> inInputs = false
                        "game" -> {
                            finishGame()
                            currentGameName = null
                            currentParent = null
                            inInputs = false
                            types = null
                        }
                    }
                }
            }

            event = parser.next()
        }

        return out
    }
}

