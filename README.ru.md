# serial-kmp

> **Язык**: Русский | [English](./README.md)

Работа с последовательным портом (COM / tty) для Kotlin Multiplatform.
Одно C-ядро, один и тот же код на всех платформах: никаких сторонних
Java-библиотек для COM-портов, никаких вендорных бинарников, никаких
LGPL-зависимостей.

## Подключение

Опубликовано в **Maven Central** как `pw.binom:serial`.

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("pw.binom:serial:VERSION")
}
```

Таргетные артефакты (`-jvm`, `-linuxx64`, `-linuxarm64`, `-macosx64`,
`-macosarm64`, `-mingwx64`) резолвятся из metadata-артефакта сами —
**вручную их не прописывать**.

`VERSION` — текущий релиз; список версий лежит в реестре:

```bash
curl -s "https://repo1.maven.org/maven2/pw/binom/serial/maven-metadata.xml" \
  | grep -oE '<version>[^<]+</version>'
```

### Как поставляется нативный код

- **JVM**: C-ядро и JNI-обёртка собираются в `libserialjvm.{so,dylib,dll}` под
  все поддерживаемые платформы и кладутся в jar по пути
  `native/<os>-<arch>/`. При первом обращении нужная библиотека
  распаковывается во временный каталог и грузится. Потребителю ничего
  доустанавливать не нужно.
- **Kotlin/Native**: то же C-ядро компилируется в статический архив и
  линкуется в каждый klib. Компилятор C потребителю не требуется.

## Использование

```kotlin
import pw.binom.serial.*

// Перечислить доступные порты
val ports = listSerialPorts()
ports.forEach { println("${it.name} -> ${it.path}") }

// Открыть, записать, прочитать, закрыть
val config = SerialConfig(
    baudRate = 115200,
    dataBits = 8,
    parity = Parity.NONE,
    stopBits = StopBits.ONE,
    flowControl = FlowControl.NONE,
    readTimeoutMs = 1_000,
)

openSerialPort("/dev/ttyUSB0", config).use { port ->
    port.write("PING\n".encodeToByteArray())

    val buffer = ByteArray(64)
    val read = port.read(buffer, 0, buffer.size)   // 0 при таймауте
    if (read > 0) println(buffer.decodeToString(0, read))
}
```

`SerialPort.read` возвращает число прочитанных байт, `0` — при таймауте.
Ошибки приходят как `SerialException`: `OpenException`, `ClosedException`,
`IoException`, `UnsupportedConfigException`, `SerialWriteTimeoutException`.

## Платформы

| Платформа | Имена портов | Примечания |
|---|---|---|
| Linux | `/dev/ttyUSB*`, `/dev/ttyACM*`, `/dev/ttyS*`, `/dev/rfcomm*` | энумерация дополнительно читает `/dev/serial/by-id` |
| Windows | `COM1` … `COM256` | энумерация через ключ реестра `SERIALCOMM` |
| macOS | `/dev/cu.*` | энумерация через IOKit `IOSerialBSDClient` |
| Android | — | не поддерживается: у обычного приложения нет доступа к `/dev/tty*` |
| iOS | — | не поддерживается: песочница |

**Драйверы — забота ОС.** Библиотека работает только с системными узлами
устройств. USB-адаптеры (CP210x, CH34x, FTDI) появляются как
`/dev/ttyUSB0` / `COM3` после того, как их подхватило ядро или вендорский
драйвер:

- **Linux** — `ch341`, `cp210x`, `ftdi_sio`, `cdc_acm` есть в штатном ядре.
- **Windows** — `usbser.sys` для CDC-ACM, вендорские VCP-драйверы для
  остальных.
- **macOS** — встроен только CDC-ACM (`AppleUSBCDC.kext`). Для FTDI / CP210x /
  CH34x пользователь ставит вендорский драйвер; без него узел `/dev/cu.*`
  вообще не появится.

### Скорости

| Платформа | Поддерживается |
|---|---|
| Linux | 110 … 4 000 000 (что объявляет `termios.h`) |
| Windows | любое значение в `DCB.BaudRate` |
| macOS | **до 230 400** — в `termios.h` нет констант выше `B230400` |

Неподдерживаемая скорость даёт `UnsupportedConfigException`. Так же
отклоняются `StopBits.ONE_POINT_FIVE` на POSIX (termios умеет только
`CSTOPB` = 2 бита) и `Parity.SPACE` на Windows.

## Сборка

```bash
export JAVA_HOME=/path/to/jdk21          # kn-clang требует JVM 21
./gradlew ktlintFormat
./gradlew jvmTest linuxX64Test
./gradlew compileKotlinMingwX64 compileKotlinMacosArm64
```

Apple-таргеты собираются только на macOS-хосте — это ограничение
Kotlin/Native, а не библиотеки.

Интеграционные тесты открывают pty-пару, поэтому запускайте их с поднятым
`socat`:

```bash
socat pty,raw,echo=0,link=/tmp/ttyA pty,raw,echo=0,link=/tmp/ttyB &
./gradlew jvmTest -Dserial.integration=true
./gradlew linuxX64Test
```

Без pty-пары нативный тест пропускается сам.

## Релиз

`.github/workflows/release.yml` публикует в Maven Central по тегу `N.N.N`:

```bash
git tag 1.0.0
git push origin 1.0.0
```

Сначала Linux-джоба прогоняет тесты (JVM + `linuxX64Test`), затем джоба на
`macos-14` собирает все публикации — macOS плюс кросс-сборку под linux/mingw —
и загружает один подписанный деплой в Central. Локальная сборка никуда не
публикует, пока об этом не попросят (`publishToMavenLocal`).

## Лицензия

Apache License 2.0 — см. [LICENSE](./LICENSE).
