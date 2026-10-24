# PDFdoc — Reader & Editor: техническое задание (доработанное)

Исходный промт написан хорошо по охвату, но содержит несколько технических ошибок и
нереалистичных требований для одной итерации. Ниже — разбор, исправления и итоговое ТЗ,
по которому ведётся разработка.

## 1. Разбор исходного промта

| # | Проблема в промте | Что не так | Решение |
|---|---|---|---|
| 1 | «OCR: ML Kit Text Recognition… поддержка русского» | ML Kit Text Recognition v2 **не поддерживает кириллицу** (только латиница, китайский, деванагари, японский, корейский). | OCR на **Tesseract** (`Tesseract4Android`, Apache 2.0) с моделями `rus` + `eng` (`tessdata_fast`, ~6 МБ). |
| 2 | «Не используй проприетарные SDK» + «CameraX с автоопределением границ» | Готовый бесплатный сканер с автообрезкой — ML Kit Document Scanner — закрытый и требует Google Play Services. | MVP: CameraX + собственная обрезка/перспектива (4 угла, ручная коррекция) на `android.graphics.Matrix`. Автодетекция границ — v1.1 через OpenCV (Apache 2.0). Без GMS приложение годится для F-Droid. |
| 3 | «Редактирование существующего текстового слоя» | В PDF нет «абзацев»: текст — набор позиционированных глифов, шрифты часто встроены подмножеством (нет нужных букв). Ни одна open-source библиотека не делает это надёжно. | Честный компромисс: «замена текста» = белая заплатка поверх старого + новый текст тем же размером. Полноценное редактирование — вне scope. |
| 4 | «PDF в DOCX» | Нет open-source библиотеки для Android с сохранением вёрстки; Apache POI тяжёлый и плохо дружит с Android. | v1.1: собственный генератор DOCX (zip + WordprocessingML) — только текст и абзацы, с предупреждением пользователю. |
| 5 | «Минимальная версия API 26, целевая — последняя» | Ок. | `minSdk 26`, `targetSdk/compileSdk 36`. |
| 6 | Ссылки `[1][2][3]` | Источники не приложены — мусор от другого ИИ. | Удалено. |
| 7 | «Хранение только в приватной директории и SAF» | Верно, но не сказано про права на URI после перезапуска. | `takePersistableUriPermission` для открытых файлов; рабочая копия в `cacheDir`, атомарная запись обратно через `ContentResolver`. |
| 8 | «Локальное шифрование подписей» | `androidx.security:security-crypto` устарела. | AES-256-GCM через Android Keystore напрямую. |
| 9 | «Настройки в Room» (неявно) | Настройки — не реляционные данные. | Настройки — `DataStore Preferences`; Room — только документы, закладки, подписи. |
| 10 | «Полная история изменений» | Если писать каждое действие сразу в PDF, undo станет дорогим. | Паттерн Command: правки хранятся как модель в памяти, undo/redo — по командам, запись в PDF — по «Сохранить». |
| 11 | MVP слишком большой | Сканер + OCR + формы + аннотации + редактирование — это 3–4 месяца работы. | MVP разбит на этапы (раздел 9). |
| 12 | Объём «выдай весь код сразу» | Ответ в один заход физически не поместится, и код без сборки будет нерабочим. | Код живёт в репозитории, каждый этап собирается Gradle и проверяется тестами + CI. |

## 2. Технический стек (итоговый)

- Kotlin 2.2, Jetpack Compose (BOM), Material 3, dynamic color на Android 12+.
- MVVM + Clean Architecture: `ui` → `domain` (use cases, модели) → `data` (репозитории, Room, файлы, PDF-движок).
- Hilt, Room (KSP), DataStore, WorkManager, Coroutines/Flow, Navigation Compose (type-safe routes).
- Рендер: `android.graphics.pdf.PdfRenderer` (системный, быстрый) + кэш битмапов LruCache.
- Модификация PDF: **PdfBox-Android** (`com.tom-roush:pdfbox-android`, Apache 2.0).
- OCR: Tesseract4Android (Apache 2.0), модели `rus`, `eng`.
- Камера: CameraX.
- Тесты: JUnit4, Robolectric (для PDFBox на JVM), Compose UI tests.

Никаких Firebase, аналитики, рекламы, сетевых разрешений. В манифесте **нет** `INTERNET`.

## 3. Структура проекта

```
app/src/main/java/app/pdfdoc/
  PdfDocApp.kt                  @HiltAndroidApp, инициализация PDFBox
  MainActivity.kt               Compose host, intent VIEW для application/pdf
  di/                           Hilt-модули (Database, Dispatchers, Pdf)
  core/
    dispatchers/                @IoDispatcher и т.п.
    result/                     PdfResult / ошибки на русском
  data/
    db/                         Room: AppDatabase, entities, dao
    files/                      FileStore (SAF, cache, атомарная запись)
    pdf/                        PdfBoxPageOps, PdfRendererSource, TextSearch
    repo/                       DocumentRepositoryImpl, SignatureRepositoryImpl
    settings/                   SettingsRepository (DataStore)
  domain/
    model/                      PdfDocument, PageRange, Annotation...
    repo/                       интерфейсы репозиториев
    pdf/                        PageOperations (интерфейс)
  ui/
    theme/                      Theme, Color, Type
    navigation/                 Routes, AppNavHost
    home/                       HomeScreen, HomeViewModel
    viewer/                     ViewerScreen, ViewerViewModel, PageBitmapCache
    pages/                      PagesScreen (сетка миниатюр, drag&drop)
    merge/                      MergeScreen
    annotate/  edit/  forms/  sign/  scan/  convert/  security/  settings/
```

## 4. Экраны и навигация

```
Home ──open/intent──▶ Viewer ──▶ Pages (сетка) ──▶ Split / Extract
  │                     ├──▶ Annotate (режим поверх Viewer)
  │                     ├──▶ Edit (текст/изображения/водяной знак)
  │                     ├──▶ Forms ──▶ SignaturePad
  │                     ├──▶ Security (пароли)
  │                     └──▶ Convert (TXT/JPG/PNG)
  ├──▶ Scan ──▶ ScanReview (обрезка, фильтры) ──▶ OCR ──▶ Viewer
  ├──▶ Merge (выбор нескольких PDF, порядок) ──▶ Viewer
  ├──▶ ImagesToPdf ──▶ Viewer
  └──▶ Settings ──▶ Signatures
```

## 5. Room

```
documents(
  id INTEGER PK, uri TEXT UNIQUE, name TEXT, size_bytes INTEGER,
  page_count INTEGER, last_opened_at INTEGER, last_page INTEGER,
  thumbnail_path TEXT NULL, is_favorite INTEGER)
bookmarks(
  id PK, document_id FK→documents ON DELETE CASCADE, page INTEGER, title TEXT, created_at INTEGER)
signatures(
  id PK, file_name TEXT  -- зашифрованный PNG в filesDir/signatures, created_at INTEGER)
```

## 6. Хранение файлов

- `filesDir/signatures/*.png.enc` — подписи, AES-GCM, ключ в Android Keystore.
- `filesDir/thumbs/<docId>.webp` — миниатюры первой страницы.
- `cacheDir/work/<uuid>.pdf` — рабочие копии при редактировании (PDFBox работает с файлом, не с потоком).
- `filesDir/tessdata/` — модели OCR (поставляются в assets, копируются при первом запуске OCR).
- Пользовательские документы — только по SAF URI (persistable permission). Сохранение: запись во временный файл → `openOutputStream(uri, "wt")`. «Сохранить как» — `ACTION_CREATE_DOCUMENT`.

## 7. Ограничения и риски

- **PdfRenderer не рисует аннотации и поля форм** (проверено на Android 15). Поэтому правки
  сохраняются как настоящие PDF-аннотации с собственным appearance stream (их видно и можно
  править в Acrobat и других программах), а для показа в приложении строится «сплющенная» копия
  в кэше, где внешний вид аннотаций и полей вписан в содержимое страницы. Это же чинит показ
  аннотаций и заполненных форм в любых сторонних PDF.
- **Повторное редактирование:** у аннотаций, созданных приложением, в словаре есть ключ
  `PDFdocEdit` с JSON-описанием правки, поэтому после повторного открытия их можно двигать,
  менять и удалять. Чужие аннотации не трогаются.
- **Кириллица в добавляемом тексте и формах:** 14 стандартных шрифтов PDF её не содержат.
  В приложение встроен Roboto (Apache 2.0), он внедряется в документ.

- **Большие PDF (500+ стр., 100+ МБ):** PdfRenderer открывает лениво; рендерим только видимые страницы, битмапы ограничены размером экрана × zoom, LruCache по байтам. PDFBox грузит документ с `MemoryUsageSetting.setupTempFileOnly()`.
- **PdfRenderer однопоточный:** все вызовы через один `Mutex`.
- **Шрифты:** стандартные 14 шрифтов PDF не содержат кириллицы — для добавляемого текста встраиваем свой TTF (Noto Sans / PT Sans, OFL).
- **Повреждённые PDF:** PdfRenderer бросает `IOException` → понятное сообщение; PDFBox пытается lenient-парсинг.
- **Защищённые PDF:** PdfRenderer не открывает зашифрованные файлы → расшифровываем PDFBox в рабочую копию после ввода пароля.
- **OCR на русском:** `tessdata_fast` ~80–90% на чистых сканах; качество зависит от фильтра «Ч/Б» и разрешения ≥ 300 dpi.
- **Производительность Compose:** миниатюры через `LazyVerticalGrid`, стабильные ключи, декодирование вне main.

## 8. Сборка

```
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # тесты
```
Требуется JDK 17 и Android SDK (platform 36, build-tools). GitHub Actions собирает APK на каждый push.

## 9. План релиза

**Этап 1 (готово):** каркас, главный экран (последние файлы, поиск, сортировка, миниатюры), просмотр (непрерывная прокрутка, zoom, переход к странице, ночной режим), операции со страницами (удаление, поворот, перемещение, извлечение), объединение PDF, тёмная/авто тема.

**Этап 2 — MVP (готово):** поиск по тексту с подсветкой, аннотации (выделение, рисование, фигуры, заметки) с undo/redo, добавление текста и изображений, формы (AcroForm), рукописная подпись, сохранение.

**Этап 3 — MVP:** сканер (CameraX, ручная обрезка, фильтры, многостраничность), OCR rus/eng, searchable PDF (невидимый текстовый слой).

**v1.0:** пароли (AES-256), сжатие, изображения ↔ PDF, PDF → TXT/JPG/PNG, водяные знаки, номера страниц, колонтитулы, закладки и оглавление, печать, настройки, английская локализация.

**v1.1:** автоопределение границ (OpenCV), PDF → DOCX (только текст), режим двух страниц на планшетах, импорт/экспорт настроек, F-Droid.
