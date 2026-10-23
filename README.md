# PDFdoc — Reader & Editor

Бесплатное офлайн-приложение для работы с PDF на Android: без рекламы, регистрации, подписок и телеметрии.
У приложения нет разрешения `INTERNET`: документы никогда не покидают устройство.

Подробное ТЗ, архитектура и план релиза — в [docs/SPEC.md](docs/SPEC.md).

## Что уже работает (этап 1)

- Главный экран: последние файлы с миниатюрами, поиск, сортировка, открытие PDF.
- Просмотр: непрерывная прокрутка, pinch-zoom, двойной тап, переход к странице, ночной режим,
  полноэкранный режим, отправка, печать, открытие в другом приложении, запоминание страницы.
- Страницы: поворот, удаление, извлечение в новый PDF, перестановка перетаскиванием, отмена, сохранение.
- Объединение нескольких PDF.
- Светлая, тёмная и системная тема, Material You на Android 12+.

## Сборка

Нужны JDK 17 и Android SDK (platform 36).

```
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit-тесты
```

GitHub Actions собирает APK на каждый push в `main` (вкладка Actions → артефакт `pdfdoc-debug-apk`).

## Стек

Kotlin, Jetpack Compose, Material 3, Hilt, Room, DataStore, Navigation Compose,
PdfRenderer (просмотр), PdfBox-Android (изменение PDF).
