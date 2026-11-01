# Проверки FitProgressAI 2.1-dev

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
