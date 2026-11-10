# TASK-01 — serial-kmp: KMP-библиотека работы с компортом

## Цель

Kotlin Multiplatform библиотека для работы с последовательными портами (COM / tty).
Таргеты: **JVM, linuxX64, linuxArm64, mingwX64, macosX64, macosArm64**.
Android, iOS и USB-драйверы **вне скоупа** (см. § 9).

## 1. Принцип

Работаем **только с системными узлами**: `/dev/tty*`, `/dev/cu.*`, `COM1..COM256`.
Драйверы USB-адаптеров — забота ОС и пользователя. Библиотека НЕ реализует
USB-протоколы чипов (CP210x/CH34x/FTDI), НЕ грузит kernel extensions,
НЕ требует root.

Обоснование (проверено на живых машинах):
- Linux: `ch341.ko`, `cp210x.ko`, `ftdi_sio.ko`, `cdc_acm` входят в ядро → `/dev/ttyUSB0`;
- Windows: `usbser.sys` + вендорские VCP-драйверы → `COM3`;
- macOS: `AppleUSBCDC.kext` покрывает только CDC-ACM. Для FTDI/CP210x/CH34x
  пользователь ставит вендорский драйвер сам (стандартная практика).

## 2. Структура

```
serial-kmp/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── config/detekt/detekt.yml
├── .editorconfig
├── README.md         (англ.)
├── README.ru.md      (рус.)
├── TESTING.md
├── LICENSE           (Apache-2.0)
└── src/
    ├── commonMain/kotlin/pw/binom/serial/
    │   ├── SerialPort.kt          — интерфейс
    │   ├── SerialPortInfo.kt      — дескриптор порта
    │   ├── SerialConfig.kt        — параметры линии
    │   ├── SerialException.kt     — sealed-иерархия ошибок
    │   └── SerialPorts.kt         — expect-фабрики
    ├── commonTest/kotlin/pw/binom/serial/
    │   └── SerialConfigTest.kt
    ├── jvmMain/kotlin/pw/binom/serial/
    │   ├── JvmSerialPorts.kt
    │   └── JvmSerialPort.kt
    ├── linuxMain/kotlin/pw/binom/serial/
    │   ├── PosixSerialPorts.kt
    │   └── PosixSerialPort.kt
    ├── macosMain/kotlin/pw/binom/serial/
    │   └── MacosSerialPorts.kt    — IOKit для энумерации
    ├── mingwMain/kotlin/pw/binom/serial/
    │   ├── WinSerialPorts.kt
    │   └── WinSerialPort.kt
    └── jvmTest/kotlin/pw/binom/serial/
        └── JvmSerialPortTest.kt
```

⚠️ `macosMain` наследуется от `posixMain`? Нет — `applyDefaultHierarchyTemplate()`
создаёт `nativeMain`, `appleMain`, но `linuxMain`/`macosMain`/`mingwMain` — это
имена, которые надо **явно объявить** через `applyDefaultHierarchyTemplate { ... }`
либо `dependsOn`:

```kotlin
applyDefaultHierarchyTemplate {
    common {
        group("posix") {
            withLinux()
            withMacos()
        }
    }
}
```
Даёт source set `posixMain` (src/posixMain). Код termios кладём туда,
`macosMain` только переопределяет энумерацию через IOKit.
Если group не заводится — проще: `posixMain` руками через `dependsOn`.

## 3. Публичный API (commonMain)

```kotlin
package pw.binom.serial

/** Дескриптор найденного порта. */
data class SerialPortInfo(
    /** Системное имя: "COM3", "/dev/ttyUSB0", "/dev/cu.usbserial-1420". */
    val name: String,
    /** Человекочитаемое описание, может быть null (Linux часто не даёт). */
    val description: String? = null,
    /** Путь устройства для передачи в open(); на Windows == name. */
    val path: String = name,
)

enum class Parity { NONE, ODD, EVEN, MARK, SPACE }
enum class StopBits { ONE, ONE_POINT_FIVE, TWO }
enum class FlowControl { NONE, HARDWARE, SOFTWARE }

data class SerialConfig(
    val baudRate: Int = 115200,
    val dataBits: Int = 8,
    val parity: Parity = Parity.NONE,
    val stopBits: StopBits = StopBits.ONE,
    val flowControl: FlowControl = FlowControl.NONE,
    /** Таймаут ожидания данных на чтении, мс. 0 — не ждать. */
    val readTimeoutMs: Int = 1000,
    /** Таймаут записи, мс. */
    val writeTimeoutMs: Int = 1000,
) {
    init {
        require(baudRate > 0) { "baudRate must be positive" }
        require(dataBits in 5..8) { "dataBits must be 5..8" }
        require(readTimeoutMs >= 0) { "readTimeoutMs must be >= 0" }
        require(writeTimeoutMs >= 0) { "writeTimeoutMs must be >= 0" }
    }
}

/** Открытый порт. НЕ потокобезопасен: один порт — один читатель/писатель. */
interface SerialPort : AutoCloseable {
    val info: SerialPortInfo
    val isOpen: Boolean

    /**
     * Прочитать до [size] байт, ожидая не дольше readTimeoutMs.
     * Возвращает 0 при таймауте, -1 при закрытии/EOF.
     * Записывает в [dst] начиная с индекса 0.
     */
    fun read(dst: ByteArray, offset: Int = 0, size: Int = dst.size - offset): Int

    /** Записать всё, иначе [SerialWriteTimeoutException]. */
    fun write(src: ByteArray, offset: Int = 0, size: Int = src.size - offset)

    /** Сбросить входной/выходной буферы. */
    fun flush()

    /** Изменить параметры на лету (baud и т.п.). */
    fun reconfigure(config: SerialConfig)

    /** Оборвать соединение и освободить дескриптор. Идемпотентно. */
    override fun close()
}

sealed class SerialException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Порта нет / занят / нет прав. */
    class OpenException(val portName: String, message: String, cause: Throwable? = null) :
        SerialException(message, cause)

    /** Попытка операции на закрытом порту. */
    class ClosedException(val portName: String) :
        SerialException("Serial port '$portName' is closed")

    /** Ошибка ввода-вывода. */
    class IoException(val portName: String, message: String, cause: Throwable? = null) :
        SerialException(message, cause)

    /** Конфигурация не поддерживается платформой (например STOP_BITS_1_5 на POSIX). */
    class UnsupportedConfigException(message: String) : SerialException(message)

    /** Не удалось записать всё за writeTimeoutMs. */
    class SerialWriteTimeoutException(val portName: String, val written: Int, val expected: Int) :
        SerialException("Write timeout on '$portName': $written of $expected bytes")
}

/** Перечислить доступные порты. Не бросает: при сбое энумерации — пустой список. */
expect fun listSerialPorts(): List<SerialPortInfo>

/** Открыть порт по имени/пути из [listSerialPorts] или вручную. */
expect fun openSerialPort(
    name: String,
    config: SerialConfig = SerialConfig(),
): SerialPort
```

## 4. Реализации

### 4.1. `jvmMain` — JNI к тому же C-ядру

Зависимостей нет вообще. `serial_core.c` + `jvm-native/serial_jni.c`
компилируются через `kn-clang` в `libserialjvm.{so,dylib,dll}` под каждую
поддерживаемую платформу и пакуются в jar под `native/<os>-<arch>/`.

- `NativeLoader` распаковывает нужную библиотеку во временный каталог и
  грузит через `System.load` (не `loadLibrary`: внутри jar линкер её не видит).
- `SerialNative` — единственный класс, знающий про `external fun`.
- Ошибки C приходят как отрицательные `Int` (`SERIAL_ERR_*`) и мапятся в
  `SerialException`.

### 4.2. `posixMain` (linux + macos) — termios

```kotlin
import kotlinx.cinterop.*
import platform.posix.*
```

- open: `open(path, O_RDWR or O_NOCTTY)`, при `<0` → `OpenException` с `strerror(errno)`.
- `tcgetattr` → `cfmakeraw` → `cfsetispeed/cfsetospeed` → `tcsetattr(TCSANOW)`.
- `c_cflag` = `CS8 | CLOCAL | CREAD` (для dataBits/parity/stopbits — маска CSIZE/PARENB/CSTOPB).
- `c_cc[VMIN] = 0u`, `c_cc[VTIME] = (readTimeoutMs / 100).coerceIn(0, 255).toUByte()`.
- `read` = `read(fd, buf, size)`; при −1 с `errno == EAGAIN` → 0; иначе `IoException`.
- `write` = цикл `write()` до полного, с дедлайном по `writeTimeoutMs`.
- `flush` = `tcflush(fd, TCIOFLUSH)`.
- `close` = `close(fd)`, идемпотентно (флаг + `atomicfu` или `@Volatile`).

**Биты скорости (питфолл!)** — константы вида `B115200` в `platform.posix` имеют тип
**Int**, а `cfsetispeed` ждёт `speed_t` (UInt). Писать `.convert()`:
```kotlin
cfsetispeed(tio.ptr, B115200.convert())
```
Маска сброса: `tio.c_cflag and CSIZE.inv().convert()` — порядок именно такой
(`CSIZE.inv()` возвращает Int, `.convert()` — после). Проверено компиляцией.

⚠️ `STOP_BITS_1_5` в POSIX недоступен (только `CSTOPB` = 2 бита) →
`UnsupportedConfigException` для `ONE_POINT_FIVE`.
⚠️ `FlowControl.HARDWARE` → `crtscts` (Linux) / `CCTS_OFLOW|CRTS_IFLOW` (macOS),
разные имена — `expect/actual` внутри posix или `#if` через отдельные source set.

**Энумерация Linux** — `/dev/serial/by-id` (симлинки с описанием в имени) плюс
`/sys/class/tty/*/device` (только те tty, у которых есть device → отсекает виртуальные).
Простой путь: `opendir("/dev")` + фильтр по префиксам `ttyUSB`, `ttyACM`, `ttyS`,
`ttyAMA`, `rfcomm`. Описание — из имени симлинка в `/dev/serial/by-id`.
⚠️ `opendir("/dev")` на этой машине вернул только 20 записей в контейнере — это
норма для LXC, не считать багом.

**Энумерация macOS** — IOKit:
```kotlin
val matching = IOServiceMatching("IOSerialBSDClient")
val kr = IOServiceGetMatchingServices(kIOMainPortDefault, matching, iter.ptr)
// далее IORegistryEntryCreateCFProperty(service, "IOCalloutDevice"/"IODialinDevice", ...)
```
Работает (проверено на Mac-mini, macOS 14.4, M1).
⚠️ `openpty` в `platform.posix` на macOS **отсутствует** — только `posix_openpt/grantpt/unlockpt/ptsname`.

### 4.3. `mingwMain` — Win32

Всё уже есть в `platform.windows`, cinterop НЕ нужен (проверено компиляцией).
Доступно: `CreateFileW`, `DCB`, `COMMTIMEOUTS`, `COMSTAT`, `SetCommState`, `GetCommState`,
`SetCommTimeouts`, `PurgeComm`, `ClearCommError`, `EscapeCommFunction`, `GetCommMask`,
`SetCommMask`, `ReadFile`, `WriteFile`, `OVERLAPPED`, `WaitForSingleObject`, `CancelIoEx`.

- open: `CreateFileW("\\\\.\\COM3", GENERIC_READ or GENERIC_WRITE.convert(), 0u, null,
  OPEN_EXISTING.convert(), 0u, null)`.
  ⚠️ префикс `\\\\.\` обязателен для COM10 и выше.
- config: заполнить `DCB` (BaudRate, ByteSize, Parity=NOPARITY/ODDPARITY/EVENPARITY,
  StopBits=ONESTOPBIT/ONE5STOPBITS/TWOSTOPBITS, fBinary=1, fParity),
  `SetCommState`. `COMMTIMEOUTS`: `ReadIntervalTimeout`, `ReadTotalTimeoutConstant = readTimeoutMs`.
- read/write: `ReadFile`/`WriteFile`.
- flush: `PurgeComm(h, PURGE_RXCLEAR or PURGE_TXCLEAR)` — оба аргумента `UInt`, писать
  `PURGE_RXCLEAR.convert() or PURGE_TXCLEAR.convert()`.

**Энумерация Windows** — реестр, работает на всех версиях (проверено компиляцией):
```kotlin
RegOpenKeyExW(HKEY_LOCAL_MACHINE, "HARDWARE\\DEVICEMAP\\SERIALCOMM", 0u, KEY_READ.convert(), hKey.ptr)
RegEnumValueW(hKey.value, index, nameBuf, nameLen.ptr, null, type.ptr, dataBuf, dataLen.ptr)
```
Значение (`dataBuf` как `WCHAR*`) — имя порта (`COM3`), имя значения — обычно
`\Device\Serial0` или `\Device\VCP0`.
⚠️ `GetCommPorts` (Win10 1803+) в `platform.windows` **нет** — только реестр.
⚠️ `SetupDiGetClassDevsW` / `SP_DEVINFO_DATA` в `platform.windows` **нет**.
Дружественные имена через SetupAPI — потребуют cinterop, в этой задаче НЕ делать:
`description = null`.

## 5. README (оба языка, раздел «Подключение» ПЕРВЫМ)

Координаты: `pw.binom.serial:serial`. Публикация — Nexus `caffeine`
(`http://nexus.xx/repository/caffeine/`, `isAllowInsecureProtocol = true`).

Раздел обязан содержать: URL репозитория, координаты, пометку что таргетные
артефакты (`-jvm`, `-linuxx64`, `-macosarm64`, `-mingwx64`) резолвятся из metadata
сами, команду узнать версию из реестра.

Плюс раздел «Платформы» с честной таблицей:

| Платформа | Как работает | Ограничение |
|---|---|---|
| Linux | `/dev/ttyUSB*`, `ttyACM*`, `ttyS*` | — |
| Windows | `COM1..COM256` | — |
| macOS | `/dev/cu.*` | для FTDI/CP210x/CH34x нужен вендорский драйвер |
| Android | не поддерживается | нет доступа к `/dev/tty*` |
| iOS | не поддерживается | песочница |

## 6. TESTING.md

1. **commonTest** — только валидация `SerialConfig` (require-исключения), 4-5 тестов.
2. **jvmTest** — реальный порт через pty:
   - Linux: `socat -d -d pty,raw,echo=0,link=/tmp/ttyA pty,raw,echo=0,link=/tmp/ttyB`
     запускается из теста через `ProcessBuilder`; эхо на `ttyB` — питон/шелл.
   - macOS: `sysctl`+`posix_openpt` недоступны из JVM → запустить python-скрипт
     с `pty.openpty()`, передать путь через временный файл.
   - Тест: `openSerialPort(ttyA)` → `write("PING")` → `read()` → ассерт.
   - Помечать `@EnabledIfSystemProperty(named="serial.integration", matches="true")`,
     чтобы CI без pty не падал.
3. **Натив-тест linuxX64** — то же через pty, `runBlocking(Dispatchers.Default)`.
4. Ручная проверка на реальном железе — отдельный чек-лист (адаптер CH340 или CP2102).

## 7. Verification gate (перед коммитом)

```bash
./gradlew ktlintFormat
./gradlew ktlintCheck detekt jvmTest linuxX64Test
./gradlew compileKotlinMingwX64 compileKotlinMacosArm64 compileKotlinLinuxArm64
./gradlew jvmTest -Dserial.integration=true
```

Apple-таргеты на Linux **только компилируются**, но проверить их линковку надо **на маке**
(`subochev@mac.xx`, konan 2.4.20, Corretto 17 уже стоят).

## 8. Публикация

CI: `.gitea/workflows/release.yml`, триггер `on: release: types: [published]`,
`./gradlew -Pversion=$TAG publish` через `subochev/devops/publish@main`.
Проверить в Nexus, что легли ВСЕ артефакты: `serial`, `serial-jvm`,
`serial-linuxx64`, `serial-linuxarm64`, `serial-mingwx64`, `serial-macosx64`,
`serial-macosarm64`.

## 9. ВНЕ скоупа (не делать!)

- ❌ Android (`UsbManager`, AAR, jniLibs) — отдельная задача.
- ❌ iOS/tvOS/watchOS — нет доступа к `/dev/tty.*`.
- ❌ USB-протоколы чипов (CP210x/CH34x/FTDI) — ОС отвечает за драйверы.
- ❌ Свой C-код, JNI, cinterop, kn-clang — не нужны, `platform.posix` и
  `platform.windows` покрывают всё.
- ❌ Прокси-библиотеки, ktor, корутины в публичном API.

## 10. Питфоллы (собраны на живых прогонах)

| Питфолл | Решение |
|---|---|
| `B115200` — Int, а нужен UInt | `.convert()` |
| `CSIZE.inv()` — Int | `CSIZE.inv().convert()`, порядок именно такой |
| `System.loadLibrary` не видит `.so` внутри jar | распаковка в temp + `System.load` |
| `.so` из jar не dlopen'ится на Android | не касается, Android вне скоупа |
| `openpty` нет на macOS | `posix_openpt/grantpt/unlockpt/ptsname` |
| `GetCommPorts`/`SetupDi*` нет в `platform.windows` | реестр `SERIALCOMM` |
| `readByteArray()` в kotlinx-io — не то | использовать `port.readBytes(dst, size)` |
| `char[]` из `ReadFile` | `allocArray<ByteVar>`, не `ByteArray` напрямую |
| mingw: `GENERIC_WRITE` — Int | `GENERIC_READ or GENERIC_WRITE.convert()` |
