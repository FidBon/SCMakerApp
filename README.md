# SC Background Maker

Android-приложение для создания фоновых файлов Supercell `.sc`.
Берёт твою картинку (PNG/JPG), запекает её в копию структуры шаблонного фона
`background_anime.sc` и выдаёт готовый `.sc` файл, пригодный к подмене в игре.

## Как это устроено внутри

Шаблон `app/src/main/assets/template.sc` — это тот самый твой `background_anime.sc`.
В нём уже корректно прописаны:

- `shapes_count=14, movie_clips=9, textures=1, matrices=2935, color_transforms=628`
- один экспорт `bgr_anime` (id=35)
- одна текстура 768×896 RGBA8888 (TAG 0x01)
- shape'ы с UV-координатами и polygon-ами, которые из этой текстуры собирают сцену

Приложение делает следующее:

1. Распаковывает `template.sc`
   (SC header + Supercell-LZMA: `5d 00 00 04 00` props + 4-байт uncompressed size + LZMA stream).
2. Парсит внутренний header (counts → reserved → exports).
3. Находит первый texture-тег (TAG 0x01), читает его pixel_format/width/height.
4. Масштабирует твой PNG до точно такого же размера (768×896) и кодирует
   в тот же pixel_format.
5. Перезаписывает только пиксельные байты этого тега, опционально
   меняет имя экспорта.
6. Запаковывает обратно: LZMA в Supercell-варианте → SC header → MD5 хэш.

Геометрия shape'ов остаётся прежней — поэтому твоя картинка должна быть
**атласом такого же формата 768×896**, где спрайты лежат в тех же
позициях, что в оригинале. Можно открыть PNG, который ты получил из
первого ответа, и редактировать его поверх (фотошоп, gimp, krita).

## Сборка

1. Открыть папку `SCMakerApp` в Android Studio (Hedgehog / Iguana).
2. Дождаться синхронизации Gradle.
3. Run на устройстве / эмуляторе (minSdk 24, Android 7+).
4. Или собрать APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

> Если Gradle Wrapper отсутствует — в Android Studio открой проект,
> IDE сама предложит создать wrapper, либо выполни `gradle wrapper`
> в корне с установленным локально Gradle 8.4.

## Использование

1. Запускаешь приложение.
2. «Выбрать PNG» → выбираешь свой атлас.
3. (Опц.) меняешь имя экспорта — например, в зависимости от того,
   ресурс какого фона ты подменяешь в игре.
4. «Собрать .sc» → «Сохранить» → файл попадает в `Downloads/SCMaker/`.

## Известные ограничения

- Используется фиксированная геометрия (та же, что в `background_anime.sc`).
  Если нужен другой layout — нужно подменять не только текстуру, но и
  shape-теги, а это уже полноценный SC-editor.
- Pixel-format подбирается под шаблон (RGBA8888 в нашем случае).
- В шаблон зашит ровно один texture-тег; если делать мульти-текстурный
  фон — нужно расширять код.
- Не поддерживается формат `_tex.sc` (отдельные текстурные файлы) — здесь
  текстура встроена внутрь `*.sc`.

## Файлы

```
SCMakerApp/
├── app/
│   ├── build.gradle                       — зависимости (xz-for-java для LZMA)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/template.sc             — твой исходный background_anime.sc
│       ├── java/com/scmaker/app/
│       │   ├── MainActivity.kt            — UI (Material 3, View-based)
│       │   └── SCBuilder.kt               — весь reverse-engineering формата
│       └── res/...
├── build.gradle, settings.gradle, gradle.properties
└── README.md
```

---

## 📱 Сборка APK прямо с телефона

Без компьютера — см. [BUILD_FROM_PHONE.md](BUILD_FROM_PHONE.md).
Короче: залей этот проект на GitHub, и Actions сами соберут `app-debug.apk`,
который можно скачать обратно на телефон. Файл `.github/workflows/build.yml`
для этого уже включён в проект.
