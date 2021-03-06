/**
 * Designed and developed by Seanghay Yath (@seanghay)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.seanghay.studioexample

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.SparseArray
import android.view.Menu
import android.view.MenuItem
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.text.HtmlCompat
import androidx.core.util.set
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.room.Room
import com.seanghay.studio.engine.AudioTranscoder
import com.seanghay.studio.gles.shader.filter.pack.PackFilter
import com.seanghay.studio.utils.BitmapProcessor
import com.seanghay.studioexample.bottomsheet.FilterPackDialogFragment
import com.seanghay.studioexample.bottomsheet.QuoteDialogFragment
import com.seanghay.studioexample.bottomsheet.SceneOptionsBottomSheet
import com.seanghay.studioexample.dao.md5
import com.seanghay.studioexample.picasso.NewPicassoEngine
import com.seanghay.studioexample.sticker.QuoteState
import com.zhihu.matisse.Matisse
import com.zhihu.matisse.MimeType
import id.zelory.compressor.Compressor
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import org.apache.commons.io.IOUtils
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), FilterPackDialogFragment.FilterPackListener,
    QuoteDialogFragment.QuoteListener, SceneOptionsBottomSheet.SceneOptionStateListener {

    private val slides = arrayListOf<SlideEntity>()
    private val slideAdapter: SlideAdapter = SlideAdapter(slides)
    private lateinit var appDatabase: AppDatabase

    private var audio: AudioEntity? = null

    private val isLoading = MutableLiveData<Boolean>()
    private lateinit var compressor: Compressor
    private lateinit var composer: VideoComposer
    private val transitionAdapter: TransitionsAdapter = TransitionsAdapter(arrayListOf())

    private val quoteStatePool = SparseArray<QuoteState>()
    private lateinit var quoteState: QuoteState
    private var littleBox: LittleBox? = null

    private val compositeDisposable: CompositeDisposable = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // transcodeMp3()

        quoteState = QuoteState(
            text = "Hello, World!",
            textColor = Color.BLACK,
            textSize = 18f.dip(resources),
            scaleFactor = 1f,
            fontFamily = null
        )

        composer = VideoComposer(this)
        compressor = Compressor(this)
        appDatabase = Room.databaseBuilder(this, AppDatabase::class.java, "app-v1")
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .build()

        setupStatusBar(Color.parseColor("#80FFFFFF"))
        setLightStatusBar(true)
        setContentView(R.layout.activity_main)

        isLoading.value = true
        isLoading.observe(this, Observer {
            loadingLayout.visibility = if (it) View.VISIBLE else View.GONE
        })

        initToolbar()
        initTransitions()
        setEvents()
        initPhotos()
        initAudio()

        launch()
        initDurations()

        isLoading.value = false
    }

    private fun initDurations() {
        composer.duration.observe(this, Observer {
            textViewDuration.setText("Total duration: " + formatDuration(it))
        })
    }

    private fun formatDuration(millis: Long): String {
        return String.format(
            "%02d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(millis) - TimeUnit.HOURS.toMinutes(
                TimeUnit.MILLISECONDS.toHours(
                    millis
                )
            ),
            TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(
                TimeUnit.MILLISECONDS.toMinutes(
                    millis
                )
            )
        )
    }

    private fun transcodeMp3() {
        val file = File(Environment.getExternalStorageDirectory(), "bg.mp3")
        val outputFile = File(Environment.getExternalStorageDirectory(), "output.m4a")

        if (!file.exists()) {

            Toast.makeText(this, "Mp3 does not found", Toast.LENGTH_SHORT).show()
            return
        }

        if (outputFile.exists()) outputFile.delete()

        thread {
            val transcoder = AudioTranscoder(file.path, outputFile.path)
            transcoder.setup()
            transcoder.transcode()
            transcoder.release()
        }
    }

    private fun launch() {

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureSizeChanged(
              surface: SurfaceTexture?,
              width: Int,
              height: Int
            ) {
                littleBox?.resize(width, height)
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
                // littleBox?.release()
                return false
            }

            override fun onSurfaceTextureAvailable(
              surface: SurfaceTexture?,
              width: Int,
              height: Int
            ) {
                if (surface == null) return
                littleBox = LittleBox(this@MainActivity, surface, width, height)
                littleBox?.setComposer(composer)

                littleBox?.playProgress = {
                    seekBarProgress.progress = (it * 100f).toInt()
                }

                seekBarProgress.setOnSeekBarChangeListener(object :
                    SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                        textViewProgress.text = HtmlCompat.fromHtml(
                            "Progress: <strong>$p1%</strong>",
                            HtmlCompat.FROM_HTML_MODE_LEGACY
                        )

                        val progress = p1.toFloat() / p0!!.max.toFloat()
                        if (p2) composer.renderAtProgress(progress)
                        dispatchDraw()
                    }

                    override fun onStartTrackingTouch(p0: SeekBar?) {
                        littleBox?.pause()
                        imageButtonControl.setImageResource(R.drawable.ic_play)
                    }

                    override fun onStopTrackingTouch(p0: SeekBar?) {
                    }
                })
            }
        }
    }

    private fun dispatchDraw() {
        littleBox?.draw()
    }

    override fun newQuoteState(quoteState: QuoteState) {
        quoteStatePool[slideAdapter.selectedAt] = quoteState
        this.quoteState = quoteState
        dispatchDraw()
    }

    override fun onReceiveQuoteBitmap(bitmap: Bitmap) {
        if (slideAdapter.selectedAt != -1) {
            composer.setQuoteAt(slideAdapter.selectedAt, bitmap)
        } else composer.applyQuoteBitmap(bitmap)
        dispatchDraw()
    }

    override fun onStateChange(state: SceneOptionsBottomSheet.OptionState) {
        if (state.delete) {
            composer.removeScene(state.id, ::dispatchDraw)
            slides.find { it.path.md5() == state.id }?.let {
                val index = slides.indexOf(it)
                slides.remove(it)
                slideAdapter.notifyItemRemoved(index)
            }
            dispatchDraw()
            return
        }

        val scene = composer.getScenes().find { it.id == state.id } ?: return
        scene.duration = state.duration
        composer.evaluateDuration()
        dispatchDraw()

        composer.updateSceneCropType(state.id, BitmapProcessor.CropType.fromKey(state.crop!!))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe { isLoading.postValue(true) }
            .doOnSuccess { isLoading.postValue(false) }
            .subscribeBy(onError = {
                Toast.makeText(this, "Error while setting crop type", Toast.LENGTH_SHORT).show()
            }) {
                dispatchDraw()
            }.willBeDisposed()
    }

    override fun onFilterPackSaved(filterPack: PackFilter) {
        if (slideAdapter.selectedAt != -1) {
            composer.getScenes()[slideAdapter.selectedAt].filter = filterPack
        } else {
            composer.applyFilterPack(filterPack, true)
        }

        dispatchDraw()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.filter) {
            var filter = composer.getCurrentFilterPack()

            if (slideAdapter.selectedAt != -1) {
                filter = composer.getScenes()[slideAdapter.selectedAt].filter
            }

            FilterPackDialogFragment
                .newInstance(filter)
                .show(supportFragmentManager, "filters")
        }

        if (item.itemId == R.id.quote) {
            QuoteDialogFragment.newInstance(quoteState)
                .show(supportFragmentManager, "quote")
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    private fun initToolbar() {
        setSupportActionBar(toolbar)
    }

    private fun initTransitions() {
        val transitions = composer.getTransitions()
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        transitionAdapter.items = transitions

        recyclerViewTransitions.let {
            it.adapter = transitionAdapter
            it.layoutManager = layoutManager
            it.setHasFixedSize(true)
            (it.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
        }

        transitionAdapter.selectionChange = {
            val transition = transitions[transitionAdapter.selectedAt]
            val scene = composer.getScenes().getOrNull(slideAdapter.selectedAt)

            if (scene != null) {
                scene.transition = transition
                recyclerViewTransitions.smoothScrollToPosition(transitionAdapter.selectedAt)
            }

            dispatchDraw()
        }

        transitionAdapter.onLongPressed = {
            val transition = transitions[transitionAdapter.selectedAt]
            composer.getScenes().forEach {
                it.transition = transition
            }
            Toast.makeText(this, "Applied transitions for all slides", Toast.LENGTH_SHORT).show()
            dispatchDraw()
        }
    }

    private fun initAudio() {
        if (audio != null) return
        val audioDb = appDatabase.audioDao().first() ?: return
        setAudio(audioDb)
    }

    private fun dispatchUpdates() {
        val paths = slides.filter { File(it.path).exists() }

        Single.just(paths)
            .map {
                it.map { item -> item.path to BitmapProcessor.loadSync(item.path) }
            }
            .toObservable().switchMap { composer.setScenes(it).toObservable() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnComplete { isLoading.postValue(false) }
            .doOnSubscribe { isLoading.postValue(true) }
            .subscribeBy(onError = {
                Toast.makeText(this, "Error while loading slides", Toast.LENGTH_SHORT).show()
            }) {
                Toast.makeText(this, "Slides loaded", Toast.LENGTH_SHORT).show()
                dispatchDraw()
            }.willBeDisposed()
    }

    private fun initPhotos() {

        if (slides.isEmpty()) {
            val fromDb = appDatabase.slideDao().getAll()
            fromDb.filter { !File(it.path).exists() }.forEach(appDatabase.slideDao()::delete)
            slides.addAll(fromDb.filter { File(it.path).exists() })
        }

        recyclerView.let {
            it.adapter = slideAdapter
            it.setHasFixedSize(true)
            (it.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
        }

        dispatchUpdates()

        slideAdapter.selectionChange = {
            val sceneIndex = slideAdapter.selectedAt
            quoteState = quoteStatePool[sceneIndex] ?: quoteState

            if (sceneIndex >= 0) {
                val transition = composer.getScenes().get(sceneIndex).transition
                val selectedTransition =
                    composer.getTransitions().firstOrNull { it.name == transition.name }

                if (selectedTransition != null) {
                    val indexOf = composer.getTransitions().indexOf(selectedTransition)
                    transitionAdapter.select(indexOf)
                    recyclerViewTransitions.smoothScrollToPosition(indexOf)
                }
            }
        }

        slideAdapter.onLongPress = {
            val sceneIndex = slideAdapter.selectedAt
            if (sceneIndex >= 0) {
                val scene = composer.getScenes()[sceneIndex]

                val state = SceneOptionsBottomSheet.OptionState(
                    id = scene.id,
                    duration = scene.duration,
                    crop = scene.cropType.key()
                )

                SceneOptionsBottomSheet
                    .newInstance(state)
                    .show(
                        supportFragmentManager,
                        "scene-options"
                    )
            }
        }

        buttonDeselect.setOnClickListener {
            slideAdapter.deselectAll()
        }
    }

    private fun setEvents() {
        buttonChoose.setOnClickListener { choosePhotos() }
        buttonChooseAudio.setOnClickListener { chooseAudio() }
        buttonExport.setOnClickListener { exportAsVideoFile() }
        buttonSaveDraft.setOnClickListener { saveDraft() }
        buttonResetDraft.setOnClickListener { resetDraft() }
        imageButtonControl.setOnClickListener {
            if (littleBox?.isPlaying == true) {
                imageButtonControl.setImageResource(R.drawable.ic_play)
                littleBox?.pause()
            } else {
                littleBox?.play()
                imageButtonControl.setImageResource(R.drawable.ic_pause)
            }
        }
    }

    private fun Disposable.willBeDisposed() {
        addTo(compositeDisposable)
    }

    private fun resetDraft() {
        appDatabase.slideDao().deleteAll()
        appDatabase.audioDao().deleteAll()
        Toast.makeText(this, "Draft Reset", Toast.LENGTH_SHORT).show()
    }

    private fun saveDraft() {
        if (slides.isEmpty()) return
        appDatabase.slideDao().upsert(*slides.toTypedArray())
        if (audio != null) appDatabase.audioDao().upsert(audio!!)
        Toast.makeText(this, "Draft Saved", Toast.LENGTH_SHORT).show()
    }

    private fun formatPercent(value: Float): String {
        return "%.2f".format(value * 100f)
    }

    private fun exportAsVideoFile() {
        isLoading.value = true
        textViewMessage.text = "Preparing for export..."

        val path = File(externalCacheDir, "my-video-${System.currentTimeMillis()}.mp4").path
        val audioPath = audio?.path

        littleBox?.exportToVideo(path, audioPath, {
            runOnUiThread {
                textViewMessage.text = "Exporting (${formatPercent(it)}%)"
            }
        }) {
            runOnUiThread {
                textViewMessage.text = "Completed"
                isLoading.value = false
                saveAsStory(path)
                play(path)
            }
        }
    }

    private fun saveAsStory(path: String) {
        val storyEntity = StoryEntity(
            title = "My Story",
            path = path,
            createdAt = System.currentTimeMillis()
        )

        appDatabase.storyDao().insert(storyEntity)
        Toast.makeText(this, "Saved to stories", Toast.LENGTH_SHORT).show()
    }

    private fun chooseAudio() {
        val audioPickerIntent =
            Intent(Intent.ACTION_PICK, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(audioPickerIntent, 1)
    }

    private fun choosePhotos() {
        if (ActivityCompat.checkSelfPermission(
                this,
                READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            showPhotoChooser()
        } else {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, READ_EXTERNAL_STORAGE)) {
                Toast.makeText(this, "Permission required please enable it!", Toast.LENGTH_SHORT)
                    .show()
            } else ActivityCompat.requestPermissions(
                this,
                arrayOf(READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE),
                3
            )
        }
    }

    private fun showPhotoChooser() {
        Matisse.from(this)
            .choose(MimeType.ofImage())
            .maxSelectable(30)
            .theme(R.style.Matisse_Dracula)
            .countable(true)
            .imageEngine(NewPicassoEngine())
            .forResult(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        compositeDisposable.clear()
        composer.release()
    }

    override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 3 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showPhotoChooser()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 0 && resultCode == Activity.RESULT_OK) {
            val items = Matisse.obtainResult(data)
            applyData(items)
        }

        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            val audioFile = data?.data ?: return

            val inputStream = contentResolver.openInputStream(audioFile) ?: return
            val cursor = contentResolver.query(audioFile, null, null, null, null)
            val nameColumn = cursor!!.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            val name = cursor.getString(nameColumn)

            val outputFile = File(externalCacheDir, "audio-${UUID.randomUUID()}" + name)
            val fileOutputStream = FileOutputStream(outputFile)
            IOUtils.copy(inputStream, fileOutputStream)
            inputStream.close()
            fileOutputStream.flush()
            fileOutputStream.close()
            cursor.close()

            val audio = AudioEntity(path = outputFile.path)
            setAudio(audio)
        }
    }

    private fun setAudio(audioEntity: AudioEntity) {
        this.audio = audioEntity
        textViewAudio.text = HtmlCompat.fromHtml(
            "Audio name: <strong>${audioEntity!!.path}</string>",
            HtmlCompat.FROM_HTML_MODE_COMPACT
        )
    }

    private fun applyData(items: List<Uri>) {
        Single.just(items)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .map { items ->
                items.map {
                    val inputStream = contentResolver.openInputStream(it)
                    val file = File.createTempFile("filename", null, cacheDir)
                    val fileOutputStream = FileOutputStream(file)
                    IOUtils.copy(inputStream, fileOutputStream)
                    val compressedFile = compressor.setQuality(40)
                        .setCompressFormat(Bitmap.CompressFormat.JPEG)
                        .compressToFile(file, "photos-${UUID.randomUUID()}.jpg")
                    SlideEntity(compressedFile.path)
                }
            }.subscribeBy {
                slides.addAll(it)
                slideAdapter.notifyDataSetChanged()
                dispatchUpdates()
                dispatchDraw()
            }.willBeDisposed()
    }

    private fun play(path: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(path))
        intent.setDataAndType(Uri.parse(path), "video/mp4")
        startActivity(intent)
    }

    private fun setupStatusBar(color: Int) {
        window.decorView.systemUiVisibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        else View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.statusBarColor = color
    }

    private fun setLightStatusBar(light: Boolean = true) {
        if (light) {
            window.decorView.systemUiVisibility =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                else View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            window.decorView.systemUiVisibility =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                else View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }
}
