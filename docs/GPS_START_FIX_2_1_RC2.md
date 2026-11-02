# Исправление «Начать пробежку» — 2.1-rc2

2026-11-02. Версия 5 / 2.1-rc2. APK до изменений сохранён для сравнения подписи; приложение и история не удалялись. Схема Room v2, имена базы/DataStore и applicationId не менялись.

## Что доказано исходниками, а что неизвестно

В исходной rc1 кнопка имела working onClick и enabled=true. Через ActivityResult запрашивались FINE/COARSE/POST_NOTIFICATIONS, затем напрямую запускался RunService, минуя состояние запуска ViewModel. Отдельного маршрута навигации «active run» нет: RunningScreen должен переходить в active представление по Room runs Flow.

**Доказанный блокирующий дефект:** в RunService до RunTimers.resume и repo.saveRun выполнялась проверка GoogleApiAvailability != SUCCESS с немедленным stopSelf/return. На устройстве без доступного GMS запуск гарантированно не создавал активную пробежку, хотя системный GPS мог быть исправен. Аналогично выключенная геолокация прекращала старт до записи. Вместо ошибки рядом с кнопкой менялась лишь строка signal в верхней карточке. Кнопка оставалась прежней, busy/ошибки/handshake не было. Повторные нажатия не блокировались в UI.

Отдельный дефект представления времени: RunningScreen вызывал clockNow, но не использовал результат для расчёта показателей. Теперь монотонная метка явно пересчитывается через remember(tick); зависимости обновления таймера однозначны.

**Триггер именно на телефоне пользователя не подтверждён:** модель/версия/GMS/logcat неизвестны. ADB server не может открыть socket в рабочей среде. Нельзя утверждать, что на этом конкретном устройстве отсутствует GMS или что новый APK уже проверен нажатием. Исправлены доказанные ранние отказы и отсутствие обратной связи; для подтверждения конкретного runtime-триггера нужен журнал FitRunStart.

## Исправленная цепочка

1. RunningScreen → ViewModel.requestRunStart → RunLaunchController, pending атомарно блокирует повторное нажатие.
2. При отсутствии точного доступа — системный запрос FINE+COARSE. Approximate/denied дают конкретную ошибку и переход в разрешения приложения. POST_NOTIFICATIONS не является условием старта GPS FGS.
3. Проверяется LocationManager.isLocationEnabled. Выключенная геолокация даёт LOCATION_DISABLED возле кнопки, настройки и retry; без запуска пустой тренировки.
4. ContextCompat.startForegroundService; ошибки dispatch логируются и отображаются. Сервис создаёт канал run в onCreate и немедленно вызывает startForeground до await Room/permission/provider работ.
5. Сервис восстанавливает готовность repository, находит единственную unfinished пробежку или создаёт новую, запускает монотонные часы и сохраняет active запись **до первой координаты**.
6. При доступном GMS используется Fused Location Provider. Если GMS недоступен, подписка на настоящий Android LocationManager.GPS_PROVIDER. В обоих случаях единый GPS filter, timestamps, сохранение и foreground lifecycle; mock/демо не становятся реальными данными. Если GPS provider отсутствует — понятная ошибка, начатая запись сохраняется на паузе.
7. Flow Room меняет RunningScreen на «Пробежка идёт», показывает паузу/сохранение/карту; RunLaunch ACTIVE подтверждает сервис, signal сообщает поиск координат. Секундомер обновляется независимо от первого fix, дистанция остаётся нулевой до измерений.
8. Async регистрация GPS, SecurityException, FGS runtime отказ, storage failure и timeout дают ERROR/retry. Тайм-аут старой попытки не ломает новую. Ошибка подписки переводит начатую запись в паузу, не стирая её.

Manifest проверен: FINE, COARSE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION, POST_NOTIFICATIONS, WAKE_LOCK; RunService exported=false, foregroundServiceType=location. min29/target35. BACKGROUND_LOCATION не добавлялся: пользователь запускает FGS из видимой Activity. Обязательной регистрации/платных сервисов нет.

## Файлы

- `MainActivity.kt`: handshake/error/retry/permission UI и секундный tick.
- `FitViewModel.kt`: оркестрация запроса permission, проверки настроек, запуска и timeout.
- `RunLaunch.kt`: детерминированная state machine и коды ошибок.
- `RunService.kt`: ContextCompat, platform GPS fallback, подтверждение старта, обработка ошибок, FitRunStart logs без координат.
- `RunLaunchTest.kt`: 12 unit regressions; `RunStartUiTest.kt`: 2 Compose feedback tests; `GpsServiceStartTest.kt`: opt-in real FGS test.
- Gradle versionCode5/2.1-rc2, JVM test runner и документация. DB/migration-код в этом исправлении не менялся.

## Фактически выполнено

- **122 JVM-теста пройдены** (110 прежних + 12 новых).
- Компиляция Kotlin2.1.20/Compose/SDK35, регенерация официального Room2.7.1 и javac: выполнены.
- **23 Android-теста скомпилированы и упакованы, не исполнены**; 1 GPS service test по умолчанию пропускается и требует `-e gpsService true`. Unit success не является выполненным Android service test.
- Новый основной APK собран SDK fallback (cached dependency intermediates); apksigner/zipalign/source fingerprints/DEX/schema проверяются в `docs/verification/v2.1-rc2/apk.json`. Подпись сравнивается с rc1, versionCode растёт 4→5.
- `./gradlew assembleDebug` фактически остановился до сборки на read-only wrapper lock. Стандартная Gradle-сборка и Lint не подтверждены.
- `adb devices` фактически завершился ошибкой listener Operation not permitted. Установка/нажатие/logcat/lock screen/реальные provider ветки не выполнены.

## Проверка на телефоне

Сначала экспортировать JSON и завершить текущую запись. Установить новый APK поверх rc1 той же подписью (`adb install -r`), не удалять приложение/данные. В обычном режиме запустить `adb logcat -v threadtime -s FitRunStart AndroidRuntime`, нажать старт и разрешить точную геолокацию. Ожидаются UI start → permission result (если нужен) → dispatch → service lifecycle → backend → active. Проверить растущий секундомер на месте при отсутствии fix; затем реальный маршрут и 15–30 минут lock screen/offline. Отдельно — отказ/approximate, выключенная геолокация, повторные нажатия, пауза/продолжение/сохранение. Инструментальные команды и изоляция истории описаны в TESTING.md.
