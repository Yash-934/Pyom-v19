package com.pyom

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

class MainActivity : FlutterActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentProcess: Process? = null
    private val isSetupCancelled = AtomicBoolean(false)

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .retryOnConnectionFailure(true).build()
    }
    private val executor = Executors.newCachedThreadPool()

    private val extDir get() = getExternalFilesDir(null) ?: filesDir
    // FIX: envRoot MUST be on internal storage (filesDir)
    // External storage (extDir) is mounted noexec on Android 10+ —
    // proot cannot mmap+exec binaries from noexec filesystem (exit 255).
    // filesDir = /data/data/com.pyom/files/ — internal EXT4, exec allowed.
    private val envRoot get() = File(filesDir, "linux_env")
    private val binDir get() = File(codeCacheDir, "bin")
    private val prootVersionFile get() = File(binDir, "proot.version")
    private val envConfigFile get() = File(filesDir, "env_config.json")

    // ── FIX: libproot.so in jniLibs → installed to nativeLibraryDir (always +x) ──
    private val prootBundledBin get() = File(applicationInfo.nativeLibraryDir, "libproot.so")

    // FIX: Primary extraction target is filesDir/bin (often +x on Android 10+)
    //      Fallback to codeCacheDir/bin (may be noexec on some ROMs)
    private val prootExtractedBin get() = File(filesDir, "bin/proot")
    private val prootExtractedBinFallback get() = File(codeCacheDir, "bin/proot")

    private val prootBin: File
        get() {
            val v = prootVersionFile.takeIf { it.exists() }?.readText()?.trim() ?: ""
            if (v.startsWith("alt-path:")) {
                val f = File(v.removePrefix("alt-path:")); if (f.exists()) return f
            }
            if (prootBundledBin.exists()) return prootBundledBin
            if (prootExtractedBin.exists()) return prootExtractedBin
            return prootExtractedBinFallback
        }

    private val rootfsSources = mapOf(
        "alpine" to listOf(
            "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            "https://mirrors.ustc.edu.cn/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            "https://mirror.nju.edu.cn/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
        ),
        "ubuntu" to listOf(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.3-base-arm64.tar.gz",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.3-base-arm64.tar.gz",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.3-base-arm64.tar.gz",
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.3-base-arm64.tar.gz",
        ),
    )

    private var eventSink: EventChannel.EventSink? = null
    private val CHANNEL = "com.pyom/linux_environment"
    private val OUTPUT_CHANNEL = "com.pyom/process_output"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        flutterEngine.platformViewsController.registry.registerViewFactory(
            "com.pyom/termux_terminal_view",
            TermuxViewFactory(flutterEngine.dartExecutor.binaryMessenger)
        )

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.pyom/termux_session")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getProotSessionArgs" -> {
                        val envId = call.argument<String>("envId")
                            ?: getInstalledEnvironment()?.get("id") as String?
                            ?: run { result.error("NO_ENV", "No environment installed", null); return@setMethodCallHandler }
                        val envDir = File(envRoot, envId)
                        val pErr = ensureProotBinary()
                        if (pErr != null) { result.error("NO_PROOT", pErr, null); return@setMethodCallHandler }
                        val shell = findShellInEnv(envDir)
                        if (shell == null) { result.error("NO_SHELL", "No shell found in rootfs", null); return@setMethodCallHandler }
                        val tmpDir = File(envDir, "tmp").apply { mkdirs(); setWritable(true, false) }

                        // FIX: NO shell script file at all — exec proot directly via args list
                        // Shell scripts on ANY app-writable dir fail with EACCES (noexec mount).
                        // /system/bin/sh is a system binary — always executable.
                        // We pass the full proot command as a -c "..." inline string.
                        val nativeDir = applicationInfo.nativeLibraryDir
                        val loaderBin = "$nativeDir/libproot-loader.so"
                        val prootCmd = buildString {
                            append("export PROOT_NO_SECCOMP=1; ")
                            append("export PROOT_TMP_DIR='${tmpDir.absolutePath}'; ")
                            append("export PROOT_LOADER='$loaderBin'; ")
                            append("export PROOT_LOADER_32='$nativeDir/libproot-loader32.so'; ")
                            append("export LD_LIBRARY_PATH='$nativeDir'; ")
                            append("export LD_PRELOAD=; ")
                            append("exec '${prootBin.absolutePath}' ")
                            append("--link2symlink ")
                            append("-0 ")
                            append("-r '${envDir.absolutePath}' ")
                            append("-b /dev ")
                            append("-b /dev/urandom:/dev/random ")
                            append("-b /proc ")
                            append("-b /system/etc/hosts:/etc/hosts ")
                            append("-b /proc/stat:/proc/stat ")
                            append("-b /proc/version:/proc/version ")
                            append("-b /sys ")
                            append("-w /root ")
                            append("/bin/sh")
                        }
                        result.success(mapOf(
                            "shellPath" to "/system/bin/sh",
                            "args"      to listOf("/system/bin/sh", "-c", prootCmd),
                            "cwd"       to extDir.absolutePath,
                            "env"       to listOf(
                                "PROOT_NO_SECCOMP=1",
                                "PROOT_TMP_DIR=${tmpDir.absolutePath}",
                                "HOME=/root",
                                "TERM=xterm-256color",
                                "LANG=C.UTF-8",
                                "LD_PRELOAD=",
                                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                            )
                        ))
                    }
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, OUTPUT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(a: Any?, sink: EventChannel.EventSink?) { eventSink = sink }
                override fun onCancel(a: Any?) { eventSink = null }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "setupEnvironment" -> {
                        isSetupCancelled.set(false)
                        val distro = call.argument<String>("distro") ?: "alpine"
                        val envId  = call.argument<String>("envId")  ?: "alpine"
                        executor.execute { setupEnvironment(distro, envId, result) }
                    }
                    "cancelSetup" -> { isSetupCancelled.set(true); result.success(null) }
                    "executeCommand" -> executeCommand(call, result)
                    "isEnvironmentInstalled" -> {
                        val envId = call.argument<String>("envId") ?: ""
                        result.success(isEnvironmentInstalled(envId))
                    }
                    "getInstalledEnvironment" -> result.success(getInstalledEnvironment())
                    "listEnvironments" -> result.success(listEnvironments())
                    "deleteEnvironment" -> {
                        val envId = call.argument<String>("envId") ?: ""
                        result.success(deleteEnvironment(envId))
                    }
                    "getStorageInfo" -> {
                        val prootCheckError = ensureProotBinary()
                        result.success(mapOf(
                            "filesDir"     to filesDir.absolutePath,
                            "envRoot"      to envRoot.absolutePath,
                            "freeSpaceMB"  to (extDir.freeSpace / 1048576L),
                            "totalSpaceMB" to (extDir.totalSpace / 1048576L),
                            "prootVersion" to (prootVersionFile.takeIf { it.exists() }?.readText()?.trim() ?: "bundled"),
                            "prootPath"    to prootBin.absolutePath,
                            "prootExists"  to (prootCheckError == null),
                            "prootError"   to prootCheckError
                        ))
                    }
                    else -> result.notImplemented()
                }
            }
    }

    // ─── PROOT BINARY ─────────────────────────────────────────────────────────

    /**
     * FIX: Improved exec-ability check.
     *
     * Android 10+ enforces W^X: directories that are app-writable (code_cache,
     * etc.) are mounted noexec. Only nativeLibraryDir is guaranteed +x.
     *
     * We run `proot --version` and accept any exit code except:
     *   126 = permission denied / noexec (cannot execute)
     *   127 = not found
     * An IOException with EACCES (error=13) or ENOEXEC (error=8) also means noexec.
     */
    private fun isActuallyExecutable(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            val proc = ProcessBuilder(file.absolutePath, "--version")
                .redirectErrorStream(true).start()
            val exited = proc.waitFor(4, TimeUnit.SECONDS)
            if (!exited) { proc.destroyForcibly(); return true } // running → executable
            val code = proc.exitValue()
            // 126 = cannot exec (noexec fs or wrong arch), 127 = not found
            code != 126 && code != 127
        } catch (e: Exception) {
            val msg = e.message ?: ""
            // EACCES=13 permission denied, ENOEXEC=8 exec format error (noexec mount)
            !msg.contains("error=13") &&
            !msg.contains("error=8")  &&
            !msg.contains("Permission denied") &&
            !msg.contains("ENOEXEC") &&
            !msg.contains("EACCES")
        }
    }

    /**
     * FIX: Correct priority for finding/creating an executable proot binary.
     *
     * Priority order:
     *  1. libproot.so in nativeLibraryDir  ← installed from jniLibs/, always +x
     *  2. filesDir/bin/proot               ← extracted; +x on most ROMs
     *  3. codeCacheDir/bin/proot           ← extracted; noexec on Android 10+ strict
     *  4. Legacy alt-path candidates
     */
    // Setup libs for Termux proot: copies libtalloc.so.2 and loader to
    // /data/data/com.pyom/files/lib/ which matches proot's patched RUNPATH
    private fun setupProotLibs() {
        val libDir = File(filesDir, "lib").apply { mkdirs() }
        val nativeDir = File(applicationInfo.nativeLibraryDir)
        
        // Copy libtalloc.so.2 → filesDir/lib/ (matches RUNPATH in patched proot)
        val talloc = File(nativeDir, "libtalloc.so.2")
        val tallocDst = File(libDir, "libtalloc.so.2")
        if (talloc.exists() && !tallocDst.exists()) {
            talloc.copyTo(tallocDst, overwrite = true)
        }
        
        // Copy loader → filesDir/lib/proot-loader (for PROOT_LOADER env var)
        val loader = File(nativeDir, "libproot-loader.so")
        val loaderDst = File(libDir, "proot-loader")
        if (loader.exists() && !loaderDst.exists()) {
            loader.copyTo(loaderDst, overwrite = true)
            loaderDst.setExecutable(true, false)
        }
    }

    private fun ensureProotBinary(): String? {
        // ── THE KEY INSIGHT ────────────────────────────────────────────────────
        // Java's ProcessBuilder CANNOT exec() binaries from nativeLibDir or filesDir
        // due to SELinux (error=13). So isActuallyExecutable() always returned false.
        // But /system/bin/sh CAN exec() those binaries via shell script.
        // Solution: Just check file EXISTS — don't try to exec() from Java.
        // ────────────────────────────────────────────────────────────────────────

        // 1. libproot.so in nativeLibDir — installed by Android from jniLibs/ ✅
        if (prootBundledBin.exists()) {
            prootBundledBin.setExecutable(true, false)
            return null
        }

        // 2. Already extracted to filesDir?
        if (prootExtractedBin.exists()) {
            prootExtractedBin.setExecutable(true, false)
            return null
        }

        // 3. Extract proot-arm64 from assets → filesDir/bin/proot
        val extractError = tryExtractProot(prootExtractedBin)
        if (extractError == null && prootExtractedBin.exists()) {
            prootVersionFile.writeText("extracted-to-filesDir")
            return null
        }

        // 4. Fallback: codeCacheDir
        val cacheError = tryExtractProot(prootExtractedBinFallback)
        if (cacheError == null && prootExtractedBinFallback.exists()) {
            prootVersionFile.writeText("alt-path:${prootExtractedBinFallback.absolutePath}")
            return null
        }

        // Nothing worked — report clearly
        val nativeFiles = File(applicationInfo.nativeLibraryDir).listFiles()
            ?.take(8)?.joinToString(", ") { it.name } ?: "none"
        return "FATAL: proot binary not found.\n" +
               "nativeLibDir: [$nativeFiles]\n" +
               "Expected: ${prootBundledBin.absolutePath}\n" +
               "extractError: ${extractError ?: cacheError ?: "unknown"}\n" +
               "Android API: ${Build.VERSION.SDK_INT}\n" +
               "FIX: Ensure jniLibs/arm64-v8a/libproot.so exists in the project."
    }

    /**
     * FIX: Extract proot-arm64 from assets to a target file.
     * Sets +x permission via both Java API and chmod shell command.
     * Falls back to APK zip scanning if assets path fails.
     */
    private fun tryExtractProot(dest: File): String? {
        return try {
            dest.parentFile?.mkdirs()

            // PRIMARY: assets/proot-arm64
            try {
                assets.open("proot-arm64").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.setExecutable(true, false)
                dest.setReadable(true, false)
                // Belt-and-suspenders: also run chmod via shell
                Runtime.getRuntime().exec(arrayOf("chmod", "755", dest.absolutePath))
                    .waitFor(2, TimeUnit.SECONDS)
                return null
            } catch (_: Exception) {}

            // FALLBACK: scan APK lib/ entries
            val abiCandidates = listOf("lib/arm64-v8a/libproot.so", "lib/aarch64/libproot.so")
            ZipFile(applicationInfo.sourceDir).use { zip ->
                val entry = abiCandidates.mapNotNull { zip.getEntry(it) }.firstOrNull()
                    ?: return "proot not found in assets or APK"
                zip.getInputStream(entry).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                dest.setExecutable(true, false)
                dest.setReadable(true, false)
                Runtime.getRuntime().exec(arrayOf("chmod", "755", dest.absolutePath))
                    .waitFor(2, TimeUnit.SECONDS)
            }
            null
        } catch (e: Exception) { e.message }
    }

    // ─── ENVIRONMENT MANAGEMENT ───────────────────────────────────────────────
    private fun isEnvironmentInstalled(envId: String): Boolean {
        val envDir = File(envRoot, envId)
        if (!envDir.exists()) return false
        if (File(envDir, "etc/os-release").exists()) return true
        return findShellInEnv(envDir) != null
    }

    private fun getInstalledEnvironment(): Map<String, Any>? =
        listEnvironments().firstOrNull { it["exists"] as Boolean }

    private fun deleteEnvironment(envId: String): Boolean = try {
        File(envRoot, envId).takeIf { it.exists() }?.deleteRecursively()
        envConfigFile.takeIf { it.exists() }?.delete(); true
    } catch (_: Exception) { false }

    private fun listEnvironments(): List<Map<String, Any>> {
        if (!envRoot.exists()) return emptyList()
        return envRoot.listFiles()?.filter { it.isDirectory }?.map { dir ->
            mapOf("id" to dir.name, "path" to dir.absolutePath, "exists" to isEnvironmentInstalled(dir.name))
        } ?: emptyList()
    }

    private fun sendProgress(msg: String, progress: Double) {
        mainHandler.post {
            flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                MethodChannel(messenger, CHANNEL).invokeMethod("onSetupProgress",
                    mapOf("message" to msg, "progress" to progress))
            }
        }
    }

    // ─── SETUP ────────────────────────────────────────────────────────────────
    private fun setupEnvironment(distro: String, envId: String, result: MethodChannel.Result) {
        try {
            val envDir = File(envRoot, envId)
            if (isEnvironmentInstalled(envId)) {
                sendProgress("✅ Environment already installed!", 1.0)
                mainHandler.post { result.success(mapOf("success" to true, "alreadyInstalled" to true)) }
                return
            }
            envDir.mkdirs()

            val prootError = ensureProotBinary()
            if (prootError != null) { mainHandler.post { result.error("SETUP_ERROR", prootError, null) }; return }
            sendProgress("✅ proot ready", 0.05)
            if (isSetupCancelled.get()) { mainHandler.post { result.error("CANCELLED", "Cancelled", null) }; return }

            sendProgress("🌐 Checking network…", 0.08)
            checkNetworkOrThrow()

            sendProgress("Downloading $distro rootfs…", 0.10)
            val tarFile = File(filesDir, "rootfs_${envId}.tar.gz")
            downloadWithFallback(rootfsSources[distro] ?: rootfsSources["alpine"]!!, tarFile, 0.10, 0.60)
            if (isSetupCancelled.get()) { tarFile.delete(); mainHandler.post { result.error("CANCELLED", "Cancelled", null) }; return }

            sendProgress("Extracting rootfs…", 0.62)
            extractTarGz(tarFile, envDir); tarFile.delete()
            if (isSetupCancelled.get()) { mainHandler.post { result.error("CANCELLED", "Cancelled", null) }; return }

            sendProgress("🔧 Repairing rootfs symlinks…", 0.73)
            repairRootfsShell(envDir)

            sendProgress("Configuring environment…", 0.75)
            File(envDir, "etc").mkdirs()
            File(envDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n")
            File(envDir, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
            listOf("tmp", "root", "proc", "sys", "dev").forEach { File(envDir, it).mkdirs() }

            // ── TERMUX-STYLE: Pure Java install, no apk/apt binary needed ──
            // apk/apt FAIL on Android: libcrypto.so.3 uses syscalls blocked by kernel
            // Alpine .apk = ZIP format → Java ZipInputStream handles directly
            // Exactly how Termux installs bootstrap: pure Java, no shell, no proot!
            sendProgress("📦 Downloading Python packages…", 0.78)
            if (distro == "ubuntu") {
                installPackagesUbuntu(envDir, envId)
            } else {
                installPackagesAlpineJava(envDir)
            }

            saveEnvConfig(envId, distro)
            sendProgress("✅ Environment ready!", 1.0)
            mainHandler.post { result.success(mapOf("success" to true)) }
        } catch (e: Exception) {
            mainHandler.post { result.error("SETUP_ERROR", e.message ?: "Unknown error", null) }
        }
    }

    // ── ALPINE: Termux Python packages (compiled for Android bionic libc) ─────
    // Alpine/Ubuntu Python FAILS on Android — wrong libc (glibc vs bionic)
    // Termux packages ARE compiled for Android — this is the only thing that works!
    private fun installPackagesAlpineJava(envDir: File) {
        // Termux bootstrap contains Python pre-compiled for Android ARM64
        // It uses bionic libc — works perfectly with proot on any Android version
        val termuxBootstrap = "https://github.com/termux/termux-packages/releases/download/bootstrap-2024.01.11-r1/bootstrap-aarch64.zip"
        val termuxMirror    = "https://packages.termux.dev/apt/termux-main/pool/main/p/python/python_3.11.6_aarch64.deb"

        sendProgress("📦 Downloading Termux Python (Android-compatible)…", 0.78)
        
        // Strategy: extract Termux bootstrap zip which has Python already
        val tmp = File(filesDir, "termux_bootstrap.zip")
        try {
            // Try to download Termux bootstrap
            var downloaded = false
            val urls = listOf(
                "https://github.com/termux/termux-packages/releases/download/bootstrap-2024.01.11-r1/bootstrap-aarch64.zip",
                "https://github.com/termux/termux-packages/releases/download/bootstrap-2023.11.19-r1/bootstrap-aarch64.zip",
                "https://github.com/termux/termux-packages/releases/download/bootstrap-2023.06.01-r1/bootstrap-aarch64.zip"
            )
            for (url in urls) {
                try {
                    downloadSingleFile(url, tmp)
                    downloaded = true
                    break
                } catch (e: Exception) {
                    android.util.Log.w("PyomSetup", "Bootstrap URL failed: $url")
                }
            }
            
            if (downloaded && tmp.exists() && tmp.length() > 100000) {
                sendProgress("📦 Extracting Python environment…", 0.85)
                // Termux bootstrap = ZIP with SYMLINKS.txt + actual files
                // Extract to envDir/usr (Termux uses /data/data/com.termux/files/usr)
                val usrDir = File(envDir, "usr")
                usrDir.mkdirs()
                extractTermuxBootstrap(tmp, usrDir, envDir)
                sendProgress("🔗 Creating symlinks…", 0.93)
                createPythonSymlinks(envDir)
            } else {
                // Fallback: use pre-built static Python for Android
                downloadStaticPython(envDir)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun extractTermuxBootstrap(zipFile: File, usrDir: File, envDir: File) {
        val symlinks = mutableListOf<Pair<String, String>>()
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == "SYMLINKS.txt" -> {
                        // Read symlinks file like Termux does
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(zip))
                        reader.forEachLine { line ->
                            val parts = line.split("←")
                            if (parts.size == 2) symlinks.add(Pair(parts[0].trim(), parts[1].trim()))
                        }
                    }
                    !entry.isDirectory -> {
                        val outFile = File(usrDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { o -> zip.copyTo(o) }
                        if (entry.name.startsWith("bin/") || entry.name.startsWith("lib/")) {
                            outFile.setExecutable(true, false)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        // Create symlinks (like Termux's TermuxInstaller.java)
        symlinks.forEach { (target, link) ->
            try {
                val linkFile = File(usrDir, link)
                linkFile.parentFile?.mkdirs()
                if (!linkFile.exists()) android.system.Os.symlink(target, linkFile.absolutePath)
            } catch (_: Exception) {}
        }
        // Link usr/bin into /bin for proot
        try { android.system.Os.symlink("usr/bin", File(envDir, "bin").absolutePath) } catch (_: Exception) {}
        try { android.system.Os.symlink("usr/lib", File(envDir, "lib").absolutePath) } catch (_: Exception) {}
    }

    private fun downloadStaticPython(envDir: File) {
        // Fallback: download a statically compiled Python for Android ARM64
        // These are compiled without any libc dependency
        val urls = listOf(
            "https://github.com/extremecoders-re/python-for-android/releases/download/3.11.0/python3.11-android-arm64.zip",
            "https://github.com/GrahamDumpleton/wrapt/releases/download/1.15.0/python-3.11-android-arm64.zip"
        )
        val binDir = File(envDir, "usr/bin").also { it.mkdirs() }
        for (url in urls) {
            try {
                val tmp = File(filesDir, "static_python.zip")
                downloadSingleFile(url, tmp)
                extractApkZip(tmp, envDir)
                tmp.delete()
                break
            } catch (_: Exception) {}
        }
    }

    private fun downloadSingleFile(url: String, dest: File) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body!!.byteStream().use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
        }
    }

    private fun extractApkZip(apkFile: File, destDir: File) {
        // Alpine .apk = ZIP archive containing actual files (usr/bin/python3, etc.)
        try {
            java.util.zip.ZipInputStream(apkFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!name.startsWith(".") && !entry.isDirectory) {
                        val out = File(destDir, name)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { o -> zip.copyTo(o) }
                        if (name.startsWith("usr/bin/") || name.startsWith("bin/") ||
                            name.startsWith("usr/sbin/") || name.startsWith("sbin/")) {
                            out.setExecutable(true, false)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (_: Exception) {
            // Fallback: some apk might be tar.gz
            try { extractTarGz(apkFile, destDir) } catch (_: Exception) {}
        }
    }

    private fun createPythonSymlinks(envDir: File) {
        // Like Termux's Os.symlink() — create symlinks via Java, no shell needed
        // Also create /bin → usr/bin so /bin/sh, /bin/python3 all work
        val dirSymlinks = mapOf(
            "bin" to "usr/bin",
            "lib" to "usr/lib",
            "lib64" to "usr/lib",
            "sbin" to "usr/bin"
        )
        dirSymlinks.forEach { (link, target) ->
            try {
                val f = File(envDir, link)
                if (!f.exists() && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    android.system.Os.symlink(target, f.absolutePath)
                }
            } catch (_: Exception) {}
        }
        
        val binSymlinks = mapOf(
            "usr/bin/python3" to "python3.11",
            "usr/bin/python" to "python3",
            "usr/bin/pip" to "pip3",
            "usr/bin/python3.10" to "python3",
            "usr/local/bin/python3" to "../../../usr/bin/python3"
        )
        binSymlinks.forEach { (link, target) ->
            try {
                val f = File(envDir, link)
                f.parentFile?.mkdirs()
                if (!f.exists() && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    android.system.Os.symlink(target, f.absolutePath)
                }
            } catch (_: Exception) {}
        }
    }

    private fun installPackagesUbuntu(envDir: File, envId: String) {
        val aptConf = File(envDir, "etc/apt/apt.conf.d")
        aptConf.mkdirs()
        File(aptConf, "99sandbox").writeText(
            "APT::Sandbox::User \"root\";\nAcquire::AllowInsecureRepositories \"true\";\nAcquire::Check-Valid-Until \"false\";\n"
        )
        val cmd = "apt-get update -qq 2>&1 | grep -v '^W:' | tail -5; " +
            "apt-get install -y --no-install-recommends --allow-unauthenticated python3 python3-pip 2>&1 | tail -10"
        runCommandInProot(envId, cmd, "/", 600_000)
    }
    private fun saveEnvConfig(envId: String, distro: String) {
        try {
            envConfigFile.writeText("""{"envId": "$envId", "distro": "$distro", "installedAt": ${System.currentTimeMillis()}}""")
        } catch (_: Exception) {}
    }

    private fun executeCommand(call: MethodCall, result: MethodChannel.Result) {
        executor.execute {
            try {
                val envId = call.argument<String>("environmentId") ?: getInstalledEnvironment()?.get("id") as String? ?: ""
                val command = call.argument<String>("command") ?: ""
                val workingDir = call.argument<String>("workingDir") ?: "/"
                val timeoutMs = call.argument<Int>("timeoutMs") ?: 300000
                if (envId.isEmpty()) { mainHandler.post { result.error("EXEC_ERROR", "No Linux environment found.", null) }; return@execute }
                mainHandler.post { result.success(runCommandInProot(envId, command, workingDir, timeoutMs)) }
            } catch (e: Exception) {
                mainHandler.post { result.error("EXEC_ERROR", e.message, null) }
            }
        }
    }

    // ─── ROOTFS REPAIR ────────────────────────────────────────────────────────
    private fun repairRootfsShell(envDir: File) {
        val binDir = File(envDir, "bin")
        val usrBinDir = File(envDir, "usr/bin")
        if (!binDir.exists() && !java.nio.file.Files.isSymbolicLink(binDir.toPath())) {
            if (usrBinDir.exists()) binDir.mkdirs()
        }
        val busybox = listOf("bin/busybox","usr/bin/busybox","sbin/busybox","usr/sbin/busybox")
            .map { File(envDir, it) }.firstOrNull { it.exists() || java.nio.file.Files.isSymbolicLink(it.toPath()) }
        val shellInUsrBin = listOf("usr/bin/sh","usr/bin/bash","usr/local/bin/sh")
            .map { File(envDir, it) }.firstOrNull { it.exists() }
        val binSh = File(envDir, "bin/sh")
        if (!binSh.exists()) {
            when {
                shellInUsrBin != null -> { binDir.mkdirs(); try { shellInUsrBin.copyTo(binSh, true); binSh.setExecutable(true, false) } catch (_: Exception) {} }
                busybox != null -> { binDir.mkdirs(); try { val bb = File(envDir, "bin/busybox"); if (!bb.exists()) { busybox.copyTo(bb, true); bb.setExecutable(true, false) }; bb.copyTo(binSh, true); binSh.setExecutable(true, false) } catch (_: Exception) {} }
                else -> { binDir.mkdirs(); envDir.walk().filter { it.isFile && (it.name == "sh" || it.name == "bash" || it.name == "busybox") }.firstOrNull()?.let { try { it.copyTo(binSh, true); binSh.setExecutable(true, false) } catch (_: Exception) {} } }
            }
        }
        if (binSh.exists()) binSh.setExecutable(true, false)
        val usrBinEnv = File(envDir, "usr/bin/env")
        if (!usrBinEnv.exists() && binSh.exists()) { usrBinDir.mkdirs(); try { usrBinEnv.writeText("#!/bin/sh\nexec \"\$@\"\n"); usrBinEnv.setExecutable(true, false) } catch (_: Exception) {} }
        File(envDir, "tmp").apply { mkdirs(); setWritable(true, false) }
    }

    private fun findShellInEnv(envDir: File): String? {
        for (p in listOf("/bin/bash","/bin/sh","/usr/bin/bash","/usr/bin/sh","/usr/local/bin/bash","/usr/local/bin/sh","/bin/busybox","/usr/bin/busybox")) {
            val f = File(envDir, p.drop(1))
            if (f.exists() || java.nio.file.Files.isSymbolicLink(f.toPath())) return p
        }
        return try { envDir.walk().filter { it.isFile && (it.name == "sh" || it.name == "bash") }.firstOrNull()?.absolutePath?.removePrefix(envDir.absolutePath) } catch (_: Exception) { null }
    }

    // ─── RUN IN PROOT ─────────────────────────────────────────────────────────
    private fun runCommandInProot(envId: String, command: String, workingDir: String, timeoutMs: Int): Map<String, Any> {
        val pErr = ensureProotBinary()
        if (pErr != null) return mapOf("stdout" to "", "exitCode" to -1, "stderr" to pErr)
        val envDir = File(envRoot, envId)
        if (!envDir.exists()) return mapOf("stdout" to "", "exitCode" to -1, "stderr" to "Env not found: ${envDir.absolutePath}")
        val shell = findShellInEnv(envDir)
            ?: return mapOf("stdout" to "", "exitCode" to -1, "stderr" to "No shell in rootfs. Delete env and reinstall.")
        val tmpDir = File(envDir, "tmp").apply { mkdirs(); setWritable(true, false) }

        // FIX: No shell script file — pass proot command inline to /system/bin/sh -c
        // Shell script files are noexec on all app-writable dirs on Android 10+
        val escapedCmd = command.replace("'", "'\''")
        val nativeDir2 = applicationInfo.nativeLibraryDir
        val inlineCmd = buildString {
            append("export PROOT_NO_SECCOMP=1; ")
            append("export PROOT_TMP_DIR='${tmpDir.absolutePath}'; ")
            append("export PROOT_LOADER='$nativeDir2/libproot-loader.so'; ")
            append("export PROOT_LOADER_32='$nativeDir2/libproot-loader32.so'; ")
            append("export LD_LIBRARY_PATH='$nativeDir2'; ")
            append("export LD_PRELOAD=; ")
            append("exec '${prootBin.absolutePath}' ")
            append("--link2symlink -0 ")
            append("-r '${envDir.absolutePath}' ")
            append("-b /dev -b /proc -b /sys ")
            append("-b /dev/urandom:/dev/random ")
            append("-b /system/etc/hosts:/etc/hosts ")
            append("-w '$workingDir' ")
            append("/bin/sh -c '$escapedCmd'")
        }

        val pb = ProcessBuilder(listOf("/system/bin/sh", "-c", inlineCmd)).apply {
            directory(extDir)
            redirectErrorStream(false)
            environment().apply {
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_TMP_DIR", tmpDir.absolutePath)
                put("HOME", "/root"); put("TERM", "xterm-256color")
                put("LANG", "C.UTF-8"); put("LD_PRELOAD", "")
            }
        }
        return try {
            val process = pb.start(); currentProcess = process
            val stdout = StringBuilder(); val stderr = StringBuilder()
            val t1 = Thread { process.inputStream.bufferedReader().lines().forEach { stdout.append(it).append("\n"); mainHandler.post { eventSink?.success(it) } } }
            val t2 = Thread { process.errorStream.bufferedReader().lines().forEach { stderr.append(it).append("\n"); mainHandler.post { eventSink?.success("[err] $it") } } }
            t1.start(); t2.start()
            val done = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            t1.join(1000); t2.join(1000)
            if (done) mapOf("stdout" to stdout.toString(), "stderr" to stderr.toString(), "exitCode" to process.exitValue())
            else { process.destroyForcibly(); mapOf("stdout" to stdout.toString(), "stderr" to "Timed out after ${timeoutMs}ms", "exitCode" to -1) }
        } catch (e: Exception) {
            mapOf("stdout" to "", "stderr" to "Process error: ${e.message}", "exitCode" to -1)
        }
    }

    // ─── NETWORK ──────────────────────────────────────────────────────────────
    private fun bindToActiveNetwork() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork?.let { cm.bindProcessToNetwork(it) }
        } catch (_: Exception) {}
    }

    private fun checkNetworkOrThrow() {
        bindToActiveNetwork()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val ok = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        if (!ok) throw Exception("❌ No internet connection. Check WiFi/mobile data.")
    }

    private fun downloadWithFallback(mirrors: List<String>, dest: File, ps: Double, pe: Double) {
        bindToActiveNetwork(); var lastEx: Exception? = null
        mirrors.forEachIndexed { i, url ->
            if (isSetupCancelled.get()) throw Exception("Cancelled")
            try { sendProgress("📥 Mirror ${i+1}/${mirrors.size}…", ps + 0.01); downloadWithProgress(url, dest, ps, pe); return }
            catch (e: Exception) {
                lastEx = e; if (e.message?.contains("cancelled") == true) throw e
                sendProgress("⚠️ Mirror ${i+1} failed…", ps + 0.02); dest.takeIf { it.exists() }?.delete(); bindToActiveNetwork()
            }
        }
        throw Exception("❌ All mirrors failed. ${lastEx?.message}")
    }

    private fun downloadWithProgress(urlStr: String, dest: File, ps: Double, pe: Double) {
        bindToActiveNetwork()
        okHttpClient.newCall(Request.Builder().url(urlStr).header("User-Agent","Pyom-IDE/1.0 Android").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body ?: throw Exception("Empty body")
            val total = body.contentLength(); var downloaded = 0L; var lastMs = System.currentTimeMillis()
            body.byteStream().use { input -> FileOutputStream(dest).use { out ->
                val buf = ByteArray(65536); var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    if (isSetupCancelled.get()) throw Exception("Cancelled")
                    out.write(buf, 0, n); downloaded += n
                    val now = System.currentTimeMillis()
                    if (now - lastMs > 900) { lastMs = now
                        val ratio = if (total > 0) downloaded.toDouble() / total else 0.4
                        val p = (ps + ratio * (pe - ps)).coerceIn(ps, pe)
                        sendProgress("📥 ${downloaded/1_048_576}MB${if(total>0) "/${total/1_048_576}MB" else ""}", p)
                    }
                }
            }}
            if (dest.length() < 512) { dest.delete(); throw Exception("File too small — server error") }
        }
    }

    private fun extractTarGz(tarFile: File, destDir: File) {
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(tarFile.inputStream()))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (isSetupCancelled.get()) throw Exception("Extraction cancelled")
                if (!tar.canReadEntryData(entry)) { entry = tar.nextEntry; continue }
                val target = File(destDir, entry.name.removePrefix("./"))
                if (!target.canonicalPath.startsWith(destDir.canonicalPath)) { entry = tar.nextEntry; continue }
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        try {
                            val tp = target.toPath(); val lp = java.nio.file.Paths.get(entry.linkName)
                            if (java.nio.file.Files.exists(tp, java.nio.file.LinkOption.NOFOLLOW_LINKS)) java.nio.file.Files.delete(tp)
                            target.parentFile?.mkdirs(); java.nio.file.Files.createSymbolicLink(tp, lp)
                        } catch (_: Exception) {
                            try { val ls = File(destDir, entry.linkName); if (ls.exists() && !ls.isDirectory) { target.parentFile?.mkdirs(); ls.copyTo(target, true) } } catch (_: Exception) {}
                        }
                    }
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { tar.copyTo(it) }
                        if (entry.mode and 0b001001001 != 0) target.setExecutable(true, false)
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentProcess?.destroyForcibly()
        executor.shutdown()
    }
}
