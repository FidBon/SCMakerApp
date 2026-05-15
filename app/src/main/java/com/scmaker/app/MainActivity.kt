package com.scmaker.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scmaker.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pickedBitmap: Bitmap? = null
    private var pickedFileName: String? = null
    private var lastBuiltBytes: ByteArray? = null
    private var lastFileName: String = "background_custom.sc"
    private var bgMusic: MediaPlayer? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            val bmp = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            if (bmp == null) {
                toast("Не удалось прочитать изображение")
                return@registerForActivityResult
            }
            pickedBitmap = bmp
            pickedFileName = queryDisplayName(uri)
            binding.imagePreview.setImageBitmap(bmp)
            // Авто-имя выходного файла из имени картинки
            pickedFileName?.let { name ->
                val baseName = name.substringBeforeLast('.', name)
                if (binding.editFileName.text?.toString().isNullOrBlank() ||
                    binding.editFileName.text?.toString() == "background_custom") {
                    binding.editFileName.setText(baseName)
                }
            }
            binding.txtStatus.text =
                "Выбрано: ${bmp.width}×${bmp.height}\n" +
                "Будет масштабировано до ${binding.txtTemplateInfo.tag}"
            binding.btnBuild.isEnabled = true
        } catch (e: Exception) {
            toast("Ошибка: ${e.message}")
        }
    }

    private val saveSc = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            val bytes = lastBuiltBytes ?: return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri).use { it?.write(bytes) }
                toast("Сохранено")
            } catch (e: Exception) {
                toast("Ошибка сохранения: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // info про шаблон
        try {
            val info = SCBuilder.inspectTemplate(this)
            binding.txtTemplateInfo.text =
                "Шаблон: ${info.textureWidth}×${info.textureHeight}, pf=${info.pixelFormat}\n" +
                "Экспорт по умолчанию: ${info.originalExportName}"
            binding.txtTemplateInfo.tag = "${info.textureWidth}×${info.textureHeight}"
            binding.editExportName.setText(info.originalExportName)
        } catch (e: Exception) {
            binding.txtTemplateInfo.text = "Ошибка чтения шаблона: ${e.message}"
        }

        binding.btnPick.setOnClickListener { pickImage.launch("image/*") }

        binding.btnBuild.isEnabled = false
        binding.btnBuild.setOnClickListener { build() }

        binding.btnSave.isEnabled = false
        binding.btnSave.setOnClickListener { saveToDownloads() }

        // default: режим FLAT (плоская 2D картинка)
        binding.radioFlat.isChecked = true

        // Начать фоновую музыку (если файл есть)
        startBgMusic()
    }

    override fun onPause() {
        super.onPause()
        bgMusic?.pause()
    }

    override fun onResume() {
        super.onResume()
        bgMusic?.let { if (!it.isPlaying) try { it.start() } catch (_: Exception) {} }
    }

    override fun onDestroy() {
        super.onDestroy()
        bgMusic?.release()
        bgMusic = null
    }

    /**
     * Пытаемся проиграть assets/music/1.ogg. Если файла нет — молча игнорируем.
     */
    private fun startBgMusic() {
        try {
            val afd = assets.openFd("music/1.ogg")
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            player.isLooping = true
            player.setVolume(0.6f, 0.6f)
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
            bgMusic = player
        } catch (e: Exception) {
            // Файла нет или формат не поддерживается — это нормально.
            // Положи свой 1.ogg в app/src/main/assets/music/, чтобы заработало.
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) { null }
    }

    private fun build() {
        val bmp = pickedBitmap ?: return
        val exportName = binding.editExportName.text.toString().trim().ifBlank { null }
        // Авто-расширение: что бы ни ввёл пользователь, добавим/исправим .sc
        val rawName = binding.editFileName.text.toString().trim().ifBlank { "background_custom" }
        val fileName = sanitizeScName(rawName)
        val mode = if (binding.radioFlat.isChecked) SCBuilder.Mode.FLAT else SCBuilder.Mode.ATLAS

        binding.btnBuild.isEnabled = false
        binding.btnSave.isEnabled = false
        binding.txtStatus.text = "Собираю в режиме ${if (mode == SCBuilder.Mode.FLAT) "FLAT (полноэкран)" else "ATLAS"}…"

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    SCBuilder.build(this@MainActivity, bmp, exportName, mode)
                }
            }
            result.fold(
                onSuccess = { bytes ->
                    lastBuiltBytes = bytes
                    lastFileName = fileName
                    binding.txtStatus.text =
                        "Готово: ${bytes.size} байт.\nИмя файла: $fileName\nНажми «Сохранить»."
                    binding.btnBuild.isEnabled = true
                    binding.btnSave.isEnabled = true
                },
                onFailure = { e ->
                    binding.txtStatus.text = "Ошибка: ${e.message}"
                    binding.btnBuild.isEnabled = true
                }
            )
        }
    }

    /**
     * Любое расширение приводим к .sc.  «my_bg.png» -> «my_bg.sc»,
     * «foo.SC» -> «foo.sc», «foo» -> «foo.sc».
     */
    private fun sanitizeScName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val withoutExt = if (cleaned.contains('.')) cleaned.substringBeforeLast('.') else cleaned
        return "$withoutExt.sc"
    }

    private fun saveToDownloads() {
        val bytes = lastBuiltBytes ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(bytes)
        } else {
            saveViaSaf()
        }
    }

    private fun saveViaMediaStore(bytes: ByteArray) {
        try {
            val resolver = contentResolver
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, lastFileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SCMaker")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri).use { it?.write(bytes) }
            toast("Сохранено в Downloads/SCMaker/$lastFileName")
        } catch (e: Exception) {
            toast("MediaStore ошибка: ${e.message}, пробуем SAF…")
            saveViaSaf()
        }
    }

    private fun saveViaSaf() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, lastFileName)
        }
        saveSc.launch(intent)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
