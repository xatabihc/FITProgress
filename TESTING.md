# Проверки FitProgressAI 3.0-dev

## GPS маршрутизация — новый прогон 2026-11-02

- `./gradlew --no-parallel testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, 140 tests, 0 failures/errors; Lint 0 errors, 38 warnings + 1 informational; `assembleDebug` и `assembleDebugAndroidTest` успешны. Обычный parallel запуск однажды наткнулся на временный KAPT stub race в lint; последовательный повтор прошёл.
- `./tools/run-domain-tests.sh`: **140 tests passed**.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 111,767,106 bytes, SHA-256 `876adf2a3a116b0b598543c7e19e91eef5bbc7d78213597db466522e6b976f66`; applicationId `ru.fitprogress.ai`, versionCode 6/versionName 3.0-dev. `apksigner verify` PASS, signing cert SHA-256 `2a8cc13cdcea8733f01443195c7269f064da980c4726a9dee5d72061fc03fcef` matches prior recorded debug artifact. `aapt dump badging` PASS; 16 KB `zipalign -c -P 16 -v 4` PASS.
- Android test APK compiles; 25 instrumentation tests including the new Room favorite-route preservation test. **They were not run on a device.** ADB reports `could not install *smartsocket* listener: Operation not permitted`; installation, actual data upgrade, MapLibre taps, live routing service and lock-screen GPS were not testable here.
- JVM routing tests use recorded coordinates and a fake Valhalla transport. They prove request serialization, parser, GPX/XXE and route geometry behavior; they do not prove a live server response or cartographic quality.

### Условия сторонних серверов

`tile.openstreetmap.org` requires visible OpenStreetMap attribution, a unique application User-Agent, caching according to server headers, and prohibits tile bulk download/offline prefetch. The FOSSGIS-hosted Valhalla demo is documented as fair-use/rate-limited without SLA; Valhalla asks public applications to notify maintainers through GitHub Discussions and send identifying `X-Client-Id`. Current app adds that header and rate-spaces user-initiated requests; before public rollout, notify maintainers or use a managed/self-hosted endpoint. References: [OSMF tile policy](https://operations.osmfoundation.org/policies/tiles/), [Valhalla user introduction](https://valhalla.github.io/valhalla/start/introduction/), [Valhalla route API](https://valhalla.github.io/valhalla/api/route/api-reference/).

## Каталог и импорт программ — 2026-11-02 (предыдущий прогон)

- `python3 tools/validate_exercise_asset.py`: 876 уникальных ID, 871 пригодная запись, 0 точных нормализованных дублей исходного названия; 77 записей без equipment помечаются как неизвестные, 5 неполных исключены.
- Тогда: 134 unit tests, 0 failures; Lint 0 errors / 36 warnings; debug APK собран. Текущие результаты маршрутизационного прогона выше.
- Тогда APK SHA-256 был `6d8b5d96b7b48308466ca502c4171b3ff0656fbbcef6886f37b974c7d8cbf312`; текущий APK и сертификат указаны выше.
- `ProgramInterchangeTest`: JSON schema v1 и расписание; неизвестная версия/недопустимые параметры; CSV quotes/grouping; malformed CSV и дубли дней. `ExerciseSearchTest`: категория/equipment, RU+EN, частичный поиск, тип и происхождение записи.
- CSV принимает обязательные колонки `week,weekday,session,exercise,sets,reps`; опциональные `weight_kg,seconds,rest_seconds`. JSON описан в README. До сохранения неизвестные упражнения нужно сопоставить вручную.
- Этот прогон не заменяет Compose UI и Android instrumentation. Проверки SAF импорта на устройстве, маленького экрана/клавиатуры и Room upgrade на телефоне пока не проведены.

- `./tools/run-domain-tests.sh`: **140 тестов реально пройдены**; результат совпал со стандартным Gradle.
- `python3 tools/validate_exercise_asset.py`: **PASS**, 876 уникальных записей с pin/licence, 871 имеют required core fields, пять исключаются.
- `python3 tools/offline-android-build.py --android-tests --package`: Kotlin/Compose compilation и Room Processor/javac PASS; Android test compilation PASS. 24 Android tests compiled, **not executed**. D8/AAPT2/zipalign/apksigner build PASS.
- Стандартный Gradle запуск успешно завершил assemble, unit tests и lint. `./gradlew assembleDebugAndroidTest` собрал instrumentation APK/25 тестовых кейсов; это компиляция, не исполнение.
- ADB server не смог открыть `tcp:5037` listener (`Operation not permitted`). Не установлено, не нажимали экран, не забирали logcat.

Каталог тесты: русское/английское частичное имя, мышцы, yo folding, combined filters; Android source test проверяет asset, уникальность, маркеры, импорт и реальный search. Последний test скомпилирован, device not run. Статический `verify-apk`/fingerprint не является UI/runtime test.

## APK и ручная проверка

Основной APK: `app/build/outputs/apk/debug/app-debug.apk`; test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. Для следующего прогона на разблокированном тестовом устройстве выполнить Gradle Android build, обновить через `adb install -r` той же подписью и только затем `./gradlew connectedDebugAndroidTest`; инструментальный runner использует изолированные Room/DataStore. Не удалять данные приложения. Проверить 320dp/увеличенный font, поиск на RU/EN, GPS при экране locked, геолокация без GMS, map offline fallback, импорт/перезапуск.

Открытый справочник: [free-exercise-db](https://github.com/yuhonas/free-exercise-db), [Unlicense](https://github.com/yuhonas/free-exercise-db/blob/main/LICENSE.md). See `app/src/main/assets/EXERCISE_DATA_LICENSE.txt`.

---

Исторические протоколы 2.1-dev/rc1/rc2:

# GPS start regression — 2.1-rc2

**Реально выполнены 122 JVM-теста**: прежние 110 и 12 RunLaunchTest. Проверяются success без первой точки (таймер растёт, distance=0), запрос/отказ permission, disabled location, SecurityException/IllegalStateException, async subscription error, двойное нажатие, retry/timeout и старый timeout новой попытки. Это тест логики, не запуск Android-сервиса.

**23 Android-теста скомпилированы, но не исполнены**: прежние20, 2 новых RunStartUiTest (ошибка+retry и disabled pending button) и GpsServiceStartTest (opt-in реального сервиса). По умолчанию GPS-тест пропускается через assumption. Диагностика/логи: `docs/verification/v2.1-rc2/`.

Запуск обычных тестов на тестовом телефоне описан ниже; скрипт `tools/run-phone-tests.py --serial SERIAL --run` проверяет безопасный FitTestRunner и не выполняет uninstall/pm clear. Сначала завершить текущую production тренировку, экспортировать историю: instrumentation останавливает процесс, даже при отдельном тестовом хранилище.

Для отдельного **реального GPS service test** на тестовом устройстве после установки обоих APK и включения геолокации:

```bash
adb shell pm grant ru.fitprogress.ai android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant ru.fitprogress.ai android.permission.ACCESS_FINE_LOCATION
# Только Android33+:
adb shell pm grant ru.fitprogress.ai android.permission.POST_NOTIFICATIONS
adb shell am instrument -w -e gpsService true -e class ru.fitprogress.ai.GpsServiceStartTest ru.fitprogress.ai.test/ru.fitprogress.ai.FitTestRunner
```

Тест запускает видимую Activity, настоящий FGS, проверяет active/рост секундомера/FGS notification, завершает тестовую пробежку в isolated DB. Не требует искусственной первой координаты. Физическую точность, дальность, lock screen и fallback без GMS нужно проверять отдельно реальной прогулкой. Не запускать во время production записи.

Диагностика пользовательского нажатия (без instrumentation, в обычной базе):

```bash
adb logcat -v threadtime -s FitRunStart AndroidRuntime
```

Нажать «Начать пробежку» и сохранить журнал. Ожидается UI start → Permission result (если нужен) → Dispatch service → onCreate/onStartCommand → Location backend=fused/android-gps → Run active. Если ошибка — она видна возле кнопки с кодом. Включённая геолокация и точное разрешение обязательны; интернет и первая GPS-точка для секундомера не нужны. Отказ POST_NOTIFICATIONS не блокирует FGS, но может скрыть уведомление из шторки.

Не проверены здесь: фактический старт/запросы разрешений на телефоне, реальный GMS fallback, lock screen, OEM/Doze, визуальное поведение UI. ADB запрещён средой, logcat получить не удалось. Стандартный Gradle/Lint не выполнен, APK собирается SDK fallback.

---

Предыдущий этап (историческая матрица):

# Проверки стабилизации 2.1-rc1

Реально выполнены: **110 JVM/JUnit-тестов**, компиляция основного Android/Compose кода и нового Room-кода, компиляция **20 Android-тестов**. Исполнение Room/ViewModel/Compose на Android **не выполнено**. Логи текущего этапа: `docs/verification/v2.1-rc1/`.

Новые JVM-регрессии: завершённую пробежку нельзя возобновить; повторный finish сохраняет историю; удаление timed упражнения отменяет deadline; перестановка сохраняет валидный timer; импорт очищает монотонный anchor завершённого занятия; очередь действий сохраняет порядок и переживает ошибку/отмену.

Новые Android-тесты: настоящий ViewModel/main dispatcher + in-memory Room проверяют complete→finish, восстановление paused результата и отдыха в новом ViewModel, ошибку JSON без стирания истории и блокировку двойного exclusive. Repository проверяет неизменяемость завершённого бега. Существующий тест Room migration открывает базу v1 через реальный Room v2; до запуска на Android его результат неизвестен.

## Прогон на смартфоне

Завершите текущую тренировку и экспортируйте JSON. Instrumentation останавливает процесс, хотя тесты используют отдельные `fitprogress-instrumentation.db`/`profile-instrumentation` и не очищают production данные. Используйте тестовый профиль/устройство.

Предпочтительный путь:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb devices
python3 tools/run-phone-tests.py --serial SERIAL
python3 tools/run-phone-tests.py --serial SERIAL --run
```

Скрипт сначала проверяет подписи и безопасный FitTestRunner, использует только install -r (без uninstall/pm clear), на Android33+ разрешает уведомления для UI-прогона. Проверяет итоговый JUnit-результат. Без --run только проверяет APK. Для этой ограниченной среды доступна дополнительная упаковка тестов из прежних Gradle intermediates:

```bash
./tools/run-domain-tests.sh
python3 tools/offline-android-build.py --android-tests --package
python3 tools/package-android-tests.py
```

Основной APK: `app/build/outputs/apk/debug/app-debug.apk`.
Тестовый APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
Старый APK в `obsolete-before-2.1` не запускать. SDK fallback не заменяет стандартный Gradle/Lint.

## Оставшиеся обязательные ручные проверки

- Обновление 2.0→rc1 той же подписью без удаления приложения: прежние упражнения, подходы, история, маршруты, профиль; сравнить экспорт до/после. SQL на 10 000 строк и подпись APK не доказывают успешное обновление телефона.
- GPS: точное разрешение и включённая геолокация/GMS, реальная прогулка/бег 15–30 минут с выключенным экраном и интернетом; постоянное уведомление, маршрут/дистанция/время, pause→move→resume без соединения сегментов. Отдельно проверить отказ/отзыв разрешения, provider off, stationary drift. Демо/mock не считать реальным GNSS.
- Незавершённая тренировка: подход+отдых, background, recreate/rotation, удаление процесса, relaunch, reboot; результат сохранён, после reboot время выключенного телефона не засчитано.
- Экран 320dp/большой системный шрифт/клавиатура: диалоги параметров/подхода/завершения/ручного бега прокручиваются; действие сохранения доступно; длинные названия и TalkBack.
- Foreground service/уведомления/Doze/звук/вибрация и энергосбережение OEM. Все эти Android runtime-сценарии здесь **не проверены**.

---

Предыдущая матрица 2.1-dev (историческая):

# Проверки FitProgressAI 2.1-dev

## GPS маршрутизация — новый прогон 2026-11-02

Добавлены `NavigationTest`: фактическая haversine distance/projection и off-route threshold, route validation, GPX export/import roundtrip, отклонение некорректных координат и XXE, Valhalla costing/body/instructions parse и типизированный offline failure, Backup v4 saved route. Unit-тесты используют фиксированные точки/фиктивный Valhalla transport, но не рисуют/не сохраняют синтетические значения в пользовательские записи.

Целевой повторяемый прогон:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./tools/run-domain-tests.sh
```

Тесты JVM и Android instrumentation не доказывают доступность внешнего Valhalla, правильное поведение MapLibre на конкретном телефоне, GNSS качество, lock screen/Doze, SAF или Room upgrade установленной версии. Здесь ADB server не поднимается (`could not install *smartsocket* listener: Operation not permitted`), поэтому телефонные сценарии непроверены. См. итоговые логи/цифры ниже после сборки.

### Ручная проверка маршрутизатора и карты

1. Убедитесь, что карта видит attribution `© OpenStreetMap contributors`; не запускайте tile prefetch и не обещайте офлайн карту со стандартным OSM tile endpoint.
2. Сеть включена: выбрать «Моё местоположение», finish касанием/координатой и режим. Проверить реальную форму дороги, а не прямой сегмент, длину, русские подсказки; выбрать точку через waypoint и круг 1–50 км.
3. Сохранить маршрут, закрыть приложение, снова открыть, повторить сохранённый маршрут, экспортировать GPX; импортировать его через системный документ picker. Для GPS Run Detail проверить export реального трека. Демонстрационные точки не экспортировать.
4. Запустить запись, свернуть/заблокировать телефон, выключить сеть: FGS секундомер и GPS путь продолжаются; маршрутный backend не нужен. Отклониться на открытом месте: увидеть предупреждение, нажать ручное перестроение. Закончить пробежку, проверить трек и данные после перезапуска.
5. Проверить backend 429/5xx/timeout/no-route и геолокацию denied/provider off: причина должна быть видна, а запись и сохранённая геометрия не должны исчезать.

FOSSGIS Valhalla public demo просит пользователей мобильных/публичных приложений сообщить о проекте через GitHub Discussions, добавить `X-Client-Id`, использовать умеренно и учитывать fair-use/rate limit; endpoint не имеет SLA. Перед распространением app нужна такая договорённость либо собственный Valhalla сервер. Стандартный OSM tile policy требует видимый attribution, стабильный app User-Agent, кэширование по заголовкам и запрещает bulk/offline-prefetch. Ссылки приведены в README.

## Фактическая матрица

| Проверка | Результат |
|---|---|
| Исходные 58 JVM-тестов 2.0 | Повторно выполнены, пройдены |
| Итоговые 102 JUnit-теста | Выполнены, все пройдены |
| Точный SQL миграции 1→2 на 10 000 payload | Выполнен через SQLite JDBC; старые значения побайтно сохранены |
| Kotlin/Compose / SDK 35 | Полная компиляция supplemental offline-инструментом |
| Room code generation | Официальный RoomProcessor 2.7.1 + local KAPT; Java/schema v2 заново сгенерированы и скомпилированы |
| 10 Room/repository/settings и 5 Compose UI-тестов | Компилируются; **не выполнялись** |
| D8/AAPT2/zipalign/apksigner | Выполнены для нового APK |
| `./gradlew assembleDebug`, `testDebugUnitTest`, `lintDebug` | Остановлены wrapper lock до задач; **не пройдены** |
| ADB/установка/обновление/launch | ADB socket запрещён; **не проверено** |
| Физический GPS/lock screen/Doze/батарея | **Не проверено** |
| Реальные small screen/rotation/large font/TalkBack | **Не проверено** |

Логи — `docs/verification/v2.1/`. Прежние `v2/` относятся к 2.0-dev. В `apk.json` — точный SHA-256, версия, подпись baseline, DEX/native/source verification. Ни compilation Android-тестов, ни SQL-фикстура не являются выполненным обновлением APK на устройстве.

## Повторение в этой среде

```bash
./tools/run-domain-tests.sh
python3 tools/offline-android-build.py --android-tests --package
python3 tools/verify-apk.py --baseline /путь/к/сохранённому-2.0.apk
```

JVM-скрипт использует локальные Kotlin/JUnit/Gson/SQLite JDBC/SLF4J. DomainTest — 58, PersonalTest — 42, MigrationSqlTest — 2. Данные тестовые, в UI не подставляются.

Offline build требует прежние Gradle dependency intermediates, SDK 35, cached RoomProcessor и локальный KAPT. Компилирует новый код и генерирует новый DAO/schema, не переносит старый код приложения. Сохраняет source/schema fingerprints. Не запускает Lint/Gradle tasks/Android tests и не проверяет clean dependency resolution. При изменении ресурсов/зависимостей выполнить обычную сборку.

## Что проверяется JVM-набором

- Все прежние упражнения/подходы/таймеры/расписания/backup/GPS regression cases.
- Шесть целей, источник GPS/manual, реальные даты, срок/пустое состояние, минимальные повторы, исключение разминки, программы и регулярность.
- Валидация дневника, duplicate dates/IDs, backup roundtrip, чтение настоящего старого v2 без новых полей.
- Шесть вариантов Coach; diary requirement, боль сегодня, усталость/сон/энергия, пауза предыдущей недели, скачок силовой/беговой нагрузки, нейтральность, округление, сохранение разминки, actual-vs-planned date, разные веса повторяющегося упражнения, хронология recovery.
- Сравнение аналитики, измеренный вес vs e1RM, разделение GPS/manual, исключение демо, отсутствие выдуманного самочувствия.
- Напоминания: час, opt-out, пустой план и suppression повторов.
- GPS: elapsed timestamps при wall rollback, stale elapsed fix, корректные splits/best time и чтение таких маршрутов из backup.
- Программы: повтор исходной недели вплоть до 52-й, независимые IDs и сохранность исходника.
- SQL migration: исходные CREATE TABLE из schema v1, 10 000 UTF-8 workout payload + exercises/runs, точный SQL из production constant, сравнение всех прежних значений/schema, новая пустая таблица, отсутствие DROP/DELETE.
- Проверка экспорта до записи и обязательных структур backup v3.

## Обязательный следующий Android-прогон

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest
```

Нужны API 29 и API 35; GPS/батарею проверять на физическом телефоне. **Новый FitTestRunner использует отдельные `fitprogress-instrumentation.db` и `profile-instrumentation`, отключает собственное планирование фоновых задач.** Перед очисткой MainFlowTest проверяет этот режим и отказывается работать с production storage. Тесты всё равно следует запускать на тестовом профиле/эмуляторе: instrumentation останавливает процесс приложения и может прервать текущую запись. Старый тестовый APK без этого runner архивирован в `obsolete-before-2.1`; не запускайте его, пересоберите Android-тесты через Gradle.

1. **Upgrade:** установить исходную 2.0 с несколькими тренировками/подходами/маршрутами и профилем. Экспортировать. Обновить той же подписью через `-r` без удаления/очистки. Проверить прежние данные и новые пустые goals/diary. Android migration test создаёт v1 файл, открывает Room v2 и пишет новые records, но до исполнения это только исходник/compilation.
2. **Первый запуск:** нет целей/дневника и фиктивных результатов. Создать упражнение, тренировку, фактические подходы, отдых, финиш, историю.
3. **Таймеры:** pause/resume, изменение wall clock, background/rotation/destroy Activity, lock screen, звук/vibration off. После reboot проверить checkpoint/recovery, а не засчитанное время выключенного телефона.
4. **Цели:** создать несколько типов, достичь реальной записью, поменять период/источник/min reps, архивировать, восстановить после relaunch. Проверить пустой и истёкший период.
5. **Дневник:** создать/изменить/удалить дату; изменить дату в форме, проверить отдельную запись. Проверить, что существующая оценка не заменяется defaults при восстановлении Activity.
6. **Coach:** история двух недель + diary; pain/poor sleep/fatigue блокируют progression. Проверить concrete diff, отклонение без изменений, подтверждение, повторное применение, новую запись боли после preview. Старые результаты неизменны, целевые planned slots заменяются только после подтверждения.
7. **Аналитика:** сравнить ручным счётом дни/объём/источники, primary groups без двойного счёта, e1RM как оценку. Ползунок выбирает реальные даты.
8. **Программы:** 1/4/52 недели, jump, delete set (минимум один), reorder/rest, repeat through end, preview/cancel/apply. Завершённые/active/past slots остаются историей.
9. **GPS:** точное/приблизительное/отказ, provider off, GMS absent, stationary fixes, 15–30 минут lock screen/offline, pause/move/resume, wall clock change, km signal on/off, force stop/relaunch. Не использовать demo/mock как доказательство реального GNSS.
10. **Настройки/напоминания:** смена theme/accent, карта off не грузит тайлы и сохраняет схему; соседние toggles не перезаписываются. WorkManager приблизителен; проверить opt-in, разрешение Android, один reminder/date, пустой план и Doze.
11. **Backup:** JSON v1/v2/v3, новые records, repeated import, checksum/structure/invalid active demo/manual, checked export limit. Профиль/settings/images не заявлены переносимыми.
12. **Доступность/нагрузка:** малый экран, landscape, keyboard, font scale 2.0, длинные названия, TalkBack. Несколько тысяч тренировок/длинных треков, CPU/memory/battery profiling. SQL-тест 10 000 строк не доказывает Android UI performance.
