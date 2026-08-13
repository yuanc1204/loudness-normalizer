package com.yc.loudnorm

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

class SettingsActivity : AppCompatActivity() {
    private val settings by lazy { getSharedPreferences("user_settings", MODE_PRIVATE) }
    private lateinit var tvSaveLocation: TextView
    private lateinit var cbHideVideos: CheckBox

    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        settings.edit().putString("output_tree_uri", uri.toString()).apply()
        updateSaveLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tvSaveLocation = findViewById(R.id.tvSaveLocation)
        cbHideVideos = findViewById(R.id.cbHideVideos)
        val cbFast: CheckBox = findViewById(R.id.cbFast)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnChooseFolder).setOnClickListener { chooseFolder.launch(null) }
        findViewById<Button>(R.id.btnDefaultFolder).setOnClickListener {
            settings.edit().remove("output_tree_uri").apply()
            updateSaveLocation()
        }

        cbFast.isChecked = settings.getBoolean("fast_mode", false)
        cbFast.setOnCheckedChangeListener { _, checked ->
            settings.edit().putBoolean("fast_mode", checked).apply()
        }
        cbHideVideos.isChecked = settings.getBoolean("hide_videos", false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cbHideVideos.isChecked = false
            cbHideVideos.isEnabled = false
            cbHideVideos.text = "隐藏文件夹（需要 Android 11 或更高版本）"
        }
        cbHideVideos.setOnCheckedChangeListener { _, checked ->
            settings.edit().putBoolean("hide_videos", checked).apply()
            updateSaveLocation()
        }
        updateSaveLocation()
    }

    private fun updateSaveLocation() {
        val uriText = settings.getString("output_tree_uri", null)
        if (uriText != null) {
            val uri = Uri.parse(uriText)
            val name = DocumentFile.fromTreeUri(this, uri)?.name ?: displayName(uri) ?: "已选择的文件夹"
            tvSaveLocation.text = "自定义：$name"
            cbHideVideos.isEnabled = false
        } else {
            val hidden = settings.getBoolean("hide_videos", false) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            tvSaveLocation.text = if (hidden) {
                "隐藏位置：Movies/.响度均衡"
            } else {
                "默认：视频保存到 Movies/响度均衡，音频保存到 Music/响度均衡"
            }
            cbHideVideos.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}
