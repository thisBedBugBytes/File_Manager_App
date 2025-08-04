package io.github.ivruix.filemanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

class MainActivity : AppCompatActivity(), FileAdapter.OnItemClickListener,
    FileAdapter.OnFileLongClickListener {

    enum class SortBy {
        SORT_BY_NAME, SORT_BY_SIZE, SORT_BY_TIME_OF_CREATION, SORT_BY_EXTENSION
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 123
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileAdapter

    private var sortBy: SortBy = SortBy.SORT_BY_NAME
    private var sortAscending: Boolean = true

    private var currentPath: String? = null
    private var isShowingSearchResults = false

    private var searchJob: Job? = null
    private var progressDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        currentPath = intent.getStringExtra("path")

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_CODE_PERMISSIONS)
        } else {
            initRecyclerView()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (!allPermissionsGranted()) {
                Toast.makeText(this, "Permission not granted", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                initRecyclerView()
            }
        }
    }

    private fun initRecyclerView() {
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val path = currentPath ?: Environment.getExternalStorageDirectory().absolutePath
        val file = File(path)

        val fileList = file.listFiles()?.toMutableList() ?: mutableListOf()

        when (sortBy) {
            SortBy.SORT_BY_NAME -> fileList.sortWith(compareBy { it.name.lowercase() })
            SortBy.SORT_BY_SIZE -> fileList.sortWith(compareBy { if (it.isFile) it.length() else 0L })
            SortBy.SORT_BY_TIME_OF_CREATION -> fileList.sortWith(compareBy { getFileTimeOfCreation(it) })
            SortBy.SORT_BY_EXTENSION -> fileList.sortWith(compareBy { if (it.isFile) it.extension.lowercase() else "" })
        }

        if (!sortAscending) {
            fileList.reverse()
        }

        adapter = FileAdapter(this, ArrayList(fileList))
        adapter.setOnItemClickListener(this)
        adapter.setOnFileLongClickListener(this)
        recyclerView.adapter = adapter

        isShowingSearchResults = false
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    override fun onItemClick(file: File) {
        if (file.isDirectory) {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("path", file.absolutePath)
            startActivity(intent)
        } else {
            launchFile(file)
        }
    }

    override fun onFileLongClick(file: File, view: android.view.View) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share file using"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Error sharing file", e)
        }
    }

    private fun launchFile(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(Intent.createChooser(intent, "Open with"))
            } else {
                Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("MainActivity", "Error launching file", e)
        }
    }

    private fun getMimeType(uri: Uri): String {
        return contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.let {
                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase())
            } ?: "*/*"
    }

    private fun getFileTimeOfCreation(file: File): Long {
        return try {
            val attr = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            attr.creationTime().toMillis()
        } catch (e: Exception) {
            0L
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu?.add(0, R.id.menu_search, 999, "Search")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_sort_by_name -> {
                sortBy = SortBy.SORT_BY_NAME
                initRecyclerView()
            }
            R.id.menu_sort_by_size -> {
                sortBy = SortBy.SORT_BY_SIZE
                initRecyclerView()
            }
            R.id.menu_sort_by_time_of_creation -> {
                sortBy = SortBy.SORT_BY_TIME_OF_CREATION
                initRecyclerView()
            }
            R.id.menu_sort_by_extension -> {
                sortBy = SortBy.SORT_BY_EXTENSION
                initRecyclerView()
            }
            R.id.menu_sort_ascending -> {
                sortAscending = true
                initRecyclerView()
            }
            R.id.menu_sort_descending -> {
                sortAscending = false
                initRecyclerView()
            }
            R.id.menu_search -> {
                showSearchDialog()
            }
        }
        return true
    }

    private fun showSearchDialog() {
        val editText = EditText(this)
        editText.hint = "Enter file or folder name"

        val dialog = AlertDialog.Builder(this)
            .setTitle("Search")
            .setView(editText)
            .setPositiveButton("Search") { _, _ ->
                val query = editText.text.toString().trim()
                if (query.isNotEmpty()) {
                    startSearch(query)
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ ->
                initRecyclerView()
            }
            .create()

        dialog.show()
    }

    private fun startSearch(query: String) {
        searchJob?.cancel()

        Log.d("MainActivity", "Starting search for query: $query")

        progressDialog = AlertDialog.Builder(this)
            .setTitle("Searching...")
            .setMessage("Please wait while searching files.")
            .setCancelable(false)
            .create()
        progressDialog?.show()

        searchJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val resultFiles = searchFilesRecursively(File(Environment.getExternalStorageDirectory().absolutePath), query)
                withContext(Dispatchers.Main) {
                    progressDialog?.dismiss()
                    if (resultFiles.isEmpty()) {
                        Toast.makeText(this@MainActivity, "No matching files or folders found.", Toast.LENGTH_SHORT).show()
                    } else {
                        adapter = FileAdapter(this@MainActivity, resultFiles)
                        adapter.setOnItemClickListener(this@MainActivity)
                        adapter.setOnFileLongClickListener(this@MainActivity)
                        recyclerView.adapter = adapter
                        isShowingSearchResults = true
                        Log.d("MainActivity", "Search found ${resultFiles.size} results")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Search failed: ${e.localizedMessage}", e)
                withContext(Dispatchers.Main) {
                    progressDialog?.dismiss()
                    Toast.makeText(this@MainActivity, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun searchFilesRecursively(root: File, query: String): ArrayList<File> {
        val result = ArrayList<File>()

        try {
            val files = root.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.name.contains(query, ignoreCase = true)) {
                        result.add(file)
                    }
                    if (file.isDirectory) {
                        result.addAll(searchFilesRecursively(file, query))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return result
    }

    override fun onDestroy() {
        searchJob?.cancel()
        val db = FileHashDatabaseHelper(this)
        for (file in getFilesRecursive(Environment.getExternalStorageDirectory())) {
            if (file.isFile) {
                db.insertFileHash(file)
            }
        }
        super.onDestroy()
    }

    private fun getFilesRecursive(dir: File): ArrayList<File> {
        val files = ArrayList<File>()
        val fileList = dir.listFiles()
        if (fileList != null) {
            for (file in fileList) {
                if (file.isDirectory) {
                    files.addAll(getFilesRecursive(file))
                } else {
                    files.add(file)
                }
            }
        }
        return files
    }
}
