package com.example.tuner432

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import coil.load
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MODE_FAVORITES = 0
        private const val MODE_SEARCH = 1
        private const val MODE_LOCAL = 2
    }

    private var controller: MediaController? = null
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var modeFavorites: Button
    private lateinit var modeSearch: Button
    private lateinit var modeLocal: Button
    private lateinit var langButton: Button
    private lateinit var favoritesSection: View
    private lateinit var searchSection: View
    private lateinit var localSection: View
    private lateinit var stationSpinner: Spinner
    private lateinit var playButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var switch432: Switch
    private lateinit var eightDButton: Button
    private lateinit var hallButton: Button
    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var nowStation: TextView
    private lateinit var nowArt: ImageView
    private lateinit var searchInput: EditText
    private lateinit var searchButton: Button
    private lateinit var searchStatus: TextView
    private lateinit var resultsList: ListView
    private lateinit var addToDb: TextView
    private lateinit var localPickButton: Button
    private lateinit var localPlayButton: Button
    private lateinit var localTitle: TextView
    private lateinit var localStatus: TextView

    private var favorites: MutableList<RadioStation> = mutableListOf()
    private var stations: List<RadioStation> = listOf()
    private var results: List<RadioStation> = listOf()
    private var pinnedLogo: String? = null

    private var mode = MODE_FAVORITES
    private var convert432 = true
    private var currentLabel = "\u2014"
    private var currentLogoUrl: String? = null

    private var pickedFile: Uri? = null
    private var pickedName: String? = null
    private var localPlayback = false

    private val pitch432 = 432f / 440f

    private fun playing(): Boolean {
        val c = controller ?: return false
        return c.isPlaying || c.playbackState == Player.STATE_BUFFERING
    }

    private val askNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val pickInput = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            pickedFile = uri
            pickedName = fileName(uri) ?: "Plik audio"
            localTitle.text = pickedName
            playLocal()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(md: MediaMetadata) {
            if (localPlayback) {
                val t = (md.title ?: md.displayTitle)?.toString()?.trim()
                localTitle.text = if (!t.isNullOrEmpty()) t else (pickedName ?: getString(R.string.local_empty))
                return
            }
            val title = (md.title ?: md.displayTitle)?.toString()?.trim().orEmpty()
            val artist = md.artist?.toString()?.trim().orEmpty()
            val display = when {
                title.isEmpty() -> currentLabel
                artist.isNotEmpty() && !title.contains(artist, ignoreCase = true) -> "$artist \u2013 $title"
                else -> title
            }
            if (display != currentLabel) {
                nowPlaying.text = display
                nowStation.text = currentLabel
                nowStation.visibility = View.VISIBLE
            } else {
                nowPlaying.text = currentLabel
                nowStation.visibility = View.GONE
            }
            loadArt(md.artworkUri?.toString(), currentLogoUrl)
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            if (localPlayback) return
            val idx = controller?.currentMediaItemIndex ?: return
            if (idx in stations.indices) {
                currentLabel = stations[idx].name
                currentLogoUrl = stations[idx].logoUrl
                if (stationSpinner.selectedItemPosition != idx) stationSpinner.setSelection(idx)
                nowPlaying.text = currentLabel
                nowStation.visibility = View.GONE
                loadArt(null, currentLogoUrl)
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateTransport()
        override fun onPlaybackStateChanged(state: Int) = updateTransport()
        override fun onPlayerError(error: PlaybackException) {
            val net = error.errorCode in 2000..2999
            val msg = if (net) getString(R.string.reconnecting)
            else "${getString(R.string.unavailable)} (${error.errorCodeName})"
            if (localPlayback) localStatus.text = msg else status.text = msg
            if (!net) { playButton.text = "\u25B6"; localPlayButton.text = "\u25B6" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modeFavorites = findViewById(R.id.modeFavorites)
        modeSearch = findViewById(R.id.modeSearch)
        modeLocal = findViewById(R.id.modeLocal)
        langButton = findViewById(R.id.langButton)
        favoritesSection = findViewById(R.id.favoritesSection)
        searchSection = findViewById(R.id.searchSection)
        localSection = findViewById(R.id.localSection)
        stationSpinner = findViewById(R.id.stationSpinner)
        playButton = findViewById(R.id.playButton)
        prevButton = findViewById(R.id.prevButton)
        nextButton = findViewById(R.id.nextButton)
        switch432 = findViewById(R.id.switch432)
        eightDButton = findViewById(R.id.eightDButton)
        hallButton = findViewById(R.id.hallButton)
        status = findViewById(R.id.status)
        nowPlaying = findViewById(R.id.nowPlaying)
        nowStation = findViewById(R.id.nowStation)
        nowArt = findViewById(R.id.nowArt)
        nowArt.clipToOutline = true
        searchInput = findViewById(R.id.searchInput)
        searchButton = findViewById(R.id.searchButton)
        searchStatus = findViewById(R.id.searchStatus)
        resultsList = findViewById(R.id.resultsList)
        addToDb = findViewById(R.id.addToDb)
        localPickButton = findViewById(R.id.localPickButton)
        localPlayButton = findViewById(R.id.localPlayButton)
        localTitle = findViewById(R.id.localTitle)
        localStatus = findViewById(R.id.localStatus)

        favorites = FavoritesStore.load(this)
        if (!FavoritesStore.isSeeded(this)) {
            if (favorites.none { it.name == RadioStations.pinned.name }) {
                favorites.add(0, RadioStations.pinned)
            }
            FavoritesStore.save(this, favorites)
            FavoritesStore.setSeeded(this)
        }
        stations = favorites
        rebuildSpinner()
        fetchEskaLogo()

        stationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (mode != MODE_FAVORITES || pos !in stations.indices) return
                val c = controller
                if (c != null && playing() && !localPlayback) {
                    if (pos != c.currentMediaItemIndex) c.seekToDefaultPosition(pos)
                    return
                }
                if (stations[pos].name == currentLabel) return
                currentLabel = stations[pos].name
                currentLogoUrl = stations[pos].logoUrl
                nowPlaying.text = currentLabel
                nowStation.visibility = View.GONE
                loadArt(null, currentLogoUrl)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        modeFavorites.setOnClickListener { setMode(MODE_FAVORITES) }
        modeSearch.setOnClickListener { setMode(MODE_SEARCH) }
        modeLocal.setOnClickListener { setMode(MODE_LOCAL) }
        playButton.setOnClickListener { onPlayClicked() }
        prevButton.setOnClickListener { skip(-1) }
        nextButton.setOnClickListener { skip(+1) }
        searchButton.setOnClickListener { doSearch() }
        searchInput.setOnEditorActionListener { _, _, _ -> doSearch(); true }
        resultsList.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            if (pos in results.indices) addFavorite(results[pos])
        }
        addToDb.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.radio-browser.info/add")))
            } catch (_: Exception) {}
        }
        localPickButton.setOnClickListener { pickInput.launch(arrayOf("audio/*")) }
        localPlayButton.setOnClickListener { onLocalPlayClicked() }

        switch432.isChecked = true
        switch432.setOnCheckedChangeListener { _, checked ->
            convert432 = checked
            controller?.playbackParameters = PlaybackParameters(1f, if (checked) pitch432 else 1f)
            updateTransport()
        }

        eightDButton.setOnClickListener {
            EightD.enabled = !EightD.enabled
            styleFx(eightDButton, EightD.enabled)
        }
        hallButton.setOnClickListener {
            HallEffect.enabled = !HallEffect.enabled
            styleFx(hallButton, HallEffect.enabled)
        }
        styleFx(eightDButton, EightD.enabled)
        styleFx(hallButton, HallEffect.enabled)

        langButton.text = if (currentLang() == "en") "PL" else "EN"
        langButton.setOnClickListener {
            val next = if (currentLang() == "en") "pl" else "en"
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setMode(MODE_FAVORITES)
    }

    private fun currentLang(): String {
        val l = AppCompatDelegate.getApplicationLocales()
        return if (!l.isEmpty) (l[0]?.language ?: "pl") else "pl"
    }

    // ---------- Wyszukiwanie radia ----------
    private fun doSearch() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) return
        searchStatus.text = getString(R.string.searching)
        io.execute {
            val res = StationProvider.search(q, 40)
            runOnUiThread {
                results = res
                resultsList.adapter = ArrayAdapter(this, R.layout.result_item, res.map { it.name })
                searchStatus.text = if (res.isEmpty()) getString(R.string.no_results)
                else "${getString(R.string.db_source)} \u2014 ${getString(R.string.search_help)}"
            }
        }
    }

    private fun addFavorite(st: RadioStation) {
        if (favorites.any { it.name.equals(st.name, true) }) {
            Toast.makeText(this, R.string.already, Toast.LENGTH_SHORT).show()
            return
        }
        favorites.add(st)
        FavoritesStore.save(this, favorites)
        stations = favorites
        rebuildSpinner()
        if (playing() && !localPlayback) controller?.addMediaItem(toItem(st))
        Toast.makeText(this, R.string.added, Toast.LENGTH_SHORT).show()
    }

    private fun removeFavorite(st: RadioStation) {
        val idx = stations.indexOfFirst { it.name == st.name }
        val wasPlayingThis = playing() && !localPlayback && currentLabel == st.name
        favorites.removeAll { it.name == st.name }
        FavoritesStore.save(this, favorites)
        stations = favorites
        rebuildSpinner()
        if (wasPlayingThis) {
            controller?.stop()
            playButton.text = "\u25B6"
        } else if (playing() && !localPlayback && idx >= 0) {
            try { controller?.removeMediaItem(idx) } catch (_: Exception) {}
            val cur = controller?.currentMediaItemIndex ?: 0
            if (cur in stations.indices) stationSpinner.setSelection(cur)
        }
        if (stations.isNotEmpty()) stationSpinner.setSelection(0)
        Toast.makeText(this, getString(R.string.removed, st.name), Toast.LENGTH_SHORT).show()
    }

    private fun styleFx(b: Button, active: Boolean) {
        b.setBackgroundResource(if (active) R.drawable.pill_active else R.drawable.field_bg)
        b.setTextColor(if (active) 0xFF1A1024.toInt() else 0xFFE7C77B.toInt())
    }

    /** Logo Eski z bazy radio-browser - zanim wczyta się okładka. */
    private fun fetchEskaLogo() {
        io.execute {
            val r = StationProvider.search("ESKA", 6)
            val logo = r.firstOrNull { it.logoUrl != null }?.logoUrl ?: return@execute
            runOnUiThread {
                pinnedLogo = logo
                val i = favorites.indexOfFirst { it.name == RadioStations.pinned.name }
                if (i >= 0) {
                    favorites[i] = favorites[i].copy(logoUrl = logo)
                    stations = favorites
                    rebuildSpinner()
                    if (mode == MODE_FAVORITES && !playing() && currentLabel == RadioStations.pinned.name) {
                        currentLogoUrl = logo
                        loadArt(null, logo)
                    }
                }
            }
        }
    }

    private fun rebuildSpinner() {
        stationSpinner.adapter = FavAdapter(stations.map { it.name })
    }

    inner class FavAdapter(items: List<String>) :
        ArrayAdapter<String>(this, R.layout.spinner_selected, items) {
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = layoutInflater.inflate(R.layout.spinner_fav_dropdown, parent, false)
            row.findViewById<TextView>(R.id.favName).text = getItem(position)
            val del = row.findViewById<ImageView>(R.id.favDelete)
            del.visibility = View.VISIBLE
            del.setOnClickListener {
                if (position in stations.indices) removeFavorite(stations[position])
            }
            return row
        }
    }

    private fun toItem(st: RadioStation): MediaItem {
        val mdb = MediaMetadata.Builder().setTitle(st.name).setStation(st.name)
        st.nowPlayingId?.let { mdb.setExtras(Bundle().apply { putInt("npId", it) }) }
        return MediaItem.Builder().setUri(st.url).setMediaMetadata(mdb.build()).build()
    }

    private fun radioItems(): List<MediaItem> = stations.map { toItem(it) }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture.addListener({
            val c = controllerFuture.get()
            controller = c
            c.addListener(playerListener)
            convert432 = c.playbackParameters.pitch < 0.999f
            switch432.isChecked = convert432
            localPlayback = isCurrentLocal()
            updateTransport()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
        controller = null
        super.onStop()
    }

    private fun isCurrentLocal(): Boolean {
        val s = controller?.currentMediaItem?.localConfiguration?.uri?.scheme ?: return false
        return s == "content" || s == "file"
    }

    // ---------- Radio ----------
    private fun onPlayClicked() {
        val c = controller ?: run { status.text = getString(R.string.buffering); return }
        if (playing() && !localPlayback) {
            c.stop()
            playButton.text = "\u25B6"
            status.text = getString(R.string.stopped)
            return
        }
        val pos = stationSpinner.selectedItemPosition.coerceIn(0, stations.size - 1)
        playRadioAt(pos)
    }

    private fun playRadioAt(index: Int) {
        val c = controller ?: return
        val items = radioItems()
        if (items.isEmpty()) return
        localPlayback = false
        val idx = index.coerceIn(0, items.size - 1)
        c.repeatMode = Player.REPEAT_MODE_ALL
        c.setMediaItems(items, idx, 0L)
        c.prepare()
        c.play()
        currentLabel = stations[idx].name
        currentLogoUrl = stations[idx].logoUrl
        nowPlaying.text = currentLabel
        nowStation.visibility = View.GONE
        loadArt(null, currentLogoUrl)
        playButton.text = "\u25A0"
        status.text = getString(R.string.buffering)
    }

    private fun skip(delta: Int) {
        val c = controller
        if (c != null && playing() && !localPlayback) {
            if (delta > 0) c.seekToNextMediaItem() else c.seekToPreviousMediaItem()
        } else {
            val n = stations.size
            if (n == 0) return
            val cur = stationSpinner.selectedItemPosition.coerceIn(0, n - 1)
            stationSpinner.setSelection(((cur + delta) % n + n) % n)
        }
    }

    // ---------- Plik z telefonu ----------
    private fun playLocal() {
        val c = controller ?: return
        val uri = pickedFile ?: return
        localPlayback = true
        c.repeatMode = Player.REPEAT_MODE_OFF
        val item = MediaItem.Builder().setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(pickedName ?: "Plik").build())
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
        localTitle.text = pickedName ?: getString(R.string.local_empty)
        localPlayButton.text = "\u25A0"
        localStatus.text = getString(R.string.buffering)
    }

    private fun onLocalPlayClicked() {
        val c = controller ?: return
        if (localPlayback && playing()) {
            c.stop()
            localPlayButton.text = "\u25B6"
            localStatus.text = getString(R.string.stopped)
            return
        }
        if (pickedFile == null) { pickInput.launch(arrayOf("audio/*")); return }
        playLocal()
    }

    private fun updateTransport() {
        val c = controller ?: return
        val buffering = c.playbackState == Player.STATE_BUFFERING
        val playingNow = c.isPlaying
        val sym = if (playingNow || buffering) "\u25A0" else "\u25B6"
        playButton.text = sym
        localPlayButton.text = sym
        val txt = when {
            buffering -> getString(R.string.buffering)
            playingNow -> getString(if (convert432) R.string.playing_on else R.string.playing_off)
            else -> ""
        }
        if (txt.isNotEmpty()) {
            if (localPlayback) localStatus.text = txt else status.text = txt
        }
    }

    private fun setMode(m: Int) {
        mode = m
        favoritesSection.visibility = if (m == MODE_FAVORITES) View.VISIBLE else View.GONE
        searchSection.visibility = if (m == MODE_SEARCH) View.VISIBLE else View.GONE
        localSection.visibility = if (m == MODE_LOCAL) View.VISIBLE else View.GONE
        stylePill(modeFavorites, m == MODE_FAVORITES)
        stylePill(modeSearch, m == MODE_SEARCH)
        stylePill(modeLocal, m == MODE_LOCAL)
        if (m == MODE_FAVORITES && !playing()) {
            val pos = stationSpinner.selectedItemPosition.coerceIn(0, stations.size - 1)
            if (pos in stations.indices) {
                currentLabel = stations[pos].name
                currentLogoUrl = stations[pos].logoUrl
                nowPlaying.text = currentLabel
                nowStation.visibility = View.GONE
                loadArt(null, currentLogoUrl)
                status.text = getString(R.string.status_favorites)
            }
        }
    }

    private fun stylePill(b: Button, active: Boolean) {
        b.setBackgroundResource(if (active) R.drawable.pill_active else R.drawable.pill_inactive)
        b.setTextColor(if (active) 0xFF1A1024.toInt() else 0xFFE7C77B.toInt())
    }

    private fun loadArt(artUri: String?, logoUrl: String?) {
        val source = artUri ?: logoUrl
        if (source != null) {
            nowArt.load(source) {
                placeholder(R.mipmap.ic_launcher)
                error(R.mipmap.ic_launcher)
                crossfade(true)
            }
        } else {
            nowArt.setImageResource(R.mipmap.ic_launcher)
        }
    }

    private fun fileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
