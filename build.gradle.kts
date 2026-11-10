import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import pw.binom.kotlin.clang.addStatic
import pw.binom.kotlin.clang.clangBuildDynamic
import pw.binom.kotlin.clang.clangBuildStatic
import pw.binom.kotlin.clang.compileTaskName
import pw.binom.kotlin.clang.eachNative
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kn.clang)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.vanniktech.maven.publish)
}

group = "pw.binom"

/*
 * Версия берётся из `-Pversion=` (ручной прогон), затем из имени тега
 * (`GITHUB_REF_NAME` = 1.0.0 в release-workflow), иначе — текущая версия
 * разработки.
 */
version = providers.gradleProperty("version").orNull
    ?: System.getenv("GITHUB_REF_NAME")?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+.*")) }
    ?: "1.0.0"

val KOTLIN_VERSION = "2.4.20"

val NATIVE_CORE_SRC = file("$projectDir/native/serial_core.c")
val NATIVE_CORE_HEADER = file("$projectDir/native/serial_core.h")
val NATIVE_INCLUDE_DIR = file("$projectDir/native")
val JNI_SRC = file("$projectDir/jvm-native/serial_jni.c")

/** Каталог, куда складываются собранные JVM-библиотеки перед упаковкой в jar. */
val JVM_NATIVE_DIR = layout.buildDirectory.dir("jvmNative")

// ---------- Пишем cinterop .def с абсолютными путями ----------

val cinteropDefFile = layout.buildDirectory
    .file("cinterop/serial_core.def")
    .get()
    .asFile

val generateCinteropDef by tasks.registering {
    outputs.file(cinteropDefFile)
    inputs.file(NATIVE_CORE_HEADER)
    doLast {
        cinteropDefFile.parentFile.mkdirs()
        cinteropDefFile.writeText(
            buildString {
                appendLine("headers = ${NATIVE_CORE_HEADER.absolutePath}")
                appendLine("headerFilter = ${NATIVE_CORE_HEADER.absolutePath}")
                appendLine("package = pw.binom.serial.core")
                appendLine("compilerOpts = -I${NATIVE_INCLUDE_DIR.absolutePath}")
            },
        )
    }
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    linuxX64()
    linuxArm64()
    mingwX64()
    macosX64()
    macosArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(kotlin("stdlib"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter:5.10.2")
            }
        }
    }

    /*
     * Натив: C-ядро компилируется в статический архив и линкуется в каждый klib.
     * Потребителю не нужен ни компилятор C, ни установленный NDK — только klib.
     */
    val nativeStaticTaskByTarget = mutableMapOf<KonanTarget, Task>()
    eachNative {
        val staticTask = clangBuildStatic(target = konanTarget, name = "serialcore") {
            konanVersion.set(KOTLIN_VERSION)
            compileArgs("-std=gnu11", "-Wall")
            include(NATIVE_INCLUDE_DIR)
            compileFile(NATIVE_CORE_SRC)
            optimizationLevel(2)
        }
        staticTask.dependsOn(generateCinteropDef)
        staticTask.dependsOn(tasks.named("downloadKotlinNativeDistribution"))
        tasks
            .matching { it.name.startsWith("cinteropSerial_core") }
            .configureEach { dependsOn(generateCinteropDef) }
        nativeStaticTaskByTarget[konanTarget] = staticTask
        tasks.findByName(compileTaskName)?.dependsOn(staticTask)

        binaries {
            compilations["main"].apply {
                addStatic(staticTask.staticFile)
                cinterops {
                    create("serial_core") {
                        defFile = cinteropDefFile
                        includeDirs(NATIVE_INCLUDE_DIR.absolutePath)
                    }
                }
            }
        }
    }

    /*
     * JVM: JNI-обёртка + то же C-ядро в динамическую библиотеку.
     * На Linux-хосте собираем linux и mingw (кросс), Apple — только на macOS.
     */
    /*
     * ⚠️ HostManager.host бросает IllegalStateException на Linux/aarch64:
     * Kotlin/Native не поддерживает эту платформу как ХОСТ (только как target).
     * На Raspberry Pi сборку ведём с x86_64-хоста кроссом, а здесь нужен
     * безопасный fallback, чтобы конфигурация не падала.
     */
    val currentHost: KonanTarget = runCatching { HostManager.host }.getOrElse {
        val osName = System.getProperty("os.name").lowercase()
        val isArm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
        when {
            osName.contains("mac") -> if (isArm) KonanTarget.MACOS_ARM64 else KonanTarget.MACOS_X64
            osName.contains("win") -> KonanTarget.MINGW_X64
            isArm -> KonanTarget.LINUX_ARM64
            else -> KonanTarget.LINUX_X64
        }
    }
    val isMacHost = currentHost == KonanTarget.MACOS_X64 || currentHost == KonanTarget.MACOS_ARM64
    val isLinuxArmHost = currentHost == KonanTarget.LINUX_ARM64

    val jvmHostTargets = buildList {
        add(KonanTarget.LINUX_X64)
        add(KonanTarget.LINUX_ARM64)
        if (!isLinuxArmHost) add(KonanTarget.MINGW_X64)
        if (isMacHost) {
            add(KonanTarget.MACOS_ARM64)
            add(KonanTarget.MACOS_X64)
        }
    }

    val jdkHome = System.getenv("JAVA_HOME")
    val jdkInclude = listOfNotNull(
        jdkHome?.let { "$it/include" },
        "/usr/lib/jvm/java-17-openjdk-amd64/include",
        "/usr/lib/jvm/default-java/include",
    ).firstOrNull { File(it).exists() } ?: ""

    /*
     * Кросс-сборка JVM-библиотеки на macOS для linux/mingw требует чужого
     * `jni_md.h` — в macOS-JDK его нет. CI кладёт эти два заголовка в
     * `build/jni-include/<platform>/` (см. .github/workflows/release.yml).
     */
    fun provisionedJniPlatformDir(platform: String): String =
        File(projectDir, "build/jni-include/$platform").absolutePath

    val hostJdkPlatform = when (currentHost.family) {
        Family.MINGW -> "win32"
        Family.OSX, Family.IOS, Family.TVOS, Family.WATCHOS -> "darwin"
        else -> "linux"
    }

    val jvmBuildTasks = jvmHostTargets.associateWith { target ->
        val platform = when (target.family) {
            Family.MINGW -> "win32"
            Family.OSX -> "darwin"
            else -> "linux"
        }
        val jdkPlatformInclude = listOfNotNull(
            jdkHome?.let { "$it/include/$platform" },
            "/usr/lib/jvm/java-17-openjdk-amd64/include/$platform",
            provisionedJniPlatformDir(platform),
        ).firstOrNull { File(it).exists() }
            ?: jdkHome?.let { "$it/include/$hostJdkPlatform" }?.takeIf { File(it).exists() }
            ?: ""

        clangBuildDynamic(target = target, name = "serialjvm") {
            konanVersion.set(KOTLIN_VERSION)
            compileArgs("-std=gnu11", "-fno-rtti", "-fPIC")
            include(NATIVE_INCLUDE_DIR)
            if (jdkInclude.isNotEmpty()) include(File(jdkInclude))
            if (jdkPlatformInclude.isNotEmpty()) include(File(jdkPlatformInclude))
            compileFile(JNI_SRC)
            compileFile(NATIVE_CORE_SRC)
            linkArgs("-lm")
            optimizationLevel(2)
        }.also { task ->
            task.dependsOn(tasks.named("downloadKotlinNativeDistribution"))
            if (target != currentHost) {
                task.onlyIf("${target.name} toolchain available") {
                    jdkInclude.isNotEmpty()
                }
            }
        }
    }

    val jvmCopyTasks = jvmBuildTasks.mapValues { (target, buildTask) ->
        val extension = when (target.family) {
            Family.MINGW -> "dll"
            Family.OSX -> "dylib"
            else -> "so"
        }
        val osDirectory = when (target.family) {
            Family.MINGW -> "windows"
            Family.OSX -> "macos"
            else -> "linux"
        }
        val archDirectory = when (target) {
            KonanTarget.LINUX_ARM64, KonanTarget.MACOS_ARM64 -> "arm64"
            else -> "x64"
        }
        tasks.register<Copy>("copySerialJvm${target.name}") {
            from(buildTask.dynamicFile)
            rename { "libserialjvm.$extension" }
            into(JVM_NATIVE_DIR.get().dir("$osDirectory-$archDirectory"))
        }
    }

    tasks.named("jvmProcessResources", Copy::class.java).configure {
        dependsOn(jvmCopyTasks.values)
        from(JVM_NATIVE_DIR) {
            include("**/libserialjvm.*")
            into("native")
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    System.getProperty("serial.integration")?.let { systemProperty("serial.integration", it) }
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}

/*
 * Подпись включается только когда есть чем подписывать: CI передаёт
 * `-PsigningUseGpg=true` (+ импортированный ключ), локально можно передать
 * in-memory ключ через `-PsigningInMemoryKey*`. Без ключа `publishToMavenLocal`
 * работает как обычно (в Maven Central такой деплой всё равно не пройдёт
 * валидацию).
 */
val signingEnabled = findProperty("signingUseGpg") == "true" ||
    findProperty("signingInMemoryKey") != null ||
    findProperty("binom.gpg.private_key") != null

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (signingEnabled) {
        signAllPublications()
    }

    coordinates(
        groupId = "pw.binom",
        artifactId = "serial",
        version = project.version.toString(),
    )

    pom {
        name.set("serial")
        description.set(
            "Serial port (COM / tty) access for Kotlin Multiplatform: one C core for JVM, " +
                "Linux, Windows and macOS",
        )
        url.set("https://github.com/caffeine-mgn/serial-kmp")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("subochev")
                name.set("Anton Subochev")
                email.set("caffeine.mgn@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/caffeine-mgn/serial-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/caffeine-mgn/serial-kmp.git")
            url.set("https://github.com/caffeine-mgn/serial-kmp")
        }
    }
}

/*
 * Подпись артефактов.
 *
 * `signingUseGpg=true` — режим CI: приватный ключ импортирован в системный
 * keyring, подпись идёт через бинарь `gpg` (useGpgCmd), имя ключа и пароль
 * приходят в `-Psigning.gnupg.keyName/passphrase`.
 *
 * По умолчанию — локальный режим: ключ читается из Gradle-свойств
 * (`signingInMemoryKey*` или `binom.gpg.*`), системный keyring не трогаем.
 */
pluginManager.withPlugin("signing") {
    if (findProperty("signingUseGpg") == "true") {
        extensions.configure<SigningExtension>("signing") {
            useGpgCmd()
        }
        logger.lifecycle(
            "[signing] Using system gpg via signing.gnupg.keyName=${findProperty("signing.gnupg.keyName")}",
        )
        return@withPlugin
    }

    val key = providers.gradleProperty("signingInMemoryKey").orNull
        ?: providers.gradleProperty("binom.gpg.private_key").orNull
    val keyId = providers.gradleProperty("signingInMemoryKeyId").orNull
        ?: providers.gradleProperty("binom.gpg.key_id").orNull
    val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
        ?: providers.gradleProperty("binom.gpg.password").orNull
    val isBase64 = providers.gradleProperty("signingInMemoryKeyIsBase64").orNull?.toBoolean() ?: false

    if (key != null && keyId != null && password != null) {
        val decodedKey = if (isBase64) {
            String(Base64.getDecoder().decode(key))
        } else {
            key.replace("\\n", "\n")
        }
        extensions.configure<SigningExtension>("signing") {
            useInMemoryPgpKeys(keyId, decodedKey, password)
        }
        logger.lifecycle("[signing] Using in-memory PGP key $keyId")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
}
