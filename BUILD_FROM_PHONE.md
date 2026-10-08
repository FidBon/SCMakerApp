# Сборка APK с телефона (без компьютера)

Есть три рабочих пути. Все бесплатные. Самый простой — №1.

---

## Путь 1. GitHub Actions (рекомендую) ⭐

GitHub в облаке соберёт APK за тебя. С телефона ты только заливаешь файлы
и потом скачиваешь готовый APK. **5–7 минут** на первую сборку.

### Что нужно
- Аккаунт GitHub (бесплатно: <https://github.com/signup>).
- Браузер на телефоне. Лучше Chrome или Kiwi — у них работает «версия для ПК»,
  это иногда полезно для GitHub.
- ZIP-архив проекта `SCMakerApp.zip` (тот, что я тебе скинул).

### Шаги

**1) Создай новый репозиторий**
   - Открой <https://github.com/new>
   - Имя: например `scmaker`
   - Public или Private — не важно (на public Actions бесплатно безлимит,
     на private — 2000 минут/месяц, более чем хватит)
   - **НЕ ставь** галочки Add README / .gitignore / license — оставь пустым
   - Жми **Create repository**

**2) Залей файлы в репозиторий**
   У тебя 3 варианта:

   **Вариант A — через веб (самый простой):**
   - Распакуй `SCMakerApp.zip` на телефоне (любой архиватор: RAR, ZArchiver,
     встроенный в Files на MIUI/EMUI и т. п.)
   - На странице нового репо жми **uploading an existing file**
   - Выбери файлы в файловом менеджере. Загружай **батчами по 5–10 файлов**,
     иначе мобильный браузер может задумываться.
   - Важно: сохраняй структуру папок! Если браузер не даёт загрузить с
     подпапками — переключи в «версия для ПК» в меню Chrome.

   **Вариант B — через Termux (если есть):**
   ```bash
   pkg install git
   cd ~/storage/downloads        # или куда у тебя распакован проект
   cd SCMakerApp
   git init -b main
   git add .
   git commit -m "init"
   git remote add origin https://github.com/ТВОЁ_ИМЯ/scmaker.git
   git push -u origin main
   # Username = твой логин, Password = Personal Access Token
   # (создаётся тут: https://github.com/settings/tokens, scope = repo)
   ```

   **Вариант C — через приложение Acode + Git plugin** (если Termux лень).

**3) Запусти сборку**
   После того как файлы появились в репо:
   - Открой вкладку **Actions** (в верхнем меню репо)
   - GitHub предложит включить Actions — нажми **I understand my workflows, go ahead and enable them**
   - Workflow «Build APK» сам стартанёт от твоего пуша. Либо нажми **Run workflow**
     рядом с ним, чтобы запустить вручную.
   - Подожди 3–7 минут (первая сборка дольше из-за скачивания зависимостей).

**4) Скачай APK**
   - Открой завершившийся run в Actions (зелёная галочка)
   - Внизу страницы блок **Artifacts** → `SCMakerApp-debug-apk` → тапни, чтобы скачать ZIP
   - Распакуй ZIP → внутри `app-debug.apk`
   - Поставь APK (нужно разрешить «Установка из неизвестных источников» для браузера)

> Если хочешь, чтобы APK сразу появлялся в **Releases** (без распаковки ZIP), —
> запускай через **Run workflow → Run** на вкладке Actions. Тогда workflow
> положит APK в Releases с тегом `build-N`.

### Важно про подпись
APK будет **debug-signed** — ставится без проблем, но Play Store его не примет.
Для личного использования / тестов / подмены в игре этого хватит.
Если нужен release-подписанный APK — пиши, добавлю keystore + signing config.

---

## Путь 2. Termux (полностью локально)

Это **сложно и долго**: Android SDK не запустится в Termux напрямую
(`aapt2`/`d8` — Linux ELF, а у нас Android). Чтобы это обойти, есть скрипт
**[android-sdk-installer-termux](https://github.com/Lzhiyong/termux-ndk)**
с пересобранными бинарниками под Termux, но это требует места (~3–4 ГБ)
и часа на установку.

Кратко, если очень хочется:
```bash
pkg install openjdk-17 wget unzip git
# ... поставить android-sdk через termux-ndk releases (см. ссылку выше)
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
# скачать gradle 8.4
wget https://services.gradle.org/distributions/gradle-8.4-bin.zip
unzip gradle-8.4-bin.zip
export PATH=$PATH:$PWD/gradle-8.4/bin
# собрать
cd SCMakerApp
gradle assembleDebug
# APK тут: app/build/outputs/apk/debug/app-debug.apk
```

В 9 из 10 случаев на этом пути что-то ломается (D8, R8, manifest merger).
GitHub Actions проще.

---

## Путь 3. AIDE Pro (IDE прямо на телефоне)

[AIDE](https://www.android-ide.com/) умеет собирать APK на устройстве.
Минусы:
- Не умеет современный Android Gradle Plugin 8.x — придётся даунгрейдить
  проект (Kotlin 1.7, AGP 4.x, Material 2).
- Платный для Gradle-проектов (~$10 за Pro-функции).

Если интересно — могу подготовить отдельную "AIDE-совместимую" версию
проекта, но она будет с упрощённым UI.

---

## TL;DR

```
ZIP проекта  ──upload──►  GitHub репо
                              │
                              │ Actions автоматически:
                              │   - ставит JDK 17 + Gradle 8.4
                              │   - подтягивает Android SDK
                              │   - собирает app-debug.apk
                              ▼
                          Artifacts ──download──►  APK у тебя на телефоне
```

Никаких компиляторов, SDK, IDE на телефоне ставить не нужно.
