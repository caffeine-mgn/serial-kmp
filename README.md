# serial-kmp

> **Language**: English | [Русский](./README.ru.md)

Serial port (COM / tty) access for Kotlin Multiplatform. One C core, the same
code on every platform: no third-party Java serial libraries, no vendored
binaries, no LGPL dependencies.

## Installation

Published to **Maven Central** as `pw.binom:serial`.

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("pw.binom:serial:VERSION")
}
```

Target artifacts (`-jvm`, `-linuxx64`, `-linuxarm64`, `-macosx64`,
`-macosarm64`, `-mingwx64`) are resolved from the metadata artifact
automatically — **do not declare them by hand**.

`VERSION` — the current release; the list lives in the registry:

```bash
curl -s "https://repo1.maven.org/maven2/pw/binom/serial/maven-metadata.xml" \
  | grep -oE '<version>[^<]+</version>'
```

### How the native code is shipped

- **JVM**: the C core plus a JNI wrapper is compiled into
  `libserialjvm.{so,dylib,dll}` for all supported platforms and packed into the
  jar under `native/<os>-<arch>/`. At first use the library extracts the right
  one to a temp directory and loads it. Nothing extra to install.
- **Kotlin/Native**: the same C core is compiled to a static archive and linked
  into every klib. The consumer does not need a C compiler.

## Usage

```kotlin
import pw.binom.serial.*

// List available ports
val ports = listSerialPorts()
ports.forEach { println("${it.name} -> ${it.path}") }

// Open, write, read, close
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
    val read = port.read(buffer, 0, buffer.size)   // 0 on timeout, -1 never
    if (read > 0) println(buffer.decodeToString(0, read))
}
```

`SerialPort.read` returns the number of bytes read, `0` on timeout. Errors are
thrown as `SerialException`: `OpenException`, `ClosedException`, `IoException`,
`UnsupportedConfigException`, `SerialWriteTimeoutException`.

## Platforms

| Platform | Port names | Notes |
|---|---|---|
| Linux | `/dev/ttyUSB*`, `/dev/ttyACM*`, `/dev/ttyS*`, `/dev/rfcomm*` | enumeration also reads `/dev/serial/by-id` |
| Windows | `COM1` … `COM256` | enumeration via the `SERIALCOMM` registry key |
| macOS | `/dev/cu.*` | enumeration via IOKit `IOSerialBSDClient` |
| Android | — | not supported: no access to `/dev/tty*` from a regular app |
| iOS | — | not supported: sandbox |

**Drivers are the OS's job.** The library works only with system device nodes.
USB adapters (CP210x, CH34x, FTDI) show up as `/dev/ttyUSB0` / `COM3` once the
kernel or the vendor driver has claimed them:

- **Linux** — `ch341`, `cp210x`, `ftdi_sio`, `cdc_acm` are in the stock kernel.
- **Windows** — `usbser.sys` for CDC-ACM, vendor VCP drivers for the rest.
- **macOS** — only CDC-ACM is built in (`AppleUSBCDC.kext`). For FTDI / CP210x /
  CH34x the user installs the vendor driver; without it no `/dev/cu.*` node
  appears at all.

### Baud rates

| Platform | Supported |
|---|---|
| Linux | 110 … 4 000 000 (whatever `termios.h` declares) |
| Windows | any value passed to `DCB.BaudRate` |
| macOS | **up to 230 400** — `termios.h` has no constants above `B230400` |

An unsupported rate throws `UnsupportedConfigException`. Likewise
`StopBits.ONE_POINT_FIVE` is rejected on POSIX (termios only expresses
`CSTOPB` = 2 bits) and `Parity.SPACE` is rejected on Windows.

## Building

```bash
export JAVA_HOME=/path/to/jdk21          # kn-clang requires JVM 21
./gradlew ktlintFormat
./gradlew jvmTest linuxX64Test
./gradlew compileKotlinMingwX64 compileKotlinMacosArm64
```

Apple targets can only be compiled on a macOS host — a Kotlin/Native
limitation, not a library one.

The integration tests open a pty pair, so run them with `socat` up:

```bash
socat pty,raw,echo=0,link=/tmp/ttyA pty,raw,echo=0,link=/tmp/ttyB &
./gradlew jvmTest -Dserial.integration=true
./gradlew linuxX64Test
```

Without the pty pair the native test skips itself.

## Releasing

`.github/workflows/release.yml` publishes to Maven Central on any `N.N.N` tag:

```bash
git tag 1.0.0
git push origin 1.0.0
```

A Linux job runs the tests first (JVM + `linuxX64Test`); on success the
`macos-14` job builds every publication — macOS plus cross-compiled
linux/mingw — and uploads a single signed Central deployment. Local builds
never publish anywhere unless you ask (`publishToMavenLocal`).

## License

Apache License 2.0 — see [LICENSE](./LICENSE).
