package pw.binom.serial.jni

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Загрузчик нативной библиотеки из jar.
 *
 * Библиотека лежит в jar под `native/<os>-<arch>/libserialjvm.<ext>`.
 * При первом обращении распаковывается во временный каталог и грузится
 * через `System.load`. Именно распаковка, а не `System.loadLibrary`:
 * внутри jar библиотека невидима линкеру.
 */
internal object NativeLoader {

    private const val LIBRARY_NAME = "serialjvm"

    fun load() {
        val resourcePath = resourcePath()
        val stream = NativeLoader::class.java.getResourceAsStream(resourcePath)
        if (stream == null) {
            error(
                "Нативная библиотека не найдена в jar: $resourcePath. " +
                    "Поддерживаемые платформы: linux-x64, linux-arm64, windows-x64, macos-arm64, macos-x64.",
            )
        }

        val extension = if (isWindows()) {
            ".dll"
        } else if (isMac()) {
            ".dylib"
        } else {
            ".so"
        }
        val tempDir = Files.createTempDirectory("serialjvm").toFile()
        tempDir.deleteOnExit()
        val target = File(tempDir, "lib$LIBRARY_NAME$extension")
        target.deleteOnExit()

        stream.use { input ->
            Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        System.load(target.absolutePath)
    }

    private fun resourcePath(): String {
        val os = when {
            isWindows() -> "windows"
            isMac() -> "macos"
            else -> "linux"
        }
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "aarch64", "arm64" -> "arm64"
            else -> "x64"
        }
        val extension = when (os) {
            "windows" -> "dll"
            "macos" -> "dylib"
            else -> "so"
        }
        return "/native/$os-$arch/lib$LIBRARY_NAME.$extension"
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    private fun isMac(): Boolean =
        System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }
}
