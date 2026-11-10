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

## 9. CI/CD: публикация в Nexus

Workflow — `.gitea/workflows/release.yml`. Триггер — **объект релиза** в Gitea
(`release: published`), не голый `git push --tags`.

### ⚠️ Обязательный флаг сборки

```properties
# gradle.properties
kotlin.mpp.enableCInteropCommonization=true
```

Без него `compileNativeMainKotlinMetadata` не видит символы cinterop и падает с
`Unresolved reference 'core'` на каждом импорте `pw.binom.serial.core.*`.
**Публикация при этом мертва целиком** — при живых тестах и живых таргетах.
Это была настоящая причина, по которой `publish` не проходил.

### Переменные репозитория (обязательно ДО релиза)

`BINOM_REPO_*` живут на уровне **репозитория**, не глобально. У нового репо их нет,
и публикация уходит анонимно → падает. Заводить:

```bash
AUTH="-u subochev:<PASS>"; BASE="https://git.binom.pw/api/v1"
for n in BINOM_REPO_URL BINOM_REPO_USER BINOM_REPO_PASSWORD; do ... done
# 201 создано, 409 уже есть (тогда PUT), 204 обновлено
```

Проверка: `GET /repos/subochev/serial-kmp/actions/variables` → три записи.

### Что публикуется, а что нет

| Таргет | С раннера (Linux x86_64) |
|---|---|
| `jvm` | да |
| `linuxX64` | да |
| `linuxArm64` | да (кросс) |
| `mingwX64` | да (кросс) |
| `macosX64` | **нет** — cinterop не кросс-компилируется в Apple |
| `macosArm64` | **нет**, по той же причине |

Проверено локально: `publishToMavenLocal -Pversion=7` даёт **5 артефактов**
(`serial-kmp`, `-jvm`, `-linuxx64`, `-linuxarm64`, `-mingwx64`). Apple-артефактов
в списке нет — это не поломка, а ограничение Kotlin/Native.

Apple-артефакты, если понадобятся, выпускаются отдельным прогоном на macOS-хосте.

### Порядок выпуска

```bash
git add -A && git commit -m "..." && git push origin main
# затем ОБЪЕКТ релиза (не тег):
curl -sL -u "subochev:<PASS>" -X POST \
  "https://git.binom.pw/api/v1/repos/subochev/serial-kmp/releases" \
  -H "Content-Type: application/json" \
  -d '{"tag_name":"1","target_commitish":"main","name":"1","body":"...","draft":false}'
```

Проверка факта в Nexus:

```bash
curl -s "http://192.168.76.117/service/rest/v1/search?repository=caffeine&group=pw.binom.serial" \
  | python3 -c "import sys,json;print([i['version'] for i in json.load(sys.stdin).get('items',[])])"
```

## 10. Мак-раннер Gitea (`macos-arm64`)

Установлен на `Mac-mini-Anton` (mac.xx, M1), чтобы собирать Apple-таргеты, которые
на Linux невозможны.

| Параметр | Значение |
|---|---|
| `act_runner` | v0.2.11, darwin-arm64 |
| Каталог | `~/runners/gitea-serial/` |
| Имя в Gitea | `mac-mini-serial` (id=6) |
| Лейбл | **`macos-arm64:host`** |
| Область | **user-level** — работает для ВСЕХ репозиториев пользователя |
| JAVA_HOME | Corretto 21 |

### ⚠️ Запуск: только `nohup`, launchd не работает

Мак **headless** — GUI-сессии нет (`stat -f "%Su" /dev/console` → `root`). Поэтому
`launchctl bootstrap user/501 <plist>` падает с `Bootstrap failed: 5: Input/output
error`, и простая загрузка plist'а тоже не поднимает демон. При этом
`launchctl print user/501` показывает службу как `enabled` — ложное ощущение успеха.

Рабочий способ:

```bash
cd ~/runners/gitea-serial
nohup ./act_runner daemon > /tmp/gitea-runner.log 2>&1 &
```

Проверка, что раннер поднялся:

```bash
curl -s -u subochev:<PASS> https://git.binom.pw/api/v1/user/actions/runners \
  | python3 -c "import sys,json;[print(r['name'],r['status']) for r in json.load(sys.stdin)['runners']]"
# ожидаем: mac-mini-serial online
```

⚠️ После перезагрузки мака раннер **не поднимется сам** — `nohup` не переживает
ребут. Нужен либо GUI-логин (тогда заработает launchd), либо ручной запуск.

### ⚠️ `~/.gradle/gradle.properties` перекрывает дефолт URL публикации

У пользователя в `~/.gradle/gradle.properties` лежит:

```
binom.repo.url=https://repo.binom.pw/
binom.repo.user=subochev
```

Это **перекрывает** дефолт в `build.gradle.kts` (`http://nexus.xx/repository/caffeine/`).
Публикация уходит на `repo.binom.pw` и падает с **401 Unauthorized**.

Проверка и обход:

```bash
./gradlew   -Pbinom.repo.url=http://nexus.xx/repository/caffeine/   -Pbinom.repo.user=admin -Pbinom.repo.password=admin123   publishMacosArm64PublicationToCaffeineRepository
```

В CI этой проблемы нет: composite-action передаёт `-Pbinom.repo.*` явно из
Action Variables.

### Что публикует мак-джоба

```yaml
- run: ./gradlew -Pversion=${{ gitea.ref_name }} \
    -Pbinom.repo.user="$BINOM_REPO_USER" \
    -Pbinom.repo.password="$BINOM_REPO_PASSWORD" \
    publishMacosX64PublicationToCaffeineRepository \
    publishMacosArm64PublicationToCaffeineRepository
```

Проверено живьём 31.10.2026: оба артефакта легли в Nexus (`HTTP 200` на POM),
затем тестовая версия удалена по `componentId`.
