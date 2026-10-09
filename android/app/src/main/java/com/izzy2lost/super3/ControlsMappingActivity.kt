package com.izzy2lost.super3

import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlin.concurrent.thread

/**
 * Remaps physical controller bindings by editing the Input* keys in Supermodel.ini, either in
 * [ Global ] or in a per-game [ romname ] section (which overrides Global for that game only).
 */
class ControlsMappingActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_GAME_NAME = "com.izzy2lost.super3.extra.CONTROLS_GAME_NAME"
        const val EXTRA_GAME_TITLE = "com.izzy2lost.super3.extra.CONTROLS_GAME_TITLE"

        // Disables the hardwired controller Start/Back/Guide/D-pad shortcuts in the native input
        // system so only the mappings set here apply.
        private const val KEY_CONTROLLER_SHORTCUTS = "AndroidControllerShortcuts"
        private const val GLOBAL = "Global"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var adapter: ControlsAdapter

    private var gameName: String? = null
    private var groups: List<ControlsCatalog.Group> = emptyList()
    private var iniDoc: DocumentFile? = null
    private var iniLines: MutableList<String> = mutableListOf()
    private var defaultLines: List<String> = emptyList()
    private var busy = false

    private val sectionName: String get() = gameName ?: GLOBAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        setContentView(R.layout.activity_controls)

        toolbar = findViewById(R.id.controls_toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this) { finish() }

        val treeUri = intent.getStringExtra(IniEditorActivity.EXTRA_TREE_URI)?.let(Uri::parse)
        if (treeUri == null) {
            Toast.makeText(this, "Data folder not set", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        gameName = intent.getStringExtra(EXTRA_GAME_NAME)?.takeIf { it.isNotBlank() }
        val gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE)
        toolbar.subtitle = if (gameName == null) "All games" else (gameTitle ?: gameName)

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_controls_reset_all -> {
                    confirmResetAll()
                    true
                }
                else -> false
            }
        }

        adapter = ControlsAdapter(onClick = ::showCaptureDialog, onLongClick = ::showBindingOptions)
        findViewById<RecyclerView>(R.id.controls_list).adapter = adapter

        load(treeUri)
    }

    private fun load(treeUri: Uri) {
        setBusy(true, "Loading...")
        val game = gameName
        thread(name = "Super3ControlsLoad") {
            val doc = IniDocumentStore.ensureIniDocument(this, treeUri)
            val text = doc?.let { IniDocumentStore.readIniText(this, it) }
            val defaults =
                runCatching {
                    assets.open("Config/Supermodel.ini").bufferedReader().use { it.readText() }
                }.getOrNull().orEmpty()
            val types =
                game?.let {
                    val gamesXml = File(getExternalFilesDir(null), "super3/Config/Games.xml")
                    if (gamesXml.exists()) GameInputsIndex.inputTypes(gamesXml.absolutePath, it) else null
                }
            runOnUiThread {
                if (doc == null || text == null) {
                    setBusy(false, null)
                    Toast.makeText(this, "Failed to load Supermodel.ini", Toast.LENGTH_SHORT).show()
                    finish()
                    return@runOnUiThread
                }
                iniDoc = doc
                iniLines = text.split("\n").toMutableList()
                defaultLines = defaults.split("\n")
                groups = ControlsCatalog.groupsFor(types)
                setBusy(false, null)
                refreshRows()
            }
        }
    }

    private fun refreshRows() {
        val rows = ArrayList<Row>()
        for (group in groups) {
            rows.add(Row.Header(group.title))
            for (def in group.inputs) {
                val own = IniSections.read(iniLines, sectionName, def.iniKey)
                val value = own ?: effectiveGlobalValue(def)
                val source =
                    when {
                        gameName == null -> ""
                        own != null -> "This game"
                        else -> "From global"
                    }
                rows.add(Row.Binding(def, ControlsCatalog.describeControllerBindings(value), source))
            }
        }
        adapter.submit(rows)
    }

    private fun effectiveGlobalValue(def: ControlsCatalog.InputDef): String? {
        return IniSections.read(iniLines, GLOBAL, def.iniKey) ?: defaultGlobalValue(def)
    }

    private fun defaultGlobalValue(def: ControlsCatalog.InputDef): String? {
        return IniSections.read(defaultLines, GLOBAL, def.iniKey)
    }

    private fun effectiveValue(def: ControlsCatalog.InputDef): String? {
        return IniSections.read(iniLines, sectionName, def.iniKey) ?: effectiveGlobalValue(def)
    }

    // --- Capture -------------------------------------------------------------------------------

    private fun showCaptureDialog(def: ControlsCatalog.InputDef, append: Boolean = false) {
        if (busy) return
        val prompt =
            when (def.kind) {
                ControlsCatalog.Kind.AXIS -> "Move a stick or trigger on your controller."
                ControlsCatalog.Kind.BUTTON -> "Press a button, D-pad direction, trigger or stick direction."
            }
        val playerNote = if (def.player == 2) "\n\nThis is a player 2 input and will be bound to controller 2." else ""
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(if (append) "Add binding: ${def.label}" else def.label)
                .setMessage(prompt + playerNote)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear") { _, _ -> clearBinding(def) }
                .create()

        var captured = false
        fun capture(token: String) {
            if (captured) return
            captured = true
            dialog.dismiss()
            applyBinding(def, token, append)
        }

        dialog.setOnShowListener {
            val window = dialog.window ?: return@setOnShowListener
            val original = window.callback
            window.callback =
                object : Window.Callback by original {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (isControllerKey(event)) {
                            val token = ControlsCatalog.tokenForKey(event.keyCode, def)
                            if (token != null) {
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) capture(token)
                                return true
                            }
                        }
                        return original.dispatchKeyEvent(event)
                    }

                    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                        if (event.action == MotionEvent.ACTION_MOVE) {
                            val token = ControlsCatalog.tokenForMotion(event, def)
                            if (token != null) {
                                capture(token)
                                return true
                            }
                        }
                        return original.dispatchGenericMotionEvent(event)
                    }
                }
        }
        dialog.show()
    }

    private fun isControllerKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
        return event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_DPAD) ||
            KeyEvent.isGamepadButton(event.keyCode)
    }

    private fun showBindingOptions(def: ControlsCatalog.InputDef) {
        if (busy) return
        val resetLabel = if (gameName == null) "Reset to default" else "Use global binding"
        val options = arrayOf("Add another binding", "Clear controller bindings", resetLabel)
        MaterialAlertDialogBuilder(this)
            .setTitle(def.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCaptureDialog(def, append = true)
                    1 -> clearBinding(def)
                    else -> resetBinding(def)
                }
            }
            .show()
    }

    // --- Editing -------------------------------------------------------------------------------

    private fun applyBinding(def: ControlsCatalog.InputDef, token: String, append: Boolean) {
        val current = ControlsCatalog.splitTokens(effectiveValue(def))
        val kept = if (append) current else current.filterNot(ControlsCatalog::isControllerToken)
        val tokens = if (kept.any { it.equals(token, ignoreCase = true) }) kept else kept + token
        save(
            sets = mapOf(def.iniKey to ControlsCatalog.joinTokens(tokens), KEY_CONTROLLER_SHORTCUTS to "0"),
            message = "${def.label}: ${ControlsCatalog.describeToken(token)}",
        )
    }

    private fun clearBinding(def: ControlsCatalog.InputDef) {
        val kept = ControlsCatalog.splitTokens(effectiveValue(def)).filterNot(ControlsCatalog::isControllerToken)
        save(
            sets = mapOf(def.iniKey to ControlsCatalog.joinTokens(kept), KEY_CONTROLLER_SHORTCUTS to "0"),
            message = "${def.label}: cleared",
        )
    }

    private fun resetBinding(def: ControlsCatalog.InputDef) {
        val default = if (gameName == null) defaultGlobalValue(def) else null
        if (default != null) {
            save(sets = mapOf(def.iniKey to default), message = "${def.label}: reset")
        } else {
            save(removes = setOf(def.iniKey), message = "${def.label}: reset")
        }
    }

    private fun confirmResetAll() {
        if (busy) return
        val message =
            if (gameName == null) {
                "Restore the default controller mapping for all games? Per-game mappings are kept."
            } else {
                "Remove this game's custom mapping so it uses the global mapping again?"
            }
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset all")
            .setMessage(message)
            .setPositiveButton("Reset") { _, _ -> resetAll() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetAll() {
        val allInputs = ControlsCatalog.groups.flatMap { it.inputs }
        val sets = HashMap<String, String>()
        val removes = HashSet<String>()
        removes.add(KEY_CONTROLLER_SHORTCUTS)
        for (def in allInputs) {
            val default = if (gameName == null) defaultGlobalValue(def) else null
            if (default != null) sets[def.iniKey] = default else removes.add(def.iniKey)
        }
        save(sets = sets, removes = removes, message = "Controls reset")
    }

    private fun save(
        sets: Map<String, String> = emptyMap(),
        removes: Set<String> = emptySet(),
        message: String,
    ) {
        if (busy) return
        val doc = iniDoc ?: return
        val updated = IniSections.update(iniLines, sectionName, sets, removes)
        setBusy(true, "Saving...")
        thread(name = "Super3ControlsSave") {
            val ok = IniDocumentStore.writeIniText(this, doc, updated.joinToString("\n"))
            runOnUiThread {
                setBusy(false, null)
                if (!ok) {
                    Toast.makeText(this, "Failed to save Supermodel.ini", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                iniLines = updated
                refreshRows()
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setBusy(isBusy: Boolean, status: String?) {
        busy = isBusy
        toolbar.title = status ?: "Controller mapping"
    }

    // --- List ----------------------------------------------------------------------------------

    private sealed class Row {
        data class Header(val title: String) : Row()

        data class Binding(
            val def: ControlsCatalog.InputDef,
            val description: String,
            val source: String,
        ) : Row()
    }

    private class ControlsAdapter(
        private val onClick: (ControlsCatalog.InputDef) -> Unit,
        private val onLongClick: (ControlsCatalog.InputDef) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Header) 0 else 1

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = if (viewType == 0) R.layout.item_control_header else R.layout.item_control_binding
            return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            when (val row = rows[position]) {
                is Row.Header -> (view as TextView).text = row.title
                is Row.Binding -> {
                    view.findViewById<TextView>(R.id.control_label).text = row.def.label
                    view.findViewById<TextView>(R.id.control_binding).text = row.description
                    val source = view.findViewById<TextView>(R.id.control_source)
                    source.text = row.source
                    source.visibility = if (row.source.isEmpty()) View.GONE else View.VISIBLE
                    view.setOnClickListener { onClick(row.def) }
                    view.setOnLongClickListener {
                        onLongClick(row.def)
                        true
                    }
                }
            }
        }
    }
}

/** Minimal section-aware Supermodel.ini line editing ("[ Section ]" headers, "Key = Value" lines). */
internal object IniSections {
    fun read(lines: List<String>, section: String, key: String): String? {
        val (start, end) = bounds(lines, section) ?: return null
        val rx = keyRegex(key)
        for (i in (start + 1) until end) {
            val line = lines[i]
            if (line.trim().startsWith(";")) continue
            if (rx.containsMatchIn(line)) return line.substring(line.indexOf('=') + 1).trim()
        }
        return null
    }

    fun update(
        lines: List<String>,
        section: String,
        sets: Map<String, String>,
        removes: Set<String>,
    ): MutableList<String> {
        val (start, end) =
            bounds(lines, section) ?: run {
                val out = lines.toMutableList()
                if (sets.isEmpty()) return out
                if (out.isNotEmpty() && out.last().isNotBlank()) out.add("")
                out.add("[ $section ]")
                for ((k, v) in sets) out.add("$k = $v")
                return out
            }

        val out = ArrayList<String>(lines.size + sets.size)
        out.addAll(lines.take(start + 1))

        val written = HashSet<String>()
        var lastContent = start
        for (i in (start + 1) until end) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith(";")) {
                out.add(line)
                continue
            }
            val removeKey = removes.firstOrNull { keyRegex(it).containsMatchIn(line) }
            if (removeKey != null && !sets.keys.any { it.equals(removeKey, ignoreCase = true) }) continue
            val setEntry = sets.entries.firstOrNull { keyRegex(it.key).containsMatchIn(line) }
            if (setEntry != null) {
                out.add("${setEntry.key} = ${setEntry.value}")
                written.add(setEntry.key.lowercase())
            } else {
                out.add(line)
            }
            lastContent = out.size - 1
        }

        // Append new keys right after the section's last entry, ahead of trailing blank lines.
        val additions = sets.filterKeys { it.lowercase() !in written }.map { (k, v) -> "$k = $v" }
        out.addAll(lastContent + 1, additions)

        out.addAll(lines.drop(end))
        return out
    }

    private fun keyRegex(key: String) = Regex("^\\s*${Regex.escape(key)}\\s*=", RegexOption.IGNORE_CASE)

    private fun bounds(lines: List<String>, section: String): Pair<Int, Int>? {
        val target = section.lowercase()
        val start = lines.indexOfFirst { sectionName(it) == target }
        if (start < 0) return null
        var end = lines.size
        for (i in (start + 1) until lines.size) {
            if (sectionName(lines[i]) != null) {
                end = i
                break
            }
        }
        return start to end
    }

    private fun sectionName(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        return trimmed.substring(1, trimmed.length - 1).trim().lowercase()
    }
}
