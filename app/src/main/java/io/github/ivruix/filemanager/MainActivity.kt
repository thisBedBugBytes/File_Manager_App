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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import android.app.Activity
import android.os.Build


class MainActivity : AppCompatActivity(), FileAdapter.OnItemClickListener,
    FileAdapter.OnFileLongClickListener {

    enum class SortBy {
        SORT_BY_NAME, SORT_BY_SIZE, SORT_BY_TIME_OF_CREATION, SORT_BY_EXTENSION
    }

    companion object {
        private const val TAG = "FileManager_MainActivity"
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

    //search
    private var searchJob: Job? = null
    private var progressDialog: AlertDialog? = null

    //deletion
    private lateinit var deletionManager: FileDeletionManager
    private var pendingDeleteFile: File? = null
    private var pendingDeleteAdapterPosition: Int? = null

    // 1) Launcher for MediaStore.createDeleteRequest() confirmation (StartIntentSenderForResult)
    private val deleteIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val ok = result.resultCode == Activity.RESULT_OK
        if (ok) {
            // system deleted; remove from adapter if we have a pending position
            pendingDeleteAdapterPosition?.let { pos ->
                (recyclerView.adapter as? FileAdapter)?.removeAt(pos)
            }
            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Delete cancelled or failed", Toast.LENGTH_SHORT).show()
        }
        pendingDeleteFile = null
        pendingDeleteAdapterPosition = null
    }

    // 2) Launcher for SAF folder picker
    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // persist permission
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // you should persist this uri (e.g., store in SharedPreferences)
            val treeUri = uri
            // attempt SAF delete now if we have pending file
            pendingDeleteFile?.let { f ->
                val ok = deletionManager.performSafDelete(treeUri, f)
                if (ok) {
                    pendingDeleteAdapterPosition?.let { pos ->
                        (recyclerView.adapter as? FileAdapter)?.removeAt(pos)
                    }
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not find file in selected folder", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "Folder permission not granted", Toast.LENGTH_SHORT).show()
        }
        pendingDeleteFile = null
        pendingDeleteAdapterPosition = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() called")

        setContentView(R.layout.activity_main)
        Log.d(TAG, "Layout set successfully")

        currentPath = intent.getStringExtra("path")
        Log.d(TAG, "Current path from intent: $currentPath")
        deletionManager = FileDeletionManager(this)

        if (!allPermissionsGranted()) {
            Log.w(TAG, "Permissions not granted, requesting permissions")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestManageStoragePermission()
            } else {
                ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_CODE_PERMISSIONS)
            }
        } else {
            Log.d(TAG, "All permissions granted, initializing RecyclerView")
            initRecyclerView()
        }

    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "onRequestPermissionsResult() called with requestCode: $requestCode")

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val permissionsGranted = allPermissionsGranted()
            Log.d(TAG, "Permissions check result: $permissionsGranted")

            if (!permissionsGranted) {
                Log.e(TAG, "Permissions not granted, finishing activity")
                Toast.makeText(this, "Permission not granted", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Log.d(TAG, "Permissions granted, initializing RecyclerView")
                initRecyclerView()
            }
        }
    }

    private fun initRecyclerView() {
        Log.d(TAG, "initRecyclerView() called")

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        Log.d(TAG, "RecyclerView and LayoutManager initialized")

        val path = currentPath ?: Environment.getExternalStorageDirectory().absolutePath
        Log.d(TAG, "Using path: $path")

        val file = File(path)
        Log.d(TAG, "File object created for path: ${file.absolutePath}")
        Log.d(TAG, "File exists: ${file.exists()}, isDirectory: ${file.isDirectory}, canRead: ${file.canRead()}")

        val fileList = file.listFiles()?.toMutableList() ?: mutableListOf()
        Log.d(TAG, "Found ${fileList.size} files/folders")

        when (sortBy) {
            SortBy.SORT_BY_NAME -> {
                Log.d(TAG, "Sorting by name (ascending: $sortAscending)")
                fileList.sortWith(compareBy { it.name.lowercase() })
            }
            SortBy.SORT_BY_SIZE -> {
                Log.d(TAG, "Sorting by size (ascending: $sortAscending)")
                fileList.sortWith(compareBy { if (it.isFile) it.length() else 0L })
            }
            SortBy.SORT_BY_TIME_OF_CREATION -> {
                Log.d(TAG, "Sorting by creation time (ascending: $sortAscending)")
                fileList.sortWith(compareBy { getFileTimeOfCreation(it) })
            }
            SortBy.SORT_BY_EXTENSION -> {
                Log.d(TAG, "Sorting by extension (ascending: $sortAscending)")
                fileList.sortWith(compareBy { if (it.isFile) it.extension.lowercase() else "" })
            }
        }

        if (!sortAscending) {
            Log.d(TAG, "Reversing list for descending order")
            fileList.reverse()
        }

        adapter = FileAdapter(this, ArrayList(fileList))
        adapter.setOnItemClickListener(this)
        adapter.setOnFileLongClickListener(this)

        adapter.setOnDeleteListener { file, position ->
            when (val result = deletionManager.tryDeleteAny(file)) {
                FileDeletionManager.DeleteResult.Deleted -> {
                    adapter.removeAt(position)
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                }
                is FileDeletionManager.DeleteResult.RequireMediaConfirmation -> {
                    // store pending
                    pendingDeleteFile = file
                    pendingDeleteAdapterPosition = position
                    val intentSender = result.intentSender
                    val request = IntentSenderRequest.Builder(intentSender).build()
                    deleteIntentLauncher.launch(request)
                }
                FileDeletionManager.DeleteResult.RequireTreePicker -> {
                    // store pending, ask user to pick folder (SAF)
                    pendingDeleteFile = file
                    pendingDeleteAdapterPosition = position
                    openDocumentTreeLauncher.launch(null)
                }
                is FileDeletionManager.DeleteResult.Failed -> {
                    Toast.makeText(this, "Delete failed: ${result.reason}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        recyclerView.adapter = adapter
        Log.d(TAG, "Adapter created and set to RecyclerView with ${fileList.size} items")

        isShowingSearchResults = false
        Log.d(TAG, "initRecyclerView() completed successfully")
    }

    private fun allPermissionsGranted(): Boolean {
        Log.d(TAG, "Checking permissions...")

        // For Android 11+, check MANAGE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasManageStorage = Environment.isExternalStorageManager()
            Log.d(TAG, "MANAGE_EXTERNAL_STORAGE: ${if (hasManageStorage) "GRANTED" else "DENIED"}")
            return hasManageStorage
        }

        // For older versions, check the traditional permissions
        for (permission in requiredPermissions) {
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission $permission: ${if (granted) "GRANTED" else "DENIED"}")
            if (!granted) {
                return false
            }
        }
        Log.d(TAG, "All permissions are granted")
        return true
    }

    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, 124)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 124) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    initRecyclerView()
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }



    override fun onItemClick(file: File) {
        Log.d(TAG, "onItemClick() called for file: ${file.name}")
        Log.d(TAG, "File path: ${file.absolutePath}")
        Log.d(TAG, "File isDirectory: ${file.isDirectory}, isFile: ${file.isFile}")

        if (file.isDirectory) {
            Log.d(TAG, "Opening directory: ${file.absolutePath}")
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("path", file.absolutePath)
            startActivity(intent)
        } else {
            Log.d(TAG, "Launching file: ${file.name}")
            launchFile(file)
        }
    }

    override fun onFileLongClick(file: File, view: android.view.View) {
        Log.d(TAG, "onFileLongClick() called for file: ${file.name}")
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            Log.d(TAG, "Generated URI for sharing: $uri")

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Log.d(TAG, "Share intent created, launching chooser")
            startActivity(Intent.createChooser(shareIntent, "Share file using"))
            Log.d(TAG, "Share chooser launched successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing file: ${file.name}", e)
            Toast.makeText(this, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchFile(file: File) {
        Log.d(TAG, "launchFile() called for: ${file.name}")
        Log.d(TAG, "File path: ${file.absolutePath}")
        Log.d(TAG, "File size: ${file.length()} bytes")

        if (!file.exists()) {
            Log.e(TAG, "File does not exist: ${file.absolutePath}")
            Toast.makeText(this, "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            Log.d(TAG, "Generated URI for file: $uri")

            val mimeType = getMimeType(uri)
            Log.d(TAG, "Detected MIME type: $mimeType")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (intent.resolveActivity(packageManager) != null) {
                Log.d(TAG, "Found app to handle file, launching chooser")
                startActivity(Intent.createChooser(intent, "Open with"))
            } else {
                Log.w(TAG, "No app found to handle file type: $mimeType")
                Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file: ${file.name}", e)
            Toast.makeText(this, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMimeType(uri: Uri): String {
        Log.d(TAG, "getMimeType() called for URI: $uri")

        val mimeType = contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.let {
                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase())
            } ?: "*/*"

        Log.d(TAG, "Resolved MIME type: $mimeType")
        return mimeType
    }

    private fun getFileTimeOfCreation(file: File): Long {
        Log.v(TAG, "Getting creation time for file: ${file.name}")
        return try {
            val attr = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            val creationTime = attr.creationTime().toMillis()
            Log.v(TAG, "Creation time for ${file.name}: $creationTime")
            creationTime
        } catch (e: Exception) {
            Log.w(TAG, "Could not get creation time for ${file.name}: ${e.message}")
            0L
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        Log.d(TAG, "onCreateOptionsMenu() called")
        menuInflater.inflate(R.menu.menu_main, menu)
        menu?.add(0, R.id.menu_search, 999, "Search")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        Log.d(TAG, "Options menu created successfully")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        Log.d(TAG, "onOptionsItemSelected() called with item: ${item.title}")

        when (item.itemId) {
            R.id.menu_sort_by_name -> {
                Log.d(TAG, "Sort by name selected")
                sortBy = SortBy.SORT_BY_NAME
                initRecyclerView()
            }
            R.id.menu_sort_by_size -> {
                Log.d(TAG, "Sort by size selected")
                sortBy = SortBy.SORT_BY_SIZE
                initRecyclerView()
            }
            R.id.menu_sort_by_time_of_creation -> {
                Log.d(TAG, "Sort by creation time selected")
                sortBy = SortBy.SORT_BY_TIME_OF_CREATION
                initRecyclerView()
            }
            R.id.menu_sort_by_extension -> {
                Log.d(TAG, "Sort by extension selected")
                sortBy = SortBy.SORT_BY_EXTENSION
                initRecyclerView()
            }
            R.id.menu_sort_ascending -> {
                Log.d(TAG, "Sort ascending selected")
                sortAscending = true
                initRecyclerView()
            }
            R.id.menu_sort_descending -> {
                Log.d(TAG, "Sort descending selected")
                sortAscending = false
                initRecyclerView()
            }
            R.id.menu_search -> {
                Log.d(TAG, "Search menu item selected")
                showSearchDialog()
            }
        }
        return true
    }

    private fun showSearchDialog() {
        Log.d(TAG, "showSearchDialog() called")

        val editText = EditText(this)
        editText.hint = "Enter file or folder name"

        val dialog = AlertDialog.Builder(this)
            .setTitle("Search")
            .setView(editText)
            .setPositiveButton("Search") { _, _ ->
                val query = editText.text.toString().trim()
                Log.d(TAG, "Search initiated with query: '$query'")
                if (query.isNotEmpty()) {
                    startSearch(query)
                } else {
                    Log.w(TAG, "Empty search query provided")
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                Log.d(TAG, "Search dialog cancelled")
            }
            .setNeutralButton("Reset") { _, _ ->
                Log.d(TAG, "Search reset requested, reinitializing RecyclerView")
                initRecyclerView()
            }
            .create()

        dialog.show()
        Log.d(TAG, "Search dialog displayed")
    }

    private fun startSearch(query: String) {
        Log.d(TAG, "startSearch() called with query: '$query'")

        searchJob?.cancel()
        Log.d(TAG, "Previous search job cancelled (if any)")

        progressDialog = AlertDialog.Builder(this)
            .setTitle("Searching...")
            .setMessage("Please wait while searching files.")
            .setCancelable(false)
            .create()
        progressDialog?.show()
        Log.d(TAG, "Progress dialog shown")

        val searchRoot = File(Environment.getExternalStorageDirectory().absolutePath)
        Log.d(TAG, "Starting search in root directory: ${searchRoot.absolutePath}")

        searchJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Search coroutine started on IO dispatcher")
                val startTime = System.currentTimeMillis()

                val resultFiles = searchFilesRecursively(searchRoot, query)

                val endTime = System.currentTimeMillis()
                Log.d(TAG, "Search completed in ${endTime - startTime}ms, found ${resultFiles.size} results")

                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Switching to Main dispatcher to update UI")
                    progressDialog?.dismiss()
                    Log.d(TAG, "Progress dialog dismissed")

                    if (resultFiles.isEmpty()) {
                        Log.i(TAG, "No search results found for query: '$query'")
                        Toast.makeText(this@MainActivity, "No matching files or folders found.", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.i(TAG, "Displaying ${resultFiles.size} search results")
                        adapter = FileAdapter(this@MainActivity, resultFiles)
                        adapter.setOnItemClickListener(this@MainActivity)
                        adapter.setOnFileLongClickListener(this@MainActivity)
                        recyclerView.adapter = adapter
                        isShowingSearchResults = true
                        Log.d(TAG, "Search results adapter set successfully")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search failed with exception: ${e.localizedMessage}", e)
                withContext(Dispatchers.Main) {
                    progressDialog?.dismiss()
                    Toast.makeText(this@MainActivity, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun searchFilesRecursively(root: File, query: String): ArrayList<File> {
        Log.v(TAG, "searchFilesRecursively() called for directory: ${root.absolutePath}")
        val result = ArrayList<File>()

        try {
            val files = root.listFiles()
            if (files != null) {
                Log.v(TAG, "Scanning ${files.size} items in ${root.absolutePath}")
                for (file in files) {
                    if (file.name.contains(query, ignoreCase = true)) {
                        Log.v(TAG, "Match found: ${file.absolutePath}")
                        result.add(file)
                    }
                    if (file.isDirectory) {
                        Log.v(TAG, "Recursively searching directory: ${file.name}")
                        result.addAll(searchFilesRecursively(file, query))
                    }
                }
            } else {
                Log.w(TAG, "Could not list files in directory: ${root.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching in directory ${root.absolutePath}: ${e.message}", e)
        }

        Log.v(TAG, "Found ${result.size} matches in ${root.absolutePath}")
        return result
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called")

        searchJob?.cancel()
        Log.d(TAG, "Search job cancelled")

        // Move database operations to background thread so it doesn't block
        Thread {
            Log.d(TAG, "Starting file hash database update")
            val startTime = System.currentTimeMillis()

            val db = FileHashDatabaseHelper(this@MainActivity)
            val allFiles = getFilesRecursive(Environment.getExternalStorageDirectory())
            Log.d(TAG, "Found ${allFiles.size} total files for hash database update")

            var processedFiles = 0
            for (file in allFiles) {
                if (file.isFile) {
                    db.insertFileHash(file)
                    processedFiles++
                    if (processedFiles % 100 == 0) {
                        Log.d(TAG, "Processed $processedFiles files for hash database")
                    }
                }
            }

            val endTime = System.currentTimeMillis()
            Log.d(TAG, "File hash database update completed in ${endTime - startTime}ms, processed $processedFiles files")
        }.start()

        super.onDestroy()
        Log.d(TAG, "onDestroy() completed")
    }
    private fun getFilesRecursive(dir: File): ArrayList<File> {
        Log.v(TAG, "getFilesRecursive() called for directory: ${dir.absolutePath}")
        val files = ArrayList<File>()
        val fileList = dir.listFiles()
        if (fileList != null) {
            Log.v(TAG, "Processing ${fileList.size} items in ${dir.absolutePath}")
            for (file in fileList) {
                if (file.isDirectory) {
                    files.addAll(getFilesRecursive(file))
                } else {
                    files.add(file)
                }
            }
        } else {
            Log.w(TAG, "Could not list files in directory: ${dir.absolutePath}")
        }
        Log.v(TAG, "Found ${files.size} files recursively in ${dir.absolutePath}")
        return files
    }
}