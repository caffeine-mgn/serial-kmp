# Тестирование serial-kmp

## 1. Unit-тесты (общие)

`src/commonTest` — валидация `SerialConfig`: положительный baudRate, диапазон
dataBits 5..8, неотрицательные таймауты. Запускаются на каждом таргете:

```bash
./gradlew jvmTest linuxX64Test
```

## 2. Интеграционный тест через pty

Настоящее железо для CI не нужно: два псевдотерминала связываются в петлю,
и библиотека пишет в один конец, читая из другого.

### Подготовка петли

**Linux:**

```bash
socat pty,raw,echo=0,link=/tmp/ttyA pty,raw,echo=0,link=/tmp/ttyB
```

**macOS** (`socat` в базовой системе нет):

```bash
python3 -c "
import pty, os, time, select
m, s = pty.openpty()
open('/tmp/pty.txt','w').write(os.ttyname(s))
t = time.time() + 60
while time.time() < t:
    r, _, _ = select.select([m], [], [], 0.5)
    if m in r:
        d = os.read(m, 256)
        if d: os.write(m, d)
"
```

### JVM

```bash
./gradlew jvmTest -Dserial.integration=true
```

Тест `JvmSerialPortIntegrationTest` сам поднимает `socat` (Linux) и проверяет
петлю. На macOS используется `MacPtyManualTest`: ему нужен внешний pty,
путь к которому лежит в `/tmp/pty.txt`.

Ожидаемый результат: `pty roundtrip PASSED`, `MAC-C-PING` возвращается
байт в байт.

### Kotlin/Native (linuxX64)

Петля поднимается снаружи (см. выше), затем:

```bash
./gradlew linuxX64Test
```

`NativeSerialPortIntegrationTest.ptyRoundTrip` пишет в `/tmp/ttyA` и читает
из `/tmp/ttyB` — уже через своё C-ядро, без JVM.

## 3. Проверка на реальном железе

Чек-лист (нужен любой USB-UART адаптер — CH340, CP2102 или FTDI):

1. Воткнуть адаптер, убедиться, что узел появился:
   - Linux: `ls /dev/ttyUSB*` или `/dev/ttyACM*`
   - Windows: `Get-CimInstance Win32_SerialPort` или диспетчер устройств
   - macOS: `ls /dev/cu.*` (если пусто — не установлен драйвер, см. README)
2. `listSerialPorts()` должен вернуть этот порт.
3. Замкнуть TX и RX перемычкой (loopback).
4. Открыть порт, записать `"TEST"`, прочитать — должны вернуться те же байты.
5. Отдельно проверить `flush()` и повторное `openSerialPort` без перезагрузки.

## 4. Проверка нативной библиотеки в jar

Что все пять бинарников действительно упакованы:

```bash
./gradlew jvmProcessResources
find build/processedResources/jvm/main/native -name 'libserialjvm.*' | sort
# ожидается 5: linux-x64, linux-arm64, windows-x64, macos-x64, macos-arm64
```

Проверка форматов:

```bash
file build/jvmNative/*/libserialjvm.*
```

## 5. Полный прогон перед релизом

```bash
export JAVA_HOME=/path/to/jdk21
./gradlew ktlintFormat
./gradlew ktlintCheck detekt
./gradlew jvmTest -Dserial.integration=true
./gradlew linuxX64Test
./gradlew compileKotlinMingwX64 compileKotlinMacosArm64 compileKotlinMacosX64
```

Apple-таргеты — **только на macOS-хосте**. На Linux они компилироваться не
будут: cinterop не поддерживает кросс-компиляцию в Apple-семейство.

## 6. Известные ограничения тестов

- `mingwX64Test` требует Windows-хоста — на Linux только `compileKotlin`.
- `macosArm64Test` требует macOS-хоста.
- Тест `listSerialPorts` на CI без устройств возвращает пустой список — это
  норма, а не падение.

## 7. Проверено на реальном железе

Зафиксированные прогоны (не «должно работать», а «запущено»):

| Машина | Что проверено | Результат |
|---|---|---|
| Linux x86_64 | `jvm` + pty-петля | PASSED |
| Linux x86_64 | `linuxX64` натив + pty-петля | PASSED |
| macOS 14.4 arm64 (M1) | `jvm` + pty-петля | PASSED |
| **Raspberry Pi 4, Ubuntu 24.04 aarch64** | `jvm` arm64: энумерация, петля, таймаут, flush | PASSED |
| **Raspberry Pi 4** | `linuxArm64` натив: 7 тестов, pty-петля | PASSED |
| **Raspberry Pi 4** | **живой UART `/dev/ttyS0`**: запись в железо, `stty` подтвердил смену скорости 9600 → 19200 | PASSED |
| Windows | `mingwX64` + `.dll` + реестр | только компиляция, запуска не было |

## 8. ⚠️ Kotlin/Native не поддерживает Linux/aarch64 как ХОСТ

`HostManager.host` на Raspberry Pi бросает `IllegalStateException: Unknown host
target: linux aarch64`. ARM-Linux есть только среди *таргетов*, но не среди
*хостов*.

Следствия (проверено на Pi 4, Ubuntu 24.04, JDK 21):

1. `build.gradle.kts` содержит fallback на `System.getProperty("os.name"/"os.arch")` —
   конфигурация проекта на ARM-хосте не падает.
2. **Собрать на самой малине нельзя вообще.** Последовательность падений:

   | Команда на ARM-хосте | Результат |
   |---|---|
   | `./gradlew linuxArm64Test` | `Task 'linuxArm64Test' is ambiguous...` — таск `Test` не создан (ARM не хост) |
   | `./gradlew linuxArm64TestBinaries` | `Configuration with name 'kotlinNativeBundleConfiguration' not found` |
   | `./gradlew linkDebugTestLinuxArm64` | то же: плагин не может скачать K/N под ARM-хост |

   Kotlin/Native просто не поставляет дистрибутив для linux-aarch64 хоста.
3. **Рабочий путь — кросс-линковка с x86_64-хоста.** В `~/.konan/dependencies`
   лежит `aarch64-unknown-linux-gnu-gcc-8.3.0`, и линковка проходит:

   ```bash
   # на x86_64-хосте
   ./gradlew linkDebugTestLinuxArm64
   scp build/bin/linuxArm64/debugTest/test.kexe root@192.168.76.108:/tmp/test-arm.kexe
   # на малине
   chmod +x /tmp/test-arm.kexe && /tmp/test-arm.kexe
   ```

   На Pi прогон даёт `7 tests, PASSED` — включая pty-петлю через своё C.
4. На ARM-хосте кросс в mingw отключается автоматически (нет тулчейна).

## 9. CI/CD: GitHub Actions → Maven Central

Два workflow'а в `.github/workflows/`:

| Файл | Триггер | Что делает |
|---|---|---|
| `ci.yml` | push в `main`, pull request | `jvmTest -Dserial.integration=true` (pty через socat), `linuxX64Test`, `ktlintCheck` на `ubuntu-latest` |
| `release.yml` | тег `N.N.N` | гейт на ubuntu → публикация с `macos-latest` |

### ⚠️ Обязательный флаг сборки

```properties
# gradle.properties
kotlin.mpp.enableCInteropCommonization=true
```

Без него `compileNativeMainKotlinMetadata` не видит символы cinterop и падает с
`Unresolved reference 'core'` на каждом импорте `pw.binom.serial.core.*`.
**Публикация при этом мертва целиком** — при живых тестах и живых таргетах.

### Почему публикует macos-latest

Apple-таргеты (`macosX64`, `macosArm64`) собираются только на macOS: cinterop не
кросс-компилируется в Apple-семейство. macOS-раннер при этом умеет кросс-собрать
и все остальные таргеты, поэтому публикация — один Central-деплой из одной джобы:

| Таргет | С macos-latest |
|---|---|
| `jvm` | да |
| `linuxX64` | да (кросс) |
| `linuxArm64` | да (кросс) |
| `mingwX64` | да (кросс) |
| `macosX64` | да |
| `macosArm64` | да |

Кросс-сборка JVM-библиотеки под linux/mingw требует чужих `jni_md.h` — в
macOS-JDK есть только `darwin`. Workflow качает два заголовка из `jdk21u` в
`build/jni-include/{linux,win32}/`, откуда их берёт `build.gradle.kts`.
Перед публикацией отдельный шаг проверяет, что в `serial-jvm-*.jar` лежат все
пять нативных библиотек (`native/<os>-<arch>/libserialjvm.*`) — иначе потребитель
на этой ОС получит jar, падающий на `System.load`.

### Секреты репозитория

Задаются один раз (скрипт скилла `maven-central-publish`):

```bash
~/.config/opencode/skills/maven-central-publish/scripts/gh-secrets.sh caffeine-mgn/serial-kmp
```

`GPG_PRIVATE_KEY`, `GPG_KEY_ID`, `GPG_PASSWORD`, `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`.

### Порядок выпуска

```bash
git tag 1.0.0 && git push origin 1.0.0
```

Версия берётся из имени тега (`GITHUB_REF_NAME`). Если прогон сломался —
поправь workflow, **удали и пересоздай тег**, чтобы не запускать два релиза
параллельно:

```bash
git tag -d 1.0.0 && git push origin :refs/tags/1.0.0
git tag 1.0.0 && git push origin 1.0.0
```

### Проверка результата

```bash
source ~/.config/opencode/skills/maven-central-publish/scripts/creds.env
curl -s -u "$CENTRAL_USER:$CENTRAL_PASSWORD" \
  "https://central.sonatype.com/api/v1/publisher/deployments?pageSize=3" \
  | python3 -c "import json,sys;[print(x['deploymentName'],x['deploymentState']) for x in json.load(sys.stdin)['deployments']]"
# PUBLISHED (15-25 минут после успешной загрузки), затем:
curl -I https://repo1.maven.org/maven2/pw/binom/serial/1.0.0/serial-1.0.0.pom   # 200
```

### Локальная сборка для потребителей

`publishToMavenLocal` не требует ключей и публикует `pw.binom:serial:<версия>`
в `~/.m2` — этим пользуется, например, `proxy-bridge` при отладке:

```bash
./gradlew publishToMavenLocal -Pversion=1.0.0
```
