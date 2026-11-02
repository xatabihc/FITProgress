# Технический аудит и стабилизация 2.1-rc1

Дата: 2026-11-01. Исходная точка Git `194da27` (2.1-dev), прежний 2.0 — `dd9fb79`. Перед работой tracked changes отсутствовали. Сохранена копия исходного APK; история Git и пользовательская база не удалялись. Текущий applicationId прежний, versionCode 4.

## Исправления по аудиту

| Риск | Изменение | Подтверждение |
|---|---|---|
| Действие «завершить» обгоняет сохранение подхода после dispatch на IO | Mutex очереди ViewModel захватывается в порядке вызовов на main; exclusive атомарен | JVM queue regression; Android ViewModel тест с Room подготовлен |
| Последующий старт использует current завершённой пробежки | Выбор только unfinished, запрет resume(done), сохранённый run неизменяем, finish идемпотентен | JVM пройдены; repository Android тест скомпилирован |
| Старый FINISH останавливает новый START | stopSelfResult(startId); foreground снимается только для последней команды | Android compilation; реальная гонка сервиса не исполнена |
| Удаление timed упражнения оставляет активный deadline | Сброс orphan таймера перед валидацией сохранения | JVM пройдены |
| Ошибка чтения Room/JSON/DataStore приводит к падению/непонятному пустому экрану | Явная ошибка чтения и блокировка UI-изменений, без erase/recreate | Android compilation; malformed-row тест подготовлен |
| Отказ запуска foreground service/уведомлений | Обработка runtime отказа; обычные notify проверяют Android33 permission | Android compilation; Lint/runtime не выполнены |
| Повтор команды во время остановки workout timer | Перезапуск polling job, stopSelfResult; продление wake lock для нового countdown | Android compilation; lock screen/Doze не проверены |
| Длинные формы на малой высоте | Прокрутка содержимого нескольких диалогов | Compose compilation; визуальная проверка не выполнена |

Новых крупных возможностей нет. Настройки, цели, дневник, Coach и аналитика прежней 2.1-dev сохранены.

## Данные при обновлении

Room остаётся v2; migration 1→2 только CREATE TABLE personal_records, прежние таблицы JSON payload не меняются. Destructive fallback отсутствует. Точный production SQL исполнен SQLite JDBC на schema v1 с 10 000 workout payload; старые значения сравниваются побайтно, таблицы exercises/runs сохраняются. Это проверка SQL, не проверка Android SQLite/Room открытием на телефоне. Такой инструментальный тест находится в RepositoryTest и пока только скомпилирован.

Проверка APK сравнивает локальную debug-подпись и native библиотеки с исходным APK 2.1-dev; старый этап `docs/verification/v2.1/` содержит сравнение с 2.0-dev. Пакет сохраняется, versionCode растёт 3→4. Обновление установленной 2.0 с другой подписью невозможно без совместимого ключа; не обходить это удалением единственной истории. Перед реальным обновлением экспортировать JSON, установить через `adb install -r`, проверить историю и настройки. Реальное обновление телефона в этой среде не проверено.

## Проверки и границы результата

110 JUnit-тестов реально пройдены. Полная компиляция Kotlin/Compose + регенерация RoomProcessor/Java + компиляция 20 Android-тестов выполнена SDK fallback. Android-тесты используют изолированные Room/DataStore, production очистка запрещена. `tools/run-phone-tests.py` проверяет runner/подписи перед установкой и требует явного --run; uninstall/pm clear не выполняет. Инструментальные тесты не исполнены в этой среде.

Обычный Gradle остановлен до задач: read-only wrapper lock; с GRADLE_USER_HOME=/tmp — SocketException Operation not permitted у Gradle daemon. ADB server также не может открыть socket. Отдельного Lint CLI нет (ClassNotFoundException для официального Main); наличие Lint engine в IDE не считается выполнением Lint. Нельзя утверждать, что Lint, clean Gradle build, установка, фоновые сервисы или UI на телефоне прошли проверку.

Оставшиеся P0: обычный Gradle unit/Lint/assemble; исполнение 20 Android-тестов на API29/35; реальное обновление с 2.0; GNSS при lock screen/offline/Doze; восстановление процесса; визуальные small screen/large font/keyboard проверки. До этого версия обозначена rc1, не стабильной 2.1.
