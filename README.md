# Nebula VPN

Android-клиент для прокси-протоколов **VMess / VLESS / Trojan / Shadowsocks**, написанный на **Kotlin + Jetpack Compose**. Принимает на вход v2ray-подписку (ссылку со списком конфигов), парсит её, показывает список серверов с пингом и поднимает системный VPN-туннель через `VpnService`.

По архитектуре и поведению это аналог [v2rayNG](https://github.com/2dust/v2rayNG), но с нуля, компактнее и под одну конкретную задачу.

---

## Что работает

Нативное ядро **Xray уже подключено** — туннель реально гонит трафик через выбранный сервер. Используется `libv2ray.aar` (AndroidLibXrayLite v26.6.2): современный API ядра принимает дескриптор `tun` напрямую (`startLoop(config, tunFd)`) и сам обслуживает устройство через `tun`-inbound, поэтому отдельный tun2socks не нужен. Сокеты самого ядра не зацикливаются в туннель, потому что приложение исключает свой UID из маршрута (`addDisallowedApplication`).

Работает после установки APK:

- загрузка и парсинг подписки (VMess / VLESS / Trojan / Shadowsocks);
- весь UI: список серверов, поиск, выбор, TCP-пинг, статусы подключения;
- генерация валидного Xray-JSON-конфига (`tun` + `socks` inbound) под каждый сервер;
- поднятие `tun`, запуск ядра и **реальное проксирование трафика**.

Ядро (`libv2ray.aar`, ~56 МБ) **не лежит в репозитории** — оно скачивается на этапе сборки (CI-workflow тянет его из релизов AndroidLibXrayLite; для локальной сборки положи сам — см. «Нативное ядро»).

---

## Архитектура

```
app/src/main/java/com/nebula/vpn/
├── MainActivity.kt              # Compose UI: список, поиск, кнопка Connect, пинг
├── ui/
│   └── MainViewModel.kt         # состояние экрана (StateFlow), загрузка/пинг/выбор
├── proxy/
│   ├── ServerConfig.kt          # модель сервера + парсер URI (vmess/vless/trojan/ss)
│   ├── XrayConfigBuilder.kt     # ServerConfig → полный Xray JSON
│   ├── SubscriptionRepository.kt# загрузка подписки, кэш, tcpPing()
│   ├── VpnManager.kt            # глобальное состояние VPN (singleton, StateFlow)
│   └── V2RayVpnService.kt       # VpnService: строит tun, foreground-уведомление
└── core/
    ├── V2RayCore.kt             # интерфейс ядра + CoreController + StubCore (фолбэк)
    └── XrayCore.kt              # РЕАЛЬНАЯ реализация на libv2ray (Xray-core)
```

Ключевая идея — **подключаемое ядро**: `CoreController.factory` отдаёт реализацию `V2RayCore`. В `MainActivity.onCreate` фабрика переключена на `XrayCore` — настоящее ядро Xray. `StubCore` остаётся как безопасный фолбэк (`isAvailable = false`): если `.aar` по какой-то причине не подключён, приложение не «роняет» интернет чёрной дырой, а честно сообщает об этом.

### Поддерживаемые протоколы

| Протокол | Парсинг | Транспорты |
|----------|---------|------------|
| VMess (`vmess://`) | base64-JSON | tcp / ws / grpc / h2 / kcp, tls/none |
| VLESS (`vless://`) | URI + query | tcp / ws / grpc, tls/reality/none |
| Trojan (`trojan://`) | URI + query | tcp / ws / grpc, tls |
| Shadowsocks (`ss://`) | SIP002 base64 | tcp |
| ~~SSR (`ssr://`)~~ | — | не поддерживается (Xray не умеет проксировать SSR) |

---

## Как получить APK

### Вариант А — собрать в облаке (без локального тулчейна)
В репозитории лежит workflow `.github/workflows/build-apk.yml`:

1. Запушить в репозиторий на GitHub.
2. Workflow запустится сам на push (или вкладка **Actions → Build APK → Run workflow**).
3. Раннер сам скачивает `libv2ray.aar` из релизов AndroidLibXrayLite и собирает APK с Google Maven.
4. Скачать `NebulaVPN-debug-apk` из артефактов завершённого запуска → внутри `app-debug.apk`.
5. Закинуть на телефон, разрешить установку из неизвестных источников, поставить.

### Вариант Б — собрать локально
```bash
# нужен установленный Android SDK (через Android Studio или cmdline-tools) и JDK 17

# 1) положить нативное ядро (один раз):
mkdir -p app/libs
curl -fL -o app/libs/libv2ray.aar \
  https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.6.2/libv2ray.aar

# 2) собрать:
./gradlew assembleDebug
# результат: app/build/outputs/apk/debug/app-debug.apk
```
Или открыть в Android Studio: **Build → Build APK(s)** (предварительно положив `app/libs/libv2ray.aar`).

> Готовый `app-debug.apk` ставится и **реально проксирует трафик** через выбранный сервер. APK ~90 МБ: внутри нативное ядро (`libgojni.so` для arm64-v8a + armeabi-v7a) и базы `geoip.dat`/`geosite.dat`.

---

## Сборка из исходников

1. **Android Studio** (Ladybug / 2024.2 или новее).
2. `File → Open` → выбрать папку `NebulaVPN`.
3. Дождаться Gradle sync. При первом синке Android Studio сам дотянет Gradle 8.9 и сгенерирует `gradle-wrapper.jar` (поэтому бинарника wrapper'а в репозитории нет).
4. `Run` на устройстве/эмуляторе с **Android 7.0 (API 24)** и выше.
5. При первом подключении система спросит разрешение на VPN — это штатный диалог `VpnService.prepare()`.

Параметры: `compileSdk 35`, `minSdk 24`, `targetSdk 35`, package `com.nebula.vpn`. Сторонних HTTP/JSON-библиотек нет — только `HttpURLConnection` + `org.json`, чтобы APK оставался маленьким.

Подписка по умолчанию уже зашита (`SubscriptionRepository.DEFAULT_SUBSCRIPTION`) — та самая ссылка из задачи. В UI её можно поменять и обновить список.

---

## Нативное ядро (Xray) — как это работает

Туннель уже работает; раздел для тех, кто хочет понять связку.

- **Библиотека:** `libv2ray.aar` (AndroidLibXrayLite v26.6.2) — Xray-core, собранный под Android через gomobile. В репозиторий не коммитится (~56 МБ), а скачивается из релизов: путь `app/libs/libv2ray.aar`, подключение — `implementation(files("libs/libv2ray.aar"))` в `app/build.gradle.kts`.
- **Запуск:** `core/XrayCore.kt` инициализирует окружение (`Libv2ray.initCoreEnv`, `Seq.setContext` — чтобы ядро читало `geoip.dat`/`geosite.dat` из ассетов), создаёт `CoreController` и зовёт `controller.startLoop(configJson, tunFd)`.
- **`tun` без tun2socks:** в этой версии ядро само обслуживает устройство. `V2RayVpnService` строит `tun` и передаёт его fd в `startLoop`; ядро читает fd из env-переменной `xray.tun.fd` через `tun`-inbound в конфиге (`XrayConfigBuilder` добавляет его). Поэтому badvpn/hev-socks5-tunnel не нужен.
- **Без петли:** сокеты ядра не уходят обратно в `tun`, потому что `buildTun()` исключает UID приложения из маршрута через `addDisallowedApplication(packageName)` — это заменяет пер-сокетный `protect()`.
- **ABI:** в `app/build.gradle.kts` через `ndk.abiFilters` оставлены `arm64-v8a` и `armeabi-v7a`. Чтобы собрать под эмулятор x86 — допиши нужный ABI.

Фабрика ядра переключается в `MainActivity.onCreate`:
```kotlin
CoreController.factory = { com.nebula.vpn.core.XrayCore() }
```

---

## Проверка парсера

Логика парсинга проверена на **реальной подписке из задачи** (4004 строки): корректно разобрано **3958 серверов** без единой ошибки —

- VMess — 1757
- Shadowsocks — 1478
- Trojan — 668
- VLESS — 55
- (SSR — 40 пропущено осознанно)

Поля (id/host/path/sni/method/password и т.д.) извлекаются верно, парсер устойчив к emoji и пробелам в remark'ах и к IPv6-адресам.

---

## Дисклеймер

Инструмент предназначен для обхода сетевых ограничений и приватности — это легально. Подписка по умолчанию — публичный список бесплатных нод из стороннего GitHub-репозитория; их доступность, скорость и доверенность никак не гарантируются. Для постоянного использования заведи свой сервер/подписку.
