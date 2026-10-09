package com.izzy2lost.super3

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.res.Configuration
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.GravityCompat
import androidx.core.widget.addTextChangedListener
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import java.io.File
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.concurrent.thread

enum class GameListViewMode { LIST, FLYERS }

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("super3_prefs", MODE_PRIVATE) }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var searchBar: SearchBar
    private lateinit var searchView: SearchView
    private lateinit var btnOpenDrawer: ImageButton
    private lateinit var btnViewMode: ImageButton
    private lateinit var btnThemeToggle: ImageButton
    private lateinit var btnAbout: MaterialButton

    private lateinit var gamesFolderText: TextView
    private lateinit var userFolderText: TextView
    private lateinit var gamesList: RecyclerView
    private lateinit var searchResultsList: RecyclerView
    private lateinit var statusText: TextView

    private lateinit var btnResolution: MaterialButton
    private lateinit var btnResolutionMatchDevice: MaterialButton
    private lateinit var btnWidescreen: MaterialButton
    private lateinit var btnWideBackground: MaterialButton
    private lateinit var btnReal3dRenderer: MaterialButton
    private lateinit var btnDaytonaTiming: MaterialButton

    private lateinit var gamesAdapter: GamesAdapter

    private var gamesTreeUri: Uri? = null
    private var userTreeUri: Uri? = null

    private var games: List<GameDef> = emptyList()
    private var zipDocs: Map<String, DocumentFile> = emptyMap()
    private var viewMode: GameListViewMode = GameListViewMode.FLYERS

    @Volatile
    private var scanning = false

    @Volatile
    private var launchingRomIntent = false

    @Volatile
    private var syncingFlyers = false

    @Volatile
    private var flyersStatusToken: Long = 0L

    private data class RomLaunchInfo(
        val uri: Uri,
        val displayName: String,
        val baseName: String,
        val file: File?,
        val size: Long?,
    )

    private val romIntentExtraKeys =
        listOf(
            "rom",
            "romPath",
            "rom_path",
            "ROM",
            "ROM_PATH",
            "game_path",
            "gamePath",
            "path",
            "file",
            "FILE",
            "uri",
            "URI",
        )

    private val romNameExtraKeys =
        listOf(
            "gameName",
            "game",
            "game_name",
            "romName",
            "rom_name",
            "ROM_NAME",
            "title",
        )

    private var pendingRomLaunch: RomLaunchInfo? = null

    private data class VideoSettings(
        val xResolution: Int,
        val yResolution: Int,
        val wideScreen: Boolean,
        val wideBackground: Boolean,
        val real3dEnabled: Boolean,
        val matchDevice: Boolean,
    )

    private data class ResolutionOption(val label: String, val x: Int, val y: Int)

    private val resolutionOptions =
        listOf(
            ResolutionOption("Native", 496, 384),
            ResolutionOption("2x", 992, 768),
            ResolutionOption("3x", 1488, 1152),
            ResolutionOption("4x", 1984, 1536),
            ResolutionOption("5x", 2480, 1920),
            ResolutionOption("6x", 2976, 2304),
            ResolutionOption("7x", 3472, 2688),
            ResolutionOption("8x", 3968, 3072),
        )

    private val pickGamesFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistTreePermission(uri)
                gamesTreeUri = uri
                prefs.edit().putString("gamesTreeUri", uri.toString()).apply()
                refreshUi()
            }
        }

    private val pickUserFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistTreePermission(uri)
                userTreeUri = uri
                prefs.edit().putString("userTreeUri", uri.toString()).apply()
                refreshUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyThemeFromPrefs()
        pendingRomLaunch = parseRomLaunchIntent(intent)
        if (!prefs.getBoolean("setup_complete", false)) {
            val hasGames = prefs.getString("gamesTreeUri", null) != null
            val hasData = prefs.getString("userTreeUri", null) != null
            if (hasGames && hasData) {
                prefs.edit().putBoolean("setup_complete", true).apply()
            } else if (pendingRomLaunch == null) {
                startActivity(Intent(this, SetupWizardActivity::class.java))
                finish()
                return
            }
        }
        applyImmersiveMode()
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.navigation_view)
        toolbar = findViewById(R.id.toolbar)
        searchBar = findViewById(R.id.search_bar)
        searchView = findViewById(R.id.search_view)
        btnOpenDrawer = findViewById(R.id.btn_open_drawer)
        btnViewMode = findViewById(R.id.btn_view_mode)
        btnThemeToggle = findViewById(R.id.btn_theme_toggle)
        gamesList = findViewById(R.id.games_list)
        searchResultsList = findViewById(R.id.search_results_list)

        // Get views from the navigation header
        val headerView = navigationView.getHeaderView(0)
        btnAbout = headerView.findViewById(R.id.btn_about)
        gamesFolderText = headerView.findViewById(R.id.games_folder_text)
        userFolderText = headerView.findViewById(R.id.user_folder_text)
        statusText = headerView.findViewById(R.id.status_text)

        val btnPickGamesFolder: MaterialButton = headerView.findViewById(R.id.btn_pick_games_folder)
        val btnPickUserFolder: MaterialButton = headerView.findViewById(R.id.btn_pick_user_folder)
        val btnRescan: MaterialButton = headerView.findViewById(R.id.btn_rescan)
        btnResolution = headerView.findViewById(R.id.btn_resolution)
        btnResolutionMatchDevice = headerView.findViewById(R.id.btn_resolution_match_device)
        btnWidescreen = headerView.findViewById(R.id.btn_widescreen)
        btnWideBackground = headerView.findViewById(R.id.btn_wide_background)
        btnReal3dRenderer = headerView.findViewById(R.id.btn_real3d_renderer)
        btnDaytonaTiming = headerView.findViewById(R.id.btn_daytona_timing)
        val btnShowTouchControls: MaterialButton = headerView.findViewById(R.id.btn_show_touch_controls)
        val btnShowShifterOverlay: MaterialButton = headerView.findViewById(R.id.btn_show_shifter_overlay)
        val btnGyroSteering: MaterialButton = headerView.findViewById(R.id.btn_gyro_steering)
        val btnGyroSensitivity: MaterialButton = headerView.findViewById(R.id.btn_gyro_sensitivity)
        val btnEditSupermodelIni: MaterialButton = headerView.findViewById(R.id.btn_edit_supermodel_ini)
        val btnControllerMapping: MaterialButton = headerView.findViewById(R.id.btn_controller_mapping)

        gamesAdapter =
            GamesAdapter(
                onClick = { item ->
                    if (item.launchable) {
                        launchGame(item.game)
                    } else {
                        Toast.makeText(this, item.status, Toast.LENGTH_SHORT).show()
                    }
                },
                onLongClick = { item -> showGameSettings(item.game) },
            )
        gamesList.adapter = gamesAdapter
        searchResultsList.adapter = gamesAdapter

        btnOpenDrawer.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        btnAbout.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showAboutDialog()
        }

        btnPickGamesFolder.setOnClickListener { pickGamesFolder.launch(null) }
        btnPickUserFolder.setOnClickListener { pickUserFolder.launch(null) }
        btnRescan.setOnClickListener { refreshUi() }

        btnEditSupermodelIni.setOnClickListener {
            val tree = userTreeUri
            if (tree == null) {
                Toast.makeText(this, "Pick a data folder first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            val options = arrayOf("Easy settings", "Raw text")
            MaterialAlertDialogBuilder(this)
                .setTitle("Edit Supermodel.ini")
                .setItems(options) { _, which ->
                    val target =
                        when (which) {
                            0 -> IniSettingsActivity::class.java
                            else -> IniEditorActivity::class.java
                        }
                    val intent =
                        Intent(this, target).apply {
                            putExtra(IniEditorActivity.EXTRA_TREE_URI, tree.toString())
                        }
                    startActivity(intent)
                }
                .show()
        }

        btnControllerMapping.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            openControllerMapping(null)
        }

        bindVideoSettingsUi()
        bindTimingUi()

        btnShowTouchControls.isChecked = prefs.getBoolean("overlay_controls_enabled", true)
        btnShowTouchControls.setOnClickListener {
            val enabled = btnShowTouchControls.isChecked
            prefs.edit().putBoolean("overlay_controls_enabled", enabled).apply()
            Toast.makeText(
                this,
                if (enabled) "Touch controls enabled" else "Touch controls hidden",
                Toast.LENGTH_SHORT,
            ).show()
        }

        btnShowShifterOverlay.isChecked = prefs.getBoolean("overlay_shifter_enabled", false)
        btnShowShifterOverlay.setOnClickListener {
            val enabled = btnShowShifterOverlay.isChecked
            prefs.edit().putBoolean("overlay_shifter_enabled", enabled).apply()
            Toast.makeText(
                this,
                if (enabled) "Shifter overlay enabled" else "Shifter overlay disabled",
                Toast.LENGTH_SHORT,
            ).show()
        }

        btnGyroSteering.isChecked = prefs.getBoolean("gyro_steering_enabled", false)
        btnGyroSteering.setOnClickListener {
            val enabled = btnGyroSteering.isChecked
            prefs.edit().putBoolean("gyro_steering_enabled", enabled).apply()
            Toast.makeText(
                this,
                if (enabled) "Gyro steering enabled (restart game to apply)" else "Gyro steering disabled",
                Toast.LENGTH_SHORT,
            ).show()
        }

        data class GyroPreset(val label: String, val scale: Float)
        val gyroPresets = listOf(
            GyroPreset("Less", 0.65f),
            GyroPreset("Normal", 1.0f),
            GyroPreset("More", 1.35f),
        )
        fun updateGyroSensitivityLabel() {
            val cur = prefs.getFloat("gyro_steering_sensitivity", 1.0f)
            val match = gyroPresets.minByOrNull { kotlin.math.abs(it.scale - cur) } ?: gyroPresets[1]
            btnGyroSensitivity.text = "Gyro sensitivity: ${match.label}"
        }
        updateGyroSensitivityLabel()
        btnGyroSensitivity.setOnClickListener {
            val cur = prefs.getFloat("gyro_steering_sensitivity", 1.0f)
            val idx = gyroPresets.indexOfFirst { kotlin.math.abs(it.scale - cur) < 0.01f }.let { if (it < 0) 1 else it }
            val next = gyroPresets[(idx + 1) % gyroPresets.size]
            prefs.edit().putFloat("gyro_steering_sensitivity", next.scale).apply()
            updateGyroSensitivityLabel()
            Toast.makeText(this, "Gyro sensitivity: ${next.label} (restart game to apply)", Toast.LENGTH_SHORT).show()
        }

        runCatching { searchView.setupWithSearchBar(searchBar) }
        styleSearchBarText()
        searchBar.setOnClickListener {
            searchView.show()
            searchView.requestFocusAndShowKeyboard()
        }
        searchView.setAutoShowKeyboard(true)

        val onSurface = MaterialColors.getColor(searchView, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant =
            MaterialColors.getColor(searchView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        searchView.editText.setTextColor(onSurface)
        searchView.editText.setHintTextColor(onSurfaceVariant)

        searchView.addTransitionListener { _, _, newState ->
            val searchActive = newState != SearchView.TransitionState.HIDDEN
            gamesList.visibility = if (searchActive) View.GONE else View.VISIBLE
            if (newState == SearchView.TransitionState.SHOWING || newState == SearchView.TransitionState.SHOWN) {
                searchView.requestFocusAndShowKeyboard()
            }
        }
        searchView.editText.addTextChangedListener { editable ->
            gamesAdapter.setFilter(editable?.toString().orEmpty())
        }

        viewMode = loadViewMode()
        btnViewMode.setOnClickListener {
            viewMode =
                when (viewMode) {
                    GameListViewMode.LIST -> GameListViewMode.FLYERS
                    GameListViewMode.FLYERS -> GameListViewMode.LIST
                }
            saveViewMode(viewMode)
            applyViewMode(viewMode)
        }

        updateThemeToggleIcon()
        btnThemeToggle.setOnClickListener { toggleTheme() }

        loadPrefs()
        games = GameXml.parseGamesXmlFromAssets(this)

        AssetInstaller.ensureInstalled(this, internalUserRoot())

        applyViewMode(viewMode)
        refreshUi()
        pendingRomLaunch?.let { info ->
            handleRomLaunchIntent(intent, info)
            pendingRomLaunch = null
        }
    }

    private fun applyThemeFromPrefs() {
        val mode =
            when (prefs.getString("theme_mode", null)) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun toggleTheme() {
        val currentlyDark = isNightModeActive()
        val mode =
            if (currentlyDark) {
                prefs.edit().putString("theme_mode", "light").apply()
                AppCompatDelegate.MODE_NIGHT_NO
            } else {
                prefs.edit().putString("theme_mode", "dark").apply()
                AppCompatDelegate.MODE_NIGHT_YES
            }
        AppCompatDelegate.setDefaultNightMode(mode)
        recreate()
    }

    private fun isNightModeActive(): Boolean {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    private fun updateThemeToggleIcon() {
        if (isNightModeActive()) {
            btnThemeToggle.setImageResource(R.drawable.light_mode_24px)
            btnThemeToggle.contentDescription = getString(R.string.theme_switch_to_light)
        } else {
            btnThemeToggle.setImageResource(R.drawable.dark_mode_24px)
            btnThemeToggle.contentDescription = getString(R.string.theme_switch_to_dark)
        }
    }

    private fun styleSearchBarText() {
        val textView = findSearchBarTextView() ?: return
        textView.gravity = Gravity.CENTER
        textView.textAlignment = View.TEXT_ALIGNMENT_CENTER
        val icon = AppCompatResources.getDrawable(this, R.drawable.search_24px) ?: return
        val tint =
            MaterialColors.getColor(
                searchBar,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        icon.setTint(tint)
        textView.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        textView.compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
    }

    private fun findSearchBarTextView(): TextView? {
        val queue = ArrayDeque<View>()
        queue.add(searchBar)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is TextView) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    queue.add(view.getChildAt(i))
                }
            }
        }
        return null
    }

    private fun showGameSettings(game: GameDef) {
        val options = arrayOf("Controller mapping")
        MaterialAlertDialogBuilder(this)
            .setTitle(game.displayName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openControllerMapping(game)
                }
            }
            .show()
    }

    private fun openControllerMapping(game: GameDef?) {
        val tree = userTreeUri
        if (tree == null) {
            Toast.makeText(this, "Pick a data folder first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent =
            Intent(this, ControlsMappingActivity::class.java).apply {
                putExtra(IniEditorActivity.EXTRA_TREE_URI, tree.toString())
                if (game != null) {
                    putExtra(ControlsMappingActivity.EXTRA_GAME_NAME, game.name)
                    putExtra(ControlsMappingActivity.EXTRA_GAME_TITLE, game.displayName)
                }
            }
        startActivity(intent)
    }

    private fun showAboutDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        val aboutText: TextView = view.findViewById(R.id.about_text)
        aboutText.text =
            """
            SUPER3 is an open-source Sega Model 3 emulator for Android.

            Sources used:
            https://github.com/trzy/Supermodel
            https://github.com/DirtBagXon/model3emu-code-sinden/tree/arm

            License (GPLv3):
            https://www.gnu.org/licenses/gpl-3.0.en.html

            App source code:
            https://github.com/izzy2lost/Super3

            Privacy policy:
            https://www.izzy2lost.com/super3-privacy

            Disclaimers:
            - No games/ROMs/BIOS included.
            - Not affiliated with or endorsed by SEGA.
            """.trimIndent()

        Linkify.addLinks(aboutText, Linkify.WEB_URLS)
        aboutText.movementMethod = LinkMovementMethod.getInstance()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseRomLaunchIntent(intent)?.let { info ->
            handleRomLaunchIntent(intent, info)
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    private fun loadPrefs() {
        gamesTreeUri = prefs.getString("gamesTreeUri", null)?.let(Uri::parse)
        userTreeUri = prefs.getString("userTreeUri", null)?.let(Uri::parse)
    }

    private fun internalUserRoot(): File {
        return File(getExternalFilesDir(null), "super3")
    }

    private fun flyersDir(internalRoot: File = internalUserRoot()): File {
        return File(internalRoot, "Flyers")
    }

    private fun flyerSpanCount(): Int {
        val dm = resources.displayMetrics
        val portrait = dm.heightPixels >= dm.widthPixels
        if (portrait) return 1
        val widthDp = dm.widthPixels / dm.density
        val desiredItemDp = 190f
        return (widthDp / desiredItemDp).toInt().coerceIn(2, 6)
    }

    private fun applyViewMode(mode: GameListViewMode) {
        gamesAdapter.setViewMode(mode)

        if (mode == GameListViewMode.FLYERS) {
            ensureFlyersSynced()
        }

        val lm =
            when (mode) {
                GameListViewMode.LIST -> androidx.recyclerview.widget.LinearLayoutManager(this)
                GameListViewMode.FLYERS -> GridLayoutManager(this, flyerSpanCount())
            }

        gamesList.layoutManager = lm
        searchResultsList.layoutManager =
            when (mode) {
                GameListViewMode.LIST -> androidx.recyclerview.widget.LinearLayoutManager(this)
                GameListViewMode.FLYERS -> GridLayoutManager(this, flyerSpanCount())
            }

        btnViewMode.setImageResource(
            when (mode) {
                GameListViewMode.LIST -> R.drawable.view_list_24px
                GameListViewMode.FLYERS -> R.drawable.view_module_24px
            },
        )
        btnViewMode.contentDescription =
            when (mode) {
                GameListViewMode.LIST -> "List view"
                GameListViewMode.FLYERS -> "Flyer view"
            }

        gamesAdapter.submitList(buildItems(games, zipDocs, flyersDir()))
    }

    private fun ensureFlyersSynced() {
        if (syncingFlyers) return

        val dir = flyersDir()
        val last = prefs.getLong("flyers_sync_last_ms", 0L)
        val now = System.currentTimeMillis()
        val hasAny = dir.exists() && (dir.listFiles()?.any { it.isFile } == true)
        val minIntervalMs = 6L * 60L * 60L * 1000L
        if (hasAny && now - last < minIntervalMs) return

        syncingFlyers = true
        flyersStatusToken = now
        Toast.makeText(this, "Syncing flyers…", Toast.LENGTH_SHORT).show()

        val priorStatus = statusText.text?.toString().orEmpty()
        statusText.text = "Downloading flyers…"

        thread(name = "Super3FlyerSync") {
            val result =
                runCatching {
                    FlyerRepoSync.syncInto(dir) { done, total, name ->
                        runOnUiThread {
                            if (flyersStatusToken == now) {
                                statusText.text = "Downloading flyers… ($done/$total) $name"
                            }
                        }
                    }
                }

            runOnUiThread {
                syncingFlyers = false
                if (flyersStatusToken == now) {
                    statusText.text = priorStatus
                    flyersStatusToken = 0L
                }

                val ok = result.getOrNull()
                if (ok == null) {
                    Toast.makeText(this, "Flyer download failed", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val sync = ok
                prefs.edit().putLong("flyers_sync_last_ms", now).apply()

                if (sync.downloaded > 0) {
                    gamesAdapter.submitList(buildItems(games, zipDocs, dir))
                }
            }
        }
    }

    private fun loadViewMode(): GameListViewMode {
        return when (prefs.getString("ui_view_mode", null)) {
            "list" -> GameListViewMode.LIST
            else -> GameListViewMode.FLYERS
        }
    }

    private fun saveViewMode(mode: GameListViewMode) {
        val v =
            when (mode) {
                GameListViewMode.LIST -> "list"
                GameListViewMode.FLYERS -> "flyers"
            }
        prefs.edit().putString("ui_view_mode", v).apply()
    }

    private fun supermodelIniFile(internalRoot: File = internalUserRoot()): File {
        return File(File(internalRoot, "Config"), "Supermodel.ini")
    }

    private fun loadVideoSettings(): VideoSettings {
        val hasPrefs = prefs.contains("video_xResolution") && prefs.contains("video_yResolution")
        if (hasPrefs) {
            val x = prefs.getInt("video_xResolution", 496)
            val y = prefs.getInt("video_yResolution", 384)
            val wideScreen = prefs.getBoolean("video_wideScreen", false)
            val wideBackground = prefs.getBoolean("video_wideBackground", false)
            val matchDevice = prefs.getBoolean("video_matchDevice", false)
            val real3dEnabled = prefs.getBoolean("video_real3d_enabled", true)
            return VideoSettings(x, y, wideScreen, wideBackground, real3dEnabled, matchDevice)
        }

        val ini = supermodelIniFile()
        val x = readIniInt(ini, "XResolution") ?: 496
        val y = readIniInt(ini, "YResolution") ?: 384
        val wideScreen = readIniBool(ini, "WideScreen") ?: false
        val wideBackground = readIniBool(ini, "WideBackground") ?: false
        val real3dEnabled = readIniBool(ini, "New3DEngine") ?: true
        return VideoSettings(x, y, wideScreen, wideBackground, real3dEnabled, matchDevice = false)
    }

    private fun saveVideoSettings(settings: VideoSettings) {
        prefs.edit()
            .putInt("video_xResolution", settings.xResolution)
            .putInt("video_yResolution", settings.yResolution)
            .putBoolean("video_wideScreen", settings.wideScreen)
            .putBoolean("video_wideBackground", settings.wideBackground)
            .putBoolean("video_real3d_enabled", settings.real3dEnabled)
            .putBoolean("video_matchDevice", settings.matchDevice)
            .apply()
    }

    private fun bindVideoSettingsUi() {
        fun renderResolutionLabel(x: Int, y: Int): String {
            val match = resolutionOptions.firstOrNull { it.x == x && it.y == y }
            return if (match != null) {
                "Resolution: ${match.label} (${match.x}x${match.y})"
            } else {
                "Resolution: Custom (${x}x${y})"
            }
        }

        fun applyUi(settings: VideoSettings) {
            btnResolution.text =
                if (settings.matchDevice) {
                    "Resolution: Device (${settings.xResolution}x${settings.yResolution})"
                } else {
                    renderResolutionLabel(settings.xResolution, settings.yResolution)
                }
            btnResolutionMatchDevice.isChecked = settings.matchDevice
            btnWidescreen.isChecked = settings.wideScreen
            btnWideBackground.isChecked = settings.wideBackground
            btnReal3dRenderer.isChecked = settings.real3dEnabled
        }

        fun persistAndApply(settings: VideoSettings) {
            saveVideoSettings(settings)
            applyVideoSettingsToIni(internalUserRoot(), settings)
            val tree = userTreeUri
            if (tree != null) {
                thread(name = "Super3SyncSettings") {
                    UserDataSync.syncInternalIntoTree(this, internalUserRoot(), tree, UserDataSync.DIRS_SETTINGS_ONLY)
                }
            }
        }

        applyUi(loadVideoSettings())

        btnResolution.setOnClickListener {
            val cur = loadVideoSettings()
            val curIndex = resolutionOptions.indexOfFirst { it.x == cur.xResolution && it.y == cur.yResolution }
            val next = resolutionOptions[(curIndex + 1).coerceAtLeast(0) % resolutionOptions.size]
            val leavingMatchMode = cur.matchDevice
            val updated =
                cur.copy(
                    xResolution = next.x,
                    yResolution = next.y,
                    matchDevice = false,
                    wideScreen = if (leavingMatchMode) false else cur.wideScreen,
                    wideBackground = if (leavingMatchMode) false else cur.wideBackground,
                )
            applyUi(updated)
            persistAndApply(updated)
            Toast.makeText(this, "Resolution set to ${next.label} (${next.x}x${next.y})", Toast.LENGTH_SHORT).show()
        }

        btnResolutionMatchDevice.setOnClickListener {
            val dm = resources.displayMetrics
            val target = exactDeviceResolution(dm.widthPixels, dm.heightPixels)
            val cur = loadVideoSettings()
            val updated =
                cur.copy(
                    xResolution = target.x,
                    yResolution = target.y,
                    wideScreen = true,
                    wideBackground = true,
                    matchDevice = true,
                )
            applyUi(updated)
            persistAndApply(updated)
            Toast.makeText(this, "Resolution set to ${target.label} (${target.x}x${target.y})", Toast.LENGTH_SHORT).show()
        }

        btnWidescreen.setOnClickListener {
            val cur = loadVideoSettings()
            val updated = cur.copy(wideScreen = btnWidescreen.isChecked, matchDevice = false)
            persistAndApply(updated)
            applyUi(updated)
        }

        btnWideBackground.setOnClickListener {
            val cur = loadVideoSettings()
            val updated = cur.copy(wideBackground = btnWideBackground.isChecked, matchDevice = false)
            persistAndApply(updated)
            applyUi(updated)
        }

        btnReal3dRenderer.setOnClickListener {
            val cur = loadVideoSettings()
            val enabled = btnReal3dRenderer.isChecked
            val updated = cur.copy(real3dEnabled = enabled, matchDevice = false)
            persistAndApply(updated)
            applyUi(updated)
            Toast.makeText(
                this,
                if (enabled) "3D renderer enabled (restart game to apply)" else "2D-only renderer enabled (restart game to apply)",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun applyVideoSettingsToIni(internalRoot: File, settings: VideoSettings) {
        val ini = supermodelIniFile(internalRoot)
        updateIniKeys(
            ini,
            mapOf(
                "XResolution" to settings.xResolution.toString(),
                "YResolution" to settings.yResolution.toString(),
                "WideScreen" to if (settings.wideScreen) "1" else "0",
                "WideBackground" to if (settings.wideBackground) "1" else "0",
                "New3DEngine" to if (settings.real3dEnabled) "1" else "0",
                "New3DAccurate" to "0",
            ),
        )
    }

    private fun loadTimingPref(): Boolean {
        if (prefs.contains("timing_legacy_default")) {
            return prefs.getBoolean("timing_legacy_default", true)
        }
        return prefs.getBoolean("daytona_timing_legacy", true)
    }

    private fun updateTimingLabel(legacy: Boolean) {
        btnDaytonaTiming.text =
            if (legacy) {
                getString(R.string.timing_legacy)
            } else {
                getString(R.string.timing_new)
            }
    }

    private fun bindTimingUi() {
        val legacy = loadTimingPref()
        btnDaytonaTiming.isChecked = legacy
        updateTimingLabel(legacy)
        btnDaytonaTiming.setOnClickListener {
            val enabled = btnDaytonaTiming.isChecked
            prefs.edit().putBoolean("timing_legacy_default", enabled).remove("daytona_timing_legacy").apply()
            applyTimingDefaultToIni(internalUserRoot(), enabled)
            val tree = userTreeUri
            if (tree != null) {
                thread(name = "Super3SyncSettings") {
                    UserDataSync.syncInternalIntoTree(this, internalUserRoot(), tree, UserDataSync.DIRS_SETTINGS_ONLY)
                }
            }
            updateTimingLabel(enabled)
            Toast.makeText(
                this,
                "Timing set to ${if (enabled) "Legacy" else "New"} (restart game to apply)",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun exactDeviceResolution(widthPx: Int, heightPx: Int): ResolutionOption {
        val x = max(1, max(widthPx, heightPx))
        val y = max(1, min(widthPx, heightPx))
        return ResolutionOption("Device", x, y)
    }

    private fun readIniInt(file: File, key: String): Int? {
        return readIniString(file, key)?.trim()?.toIntOrNull()
    }

    private fun readIniBool(file: File, key: String): Boolean? {
        val v = readIniString(file, key)?.trim()?.lowercase() ?: return null
        return when (v) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    private fun readIniString(file: File, key: String): String? {
        if (!file.exists()) return null
        val lines = file.readLines()
        val keyRegex = Regex("^\\s*${Regex.escape(key)}\\s*=\\s*(.*?)\\s*$", RegexOption.IGNORE_CASE)

        var inGlobal = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val name = trimmed.removePrefix("[").removeSuffix("]").trim()
                inGlobal = name.equals("global", ignoreCase = true)
                continue
            }
            if (!inGlobal) continue
            if (trimmed.startsWith(";")) continue
            val m = keyRegex.find(line) ?: continue
            return m.groupValues[1]
        }
        return null
    }

    private fun updateIniKeys(file: File, updates: Map<String, String>) {
        val lines = if (file.exists()) file.readLines() else emptyList()
        val out = ArrayList<String>(lines.size + updates.size + 8)

        fun isSectionHeader(s: String): Boolean {
            val t = s.trim()
            return t.startsWith("[") && t.endsWith("]")
        }

        fun sectionName(s: String): String {
            return s.trim().removePrefix("[").removeSuffix("]").trim()
        }

        val globalStart = lines.indexOfFirst { isSectionHeader(it) && sectionName(it).equals("global", ignoreCase = true) }
        if (globalStart < 0) {
            out.addAll(lines)
            if (out.isNotEmpty() && out.last().isNotBlank()) out.add("")
            out.add("[ Global ]")
            for ((k, v) in updates) {
                out.add("$k = $v")
            }
            file.parentFile?.mkdirs()
            file.writeText(out.joinToString("\n"))
            return
        }

        val globalEnd =
            (globalStart + 1 + lines.drop(globalStart + 1).indexOfFirst { isSectionHeader(it) })
                .let { if (it <= globalStart) lines.size else it }

        out.addAll(lines.take(globalStart + 1))

        val existing = HashMap<String, Int>(updates.size)
        for (i in (globalStart + 1) until globalEnd) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith(";") || trimmed.isBlank()) {
                out.add(line)
                continue
            }
            var replaced = false
            for ((k, v) in updates) {
                val rx = Regex("^\\s*${Regex.escape(k)}\\s*=", RegexOption.IGNORE_CASE)
                if (rx.containsMatchIn(line)) {
                    out.add("$k = $v")
                    existing[k.lowercase()] = 1
                    replaced = true
                    break
                }
            }
            if (!replaced) out.add(line)
        }

        for ((k, v) in updates) {
            if (existing.containsKey(k.lowercase())) continue
            out.add("$k = $v")
        }

        out.addAll(lines.drop(globalEnd))
        file.parentFile?.mkdirs()
        file.writeText(out.joinToString("\n"))
    }

    private fun updateIniSection(file: File, section: String, updates: Map<String, String>) {
        val lines = if (file.exists()) file.readLines() else emptyList()
        val out = ArrayList<String>(lines.size + updates.size + 8)

        fun isSectionHeader(s: String): Boolean {
            val t = s.trim()
            return t.startsWith("[") && t.endsWith("]")
        }

        fun sectionName(s: String): String {
            return s.trim().removePrefix("[").removeSuffix("]").trim()
        }

        val targetStart = lines.indexOfFirst { isSectionHeader(it) && sectionName(it).equals(section, ignoreCase = true) }
        if (targetStart < 0) {
            out.addAll(lines)
            if (out.isNotEmpty() && out.last().isNotBlank()) out.add("")
            out.add("[ $section ]")
            for ((k, v) in updates) {
                out.add("$k = $v")
            }
            file.parentFile?.mkdirs()
            file.writeText(out.joinToString("\n"))
            return
        }

        val targetEnd =
            (targetStart + 1 + lines.drop(targetStart + 1).indexOfFirst { isSectionHeader(it) })
                .let { if (it <= targetStart) lines.size else it }

        out.addAll(lines.take(targetStart + 1))

        val existing = HashMap<String, Int>(updates.size)
        for (i in (targetStart + 1) until targetEnd) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith(";") || trimmed.isBlank()) {
                out.add(line)
                continue
            }
            var replaced = false
            for ((k, v) in updates) {
                val rx = Regex("^\\s*${Regex.escape(k)}\\s*=", RegexOption.IGNORE_CASE)
                if (rx.containsMatchIn(line)) {
                    out.add("$k = $v")
                    existing[k.lowercase()] = 1
                    replaced = true
                    break
                }
            }
            if (!replaced) out.add(line)
        }

        for ((k, v) in updates) {
            if (existing.containsKey(k.lowercase())) continue
            out.add("$k = $v")
        }

        out.addAll(lines.drop(targetEnd))
        file.parentFile?.mkdirs()
        file.writeText(out.joinToString("\n"))
    }

    private fun applyTimingDefaultToIni(internalRoot: File, legacy: Boolean) {
        val ini = supermodelIniFile(internalRoot)
        val value = if (legacy) "1" else "0"
        updateIniKeys(ini, mapOf("LegacyReal3DTiming" to value))
    }

    private fun persistTreePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
    }

    private fun parseRomLaunchIntent(intent: Intent?): RomLaunchInfo? {
        if (intent == null) return null
        val uri = extractRomUri(intent) ?: return null
        val file = resolveFileFromUri(uri)
        val (displayName, size) = resolveRomNameAndSize(uri, file)
        val baseName = normalizeRomBaseName(displayName)
        if (baseName.isBlank()) return null
        return RomLaunchInfo(
            uri = uri,
            displayName = displayName,
            baseName = baseName,
            file = file,
            size = size,
        )
    }

    private fun extractRomUri(intent: Intent): Uri? {
        intent.data?.let { return it }
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 0) {
            val itemUri = clip.getItemAt(0).uri
            if (itemUri != null) return itemUri
        }
        getParcelableUriExtra(intent, Intent.EXTRA_STREAM)?.let { return it }
        for (key in romIntentExtraKeys) {
            getParcelableUriExtra(intent, key)?.let { return it }
            val text = intent.getStringExtra(key)
            if (!text.isNullOrBlank()) {
                val parsed = Uri.parse(text)
                if (!parsed.toString().isNullOrBlank()) return parsed
            }
        }
        return null
    }

    private fun getParcelableUriExtra(intent: Intent, key: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key) as? Uri
        }
    }

    private fun resolveFileFromUri(uri: Uri): File? {
        val path = uri.path ?: return null
        return if (uri.scheme == null || uri.scheme.equals("file", ignoreCase = true)) {
            File(path)
        } else {
            null
        }
    }

    private fun resolveRomNameAndSize(uri: Uri, file: File?): Pair<String, Long?> {
        if (file != null) {
            val size = if (file.exists()) file.length() else null
            return file.name to size
        }
        val doc = DocumentFile.fromSingleUri(this, uri)
        val name = doc?.name ?: uri.lastPathSegment.orEmpty()
        val size = doc?.length()?.takeIf { it > 0 }
        return name to size
    }

    private fun normalizeRomBaseName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return ""
        val dot = trimmed.lastIndexOf('.')
        return if (dot > 0) trimmed.substring(0, dot) else trimmed
    }

    private fun extractGameNameExtra(intent: Intent): String? {
        for (key in romNameExtraKeys) {
            val value = intent.getStringExtra(key)
            if (!value.isNullOrBlank()) return value.trim()
        }
        return null
    }

    private fun updateStatus(text: String) {
        if (::statusText.isInitialized) {
            statusText.text = text
        }
    }

    private fun maybePersistReadPermission(intent: Intent, uri: Uri) {
        if (!uri.scheme.equals("content", ignoreCase = true)) return
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    private fun scanZipFilesInDir(dir: File): Map<String, File> {
        val files = dir.listFiles() ?: return emptyMap()
        val map = HashMap<String, File>(files.size)
        for (child in files) {
            if (!child.isFile) continue
            val name = child.name
            if (!name.endsWith(".zip", ignoreCase = true)) continue
            map[name.substring(0, name.length - 4)] = child
        }
        return map
    }

    private fun copyFileToCacheIfNeeded(file: File, outFile: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        outFile.parentFile?.mkdirs()
        if (outFile.exists() && outFile.length() == file.length()) {
            return true
        }
        return try {
            file.inputStream().use { ins ->
                outFile.outputStream().use { outs ->
                    ins.copyTo(outs)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun copyUriToCacheIfNeeded(uri: Uri, expectedSize: Long?, outFile: File): Boolean {
        outFile.parentFile?.mkdirs()
        if (outFile.exists() && expectedSize != null && expectedSize > 0 && outFile.length() == expectedSize) {
            return true
        }
        return try {
            val input = contentResolver.openInputStream(uri) ?: return false
            input.use { ins ->
                outFile.outputStream().use { outs ->
                    ins.copyTo(outs)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun handleRomLaunchIntent(intent: Intent, info: RomLaunchInfo) {
        if (launchingRomIntent) return
        launchingRomIntent = true

        loadPrefs()
        maybePersistReadPermission(intent, info.uri)
        if (games.isEmpty()) {
            games = GameXml.parseGamesXmlFromAssets(this)
        }

        val explicitName = extractGameNameExtra(intent)
        val explicitDef =
            explicitName?.let { name ->
                games.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
        val baseDef = games.firstOrNull { it.name.equals(info.baseName, ignoreCase = true) }
        val gameDef = explicitDef ?: baseDef
        val gameName = gameDef?.name ?: info.baseName
        val displayName = gameDef?.displayName ?: gameName
        updateStatus("Preparing $displayName...")

        thread(name = "Super3IntentLaunch") {
            try {
                val internalRoot = internalUserRoot()
                val userUri = userTreeUri
                if (userUri != null) {
                    UserDataSync.syncFromTreeIntoInternal(this, userUri, internalRoot, UserDataSync.DIRS_GAME_SYNC)
                }

                AssetInstaller.ensureInstalled(this, internalRoot)

                val cacheDir = File(internalRoot, "romcache")
                val required = gameDef?.let { resolveRequiredRomZips(it) } ?: listOf(info.baseName)
                val docMap = if (gamesTreeUri != null) scanZipDocs(gamesTreeUri) else emptyMap()
                val dirMap = info.file?.parentFile?.let { scanZipFilesInDir(it) } ?: emptyMap()
                val docMapLower = docMap.mapKeys { it.key.lowercase() }
                val dirMapLower = dirMap.mapKeys { it.key.lowercase() }

                val mainOut = File(cacheDir, "${info.baseName}.zip")
                val mainOk =
                    if (info.file != null) {
                        copyFileToCacheIfNeeded(info.file, mainOut)
                    } else {
                        copyUriToCacheIfNeeded(info.uri, info.size, mainOut)
                    }
                if (!mainOk) {
                    runOnUiThread {
                        val msg = "Failed to open ${info.displayName}"
                        updateStatus(msg)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }

                val missing = ArrayList<String>()
                val mainKey = info.baseName.lowercase()
                for (zipBase in required) {
                    val key = zipBase.lowercase()
                    if (key == mainKey) continue
                    val outFile = File(cacheDir, "$zipBase.zip")
                    val ok =
                        when {
                            dirMapLower.containsKey(key) -> copyFileToCacheIfNeeded(dirMapLower[key]!!, outFile)
                            docMapLower.containsKey(key) -> copyDocToCacheIfNeeded(docMapLower[key]!!, outFile)
                            else -> false
                        }
                    if (!ok) missing.add(zipBase)
                }

                if (missing.isNotEmpty()) {
                    runOnUiThread {
                        val msg = "Missing required ZIP(s): ${missing.joinToString(", ")}"
                        updateStatus(msg)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }

                val romPath = mainOut.absolutePath
                val gamesXmlPath = File(internalRoot, "Config/Games.xml").absolutePath
                val userDataRoot = internalRoot.absolutePath

                runOnUiThread {
                    val launch =
                        Intent(this, Super3Activity::class.java).apply {
                            putExtra("romZipPath", romPath)
                            putExtra("gameName", gameName)
                            putExtra("gamesXmlPath", gamesXmlPath)
                            putExtra("userDataRoot", userDataRoot)
                        }
                    startActivity(launch)
                }
            } finally {
                launchingRomIntent = false
            }
        }
    }

    private fun refreshUi() {
        gamesFolderText.text = "Games folder: ${gamesTreeUri?.toString() ?: "Not set"}"
        userFolderText.text = "Data folder: ${userTreeUri?.toString() ?: "Not set"}"
        val gamesUri = gamesTreeUri
        if (gamesUri == null) {
            zipDocs = emptyMap()
            gamesAdapter.submitList(buildItems(games, zipDocs, flyersDir()))
            statusText.text = "Games folder not set. Tap \"Games folder\" to choose."
            scanning = false
            return
        }

        if (scanning) return
        scanning = true
        statusText.text = "Scanning…"

        thread(name = "Super3Scanner") {
            val userUri = userTreeUri
            if (userUri != null) {
                UserDataSync.syncFromTreeIntoInternal(this, userUri, internalUserRoot(), listOf("Flyers"))
            }
            val zips = scanZipDocs(gamesUri)
            val items = buildItems(games, zips, flyersDir())
            runOnUiThread {
                zipDocs = zips
                gamesAdapter.submitList(items)
                statusText.text = "Found ${zipDocs.size} ZIP(s). Tap a game to launch."
                scanning = false
            }
        }
    }

    private fun scanZipDocs(treeUri: Uri?): Map<String, DocumentFile> {
        if (treeUri == null) return emptyMap()
        val tree = DocumentFile.fromTreeUri(this, treeUri) ?: return emptyMap()
        val map = HashMap<String, DocumentFile>(256)
        for (child in tree.listFiles()) {
            if (!child.isFile) continue
            val name = child.name ?: continue
            if (!name.endsWith(".zip", ignoreCase = true)) continue
            map[name.substring(0, name.length - 4)] = child
        }
        return map
    }

    private fun launchGame(game: GameDef) {
        val gamesUri = gamesTreeUri
        if (gamesUri == null) {
            Toast.makeText(this, "Pick a games folder first", Toast.LENGTH_SHORT).show()
            return
        }

        statusText.text = "Preparing ${game.displayName}…"

        thread(name = "Super3Prep") {
            val internalRoot = internalUserRoot()
            val userUri = userTreeUri

            if (userUri != null) {
                UserDataSync.syncFromTreeIntoInternal(this, userUri, internalRoot, UserDataSync.DIRS_GAME_SYNC)
            }

            val cacheDir = File(internalRoot, "romcache")
            val required = resolveRequiredRomZips(game)
            val missing = required.filter { !zipDocs.containsKey(it) }
            if (missing.isNotEmpty()) {
                runOnUiThread {
                    statusText.text = "Missing required ZIP(s): ${missing.joinToString(", ")}"
                    Toast.makeText(
                        this,
                        "Missing required ZIP(s): ${missing.joinToString(", ")}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@thread
            }

            for (zipBase in required) {
                val doc = zipDocs[zipBase] ?: continue
                val ok = copyDocToCacheIfNeeded(doc, File(cacheDir, "$zipBase.zip"))
                if (!ok) {
                    runOnUiThread {
                        statusText.text = "Failed to copy $zipBase.zip"
                        Toast.makeText(this, "Failed to copy $zipBase.zip", Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }
            }

            val romPath = File(cacheDir, "${game.name}.zip").absolutePath
            val gamesXmlPath = File(internalRoot, "Config/Games.xml").absolutePath
            val userDataRoot = internalRoot.absolutePath

            runOnUiThread {
                val intent = Intent(this, Super3Activity::class.java).apply {
                    putExtra("romZipPath", romPath)
                    putExtra("gameName", game.name)
                    putExtra("gamesXmlPath", gamesXmlPath)
                    putExtra("userDataRoot", userDataRoot)
                }
                startActivity(intent)
            }
        }
    }

    private fun resolveRequiredRomZips(game: GameDef): List<String> {
        val mapByName = games.associateBy { it.name }
        val required = ArrayList<String>(4)
        val visited = HashSet<String>(8)

        var current: GameDef? = game
        while (current != null) {
            if (!visited.add(current.name)) break
            required.add(current.name)
            val parentName = current.parent
            if (parentName.isNullOrBlank()) break
            current = mapByName[parentName]
        }

        return required
    }

    private fun copyDocToCacheIfNeeded(doc: DocumentFile, outFile: File): Boolean {
        outFile.parentFile?.mkdirs()
        val expectedSize = doc.length()
        if (outFile.exists() && expectedSize > 0 && outFile.length() == expectedSize) {
            return true
        }
        val input = contentResolver.openInputStream(doc.uri) ?: return false
        input.use { ins ->
            outFile.outputStream().use { outs ->
                ins.copyTo(outs)
            }
        }
        return true
    }
}

private data class GameItem(
    val game: GameDef,
    val launchable: Boolean,
    val status: String,
    val flyerFront: File?,
    val flyerBack: File?,
    val hasOwnFlyer: Boolean,
) {
    val hasFlyer: Boolean
        get() = flyerFront != null
}

private data class FlyerGroup(
    val rootName: String,
    val groupName: String,
    val title: String,
    val flyerFront: File?,
    val flyerBack: File?,
    val variants: List<GameItem>,
)

private fun flyerGroupRootAlias(name: String): String? {
    return when {
        name.equals("vs99.1", ignoreCase = true) -> "vs99"
        name.equals("vs2v991", ignoreCase = true) -> "vs299"
        else -> null
    }
}

private fun shouldForceGroupWithRoot(name: String): Boolean {
    return name.equals("vs99.1", ignoreCase = true) || name.equals("vs2v991", ignoreCase = true)
}

private fun flyerKeyCandidates(name: String): List<String> {
    val alias = flyerGroupRootAlias(name) ?: return listOf(name)
    return if (alias == name) listOf(name) else listOf(alias, name)
}

private fun buildItems(games: List<GameDef>, zipDocs: Map<String, DocumentFile>, flyersDir: File): List<GameItem> {
    val byName = games.associateBy { it.name }

    val frontByKey = HashMap<String, File>(128)
    val backByKey = HashMap<String, File>(128)
    if (flyersDir.exists()) {
        val files = flyersDir.listFiles().orEmpty()
        for (f in files) {
            if (!f.isFile) continue
            val base = f.nameWithoutExtension
            when {
                base.endsWith("_front", ignoreCase = true) -> {
                    val key = base.dropLast("_front".length)
                    if (key.isNotBlank()) frontByKey[key] = f
                }
                base.endsWith("_back", ignoreCase = true) -> {
                    val key = base.dropLast("_back".length)
                    if (key.isNotBlank()) backByKey[key] = f
                }
            }
        }
    }

    fun requiredZips(g: GameDef): List<String> {
        val required = ArrayList<String>(4)
        val visited = HashSet<String>(8)
        var cur: GameDef? = g
        while (cur != null) {
            if (!visited.add(cur.name)) break
            required.add(cur.name)
            val parent = cur.parent
            if (parent.isNullOrBlank()) break
            cur = byName[parent]
        }
        return required
    }

    fun normalizeFlyers(front: File?, back: File?): Pair<File?, File?> {
        if (front == null && back == null) return null to null
        val displayFront = front ?: back
        val displayBack = if (front != null && back != null) back else null
        return displayFront to displayBack
    }

    fun resolveFlyers(g: GameDef): Pair<File?, File?> {
        val visited = HashSet<String>(8)
        var cur: GameDef? = g
        while (cur != null) {
            if (!visited.add(cur.name)) break
            for (key in flyerKeyCandidates(cur.name)) {
                val front = frontByKey[key]
                val back = backByKey[key]
                if (front != null || back != null) {
                    return normalizeFlyers(front, back)
                }
            }
            val parent = cur.parent
            if (parent.isNullOrBlank()) break
            cur = byName[parent]
        }
        return null to null
    }

    val items = games.map { g ->
        val req = requiredZips(g)
        val missing = req.filter { !zipDocs.containsKey(it) }
        val launchable = missing.isEmpty() && zipDocs.containsKey(g.name)
        val status =
            if (missing.isEmpty()) {
                if (req.size == 1) "${g.name}.zip found" else "needs ${req.joinToString(" + ") { "${it}.zip" }}"
            } else {
                "missing ${missing.joinToString(" + ") { "${it}.zip" }}"
            }
        val directFront = frontByKey[g.name]
        val directBack = backByKey[g.name]
        val hasOwnFlyer = directFront != null || directBack != null
        val flyers = resolveFlyers(g)
        GameItem(
            game = g,
            launchable = launchable,
            status = status,
            flyerFront = flyers.first,
            flyerBack = flyers.second,
            hasOwnFlyer = hasOwnFlyer,
        )
    }

    return items.sortedWith(
        compareByDescending<GameItem> { it.hasFlyer }
            .thenByDescending { it.launchable }
            .thenBy { it.game.displayName },
    )
}

private class GamesAdapter(
    private val onClick: (GameItem) -> Unit,
    private val onLongClick: (GameItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    companion object {
        private const val VT_LIST = 1
        private const val VT_FLYERS = 2
    }

    private var viewMode: GameListViewMode = GameListViewMode.FLYERS
    private var allItems: List<GameItem> = emptyList()
    private var shownItems: List<GameItem> = emptyList()
    private var shownFlyerGroups: List<FlyerGroup> = emptyList()
    private var filter: String = ""

    fun setViewMode(mode: GameListViewMode) {
        if (mode == viewMode) return
        viewMode = mode
        applyFilter()
    }

    fun submitList(items: List<GameItem>) {
        allItems = items
        applyFilter()
    }

    fun setFilter(query: String) {
        val normalized = query.trim()
        if (normalized == filter) return
        filter = normalized
        applyFilter()
    }

    private fun applyFilter() {
        val q = filter.trim()
        if (viewMode == GameListViewMode.FLYERS) {
            val needle = q.takeIf { it.isNotBlank() }?.lowercase()
            shownFlyerGroups = buildFlyerGroups(allItems, needle)
        } else {
            shownItems =
                if (q.isBlank()) {
                    allItems
                } else {
                    val needle = q.lowercase()
                    allItems.filter {
                        it.game.displayName.lowercase().contains(needle) || it.game.name.lowercase().contains(needle)
                    }
                }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (viewMode) {
            GameListViewMode.LIST -> VT_LIST
            GameListViewMode.FLYERS -> VT_FLYERS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VT_LIST -> ListVH(inflater.inflate(R.layout.item_game_list, parent, false))
            else -> FlyerGroupVH(inflater.inflate(R.layout.item_game, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ListVH -> holder.bind(shownItems[position], onClick, onLongClick)
            is FlyerGroupVH -> holder.bind(shownFlyerGroups[position], onClick, onLongClick)
        }
    }

    override fun getItemCount(): Int {
        return if (viewMode == GameListViewMode.FLYERS) {
            shownFlyerGroups.size
        } else {
            shownItems.size
        }
    }

    private fun buildFlyerGroups(items: List<GameItem>, needle: String?): List<FlyerGroup> {
        if (items.isEmpty()) return emptyList()

        val defByName = items.associate { it.game.name to it.game }
        val rootCache = HashMap<String, String>(items.size)

        fun rootNameFor(game: GameDef): String {
            rootCache[game.name]?.let { return it }
            val visited = HashSet<String>(8)
            var cur: GameDef? = game
            while (cur != null) {
                if (!visited.add(cur.name)) break
                val parent = cur.parent
                if (parent.isNullOrBlank()) break
                cur = defByName[parent]
            }
            val root = cur?.name ?: game.name
            val normalized = flyerGroupRootAlias(root) ?: root
            rootCache[game.name] = normalized
            return normalized
        }

        fun matches(item: GameItem, needleLc: String): Boolean {
            if (item.game.displayName.lowercase().contains(needleLc)) return true
            if (item.game.name.lowercase().contains(needleLc)) return true
            val ver = item.game.version
            if (!ver.isNullOrBlank() && ver.lowercase().contains(needleLc)) return true
            return false
        }

        val groups =
            items.groupBy { item ->
                val root = rootNameFor(item.game)
                val splitByOwnFlyer =
                    item.hasOwnFlyer &&
                        item.game.name != root &&
                        !shouldForceGroupWithRoot(item.game.name)
                if (splitByOwnFlyer) item.game.name else root
            }
        val result = ArrayList<FlyerGroup>(groups.size)
        for ((groupName, groupItems) in groups) {
            val groupGame = defByName[groupName] ?: groupItems.firstOrNull()?.game
            val rootName =
                if (groupGame != null) {
                    rootNameFor(groupGame)
                } else {
                    groupName
                }

            val title =
                if (groupGame != null && groupName != rootName) {
                    groupGame.displayName
                } else {
                    groupGame?.title ?: groupName
                }

            val ordered =
                groupItems.sortedWith(
                    compareByDescending<GameItem> { it.game.name == groupName }
                        .thenBy { it.game.displayName },
                )

            val filteredVariants =
                if (needle == null) {
                    ordered
                } else {
                    val rootMatches =
                        title.lowercase().contains(needle) ||
                            rootName.lowercase().contains(needle)
                    val matched = ordered.filter { matches(it, needle) }
                    when {
                        rootMatches -> ordered
                        matched.isNotEmpty() -> matched
                        else -> emptyList()
                    }
                }

            if (filteredVariants.isEmpty()) continue

            val main =
                ordered.firstOrNull { it.game.name == groupName } ?: ordered.firstOrNull()
            val flyerFront = main?.flyerFront
            val flyerBack = main?.flyerBack

            result.add(
                FlyerGroup(
                    rootName = rootName,
                    groupName = groupName,
                    title = title,
                    flyerFront = flyerFront,
                    flyerBack = flyerBack,
                    variants = filteredVariants,
                ),
            )
        }

        return result.sortedWith(
            compareByDescending<FlyerGroup> { it.flyerFront != null }
                .thenBy { it.title },
        )
    }

    class ListVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.game_title)
        private val subtitle: TextView = itemView.findViewById(R.id.game_subtitle)

        fun bind(item: GameItem, onClick: (GameItem) -> Unit, onLongClick: (GameItem) -> Unit) {
            title.text = item.game.displayName
            subtitle.text = item.status
            itemView.isEnabled = item.launchable
            title.isEnabled = item.launchable
            subtitle.isEnabled = item.launchable
            itemView.alpha = if (item.launchable) 1.0f else 0.5f
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    class FlyerGroupVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val flyerContainer: View = itemView.findViewById(R.id.flyer_container)
        private val flyerFront: android.widget.ImageView = itemView.findViewById(R.id.flyer_front)
        private val flyerBack: android.widget.ImageView = itemView.findViewById(R.id.flyer_back)
        private val title: TextView = itemView.findViewById(R.id.game_title)
        private val variantsContainer: ViewGroup = itemView.findViewById(R.id.variants_container)

        private var showingBack = false

        fun bind(group: FlyerGroup, onClick: (GameItem) -> Unit, onLongClick: (GameItem) -> Unit) {
            title.text = group.title

            val density = itemView.resources.displayMetrics.density
            flyerFront.cameraDistance = 8000f * density
            flyerBack.cameraDistance = 8000f * density

            showingBack = false
            flyerFront.rotationY = 0f
            flyerBack.rotationY = 0f
            flyerFront.visibility = View.VISIBLE
            flyerBack.visibility = View.GONE

            FlyerBitmapLoader.load(flyerFront, group.flyerFront, targetMaxPx = 1600)
            FlyerBitmapLoader.load(flyerBack, group.flyerBack, targetMaxPx = 1600)

            val canFlip = group.flyerFront != null && group.flyerBack != null
            flyerContainer.isClickable = canFlip
            flyerContainer.isFocusable = canFlip
            flyerContainer.setOnClickListener {
                if (!canFlip) return@setOnClickListener
                flip()
            }

            variantsContainer.removeAllViews()
            val inflater = LayoutInflater.from(itemView.context)

            for (variant in group.variants) {
                val row = inflater.inflate(R.layout.item_flyer_variant, variantsContainer, false)
                val tvTitle: TextView = row.findViewById(R.id.variant_title)
                val tvSubtitle: TextView = row.findViewById(R.id.variant_subtitle)
                val btnPlay: ImageButton = row.findViewById(R.id.btn_play_variant)

                val isPrimary = variant.game.name == group.groupName
                val version = variant.game.version?.trim().orEmpty()
                tvTitle.text =
                    when {
                        isPrimary && group.variants.size == 1 -> "Default"
                        version.isNotBlank() -> version
                        isPrimary -> "Default"
                        else -> variant.game.name
                    }
                tvSubtitle.text = variant.status

                row.isEnabled = variant.launchable
                row.alpha = if (variant.launchable) 1.0f else 0.5f
                btnPlay.isEnabled = variant.launchable

                val click = View.OnClickListener { onClick(variant) }
                row.setOnClickListener(click)
                btnPlay.setOnClickListener(click)
                val longClick =
                    View.OnLongClickListener {
                        onLongClick(variant)
                        true
                    }
                row.setOnLongClickListener(longClick)
                btnPlay.setOnLongClickListener(longClick)

                variantsContainer.addView(row)
            }
        }

        private fun flip() {
            val front = flyerFront
            val back = flyerBack

            val first = if (showingBack) back else front
            val second = if (showingBack) front else back
            val toBack = !showingBack
            showingBack = toBack

            val duration = 140L
            val flipOut = android.animation.ObjectAnimator.ofFloat(first, View.ROTATION_Y, 0f, 90f).apply {
                this.duration = duration
            }
            val flipIn = android.animation.ObjectAnimator.ofFloat(second, View.ROTATION_Y, -90f, 0f).apply {
                this.duration = duration
            }

            flipOut.addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        first.visibility = View.GONE
                        first.rotationY = 0f
                        second.visibility = View.VISIBLE
                        second.rotationY = -90f
                        flipIn.start()
                    }
                },
            )

            flipOut.start()
        }
    }
}
