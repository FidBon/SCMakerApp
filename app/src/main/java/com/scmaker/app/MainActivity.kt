package com.scmaker.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scmaker.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pickedBitmap: Bitmap? = null
    private var lastBuiltBytes: ByteArray? = null
    private var lastFileName: String = "background_custom.sc"

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
            binding.imagePreview.setImageBitmap(bmp)
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

        // Прочитать инфу о шаблоне (size, имя экспорта)
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
    }

    private fun build() {
        val bmp = pickedBitmap ?: return
        val exportName = binding.editExportName.text.toString().trim().ifBlank { null }
        val fileName = binding.editFileName.text.toString().trim()
            .ifBlank { "background_custom" }
            .let { if (it.endsWith(".sc")) it else "$it.sc" }

        binding.btnBuild.isEnabled = false
        binding.btnSave.isEnabled = false
        binding.txtStatus.text = "Собираю..."

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    SCBuilder.build(this@MainActivity, bmp, exportName)
                }
            }
            result.fold(
                onSuccess = { bytes ->
                    lastBuiltBytes = bytes
                    lastFileName = fileName
                    binding.txtStatus.text =
                        "Готово: ${bytes.size} байт.\nИмя файла: $fileName\n" +
                        "Нажми «Сохранить»."
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

    private fun saveToDownloads() {
        val bytes = lastBuiltBytes ?: return
        // На API 29+ используем MediaStore Downloads, иначе SAF
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
