# 📲 Залить на GitHub с телефона за 5 минут

Внутри ZIP уже готовый git-репозиторий — нужно только сделать `push`.

---

## Что поставить на телефон

1. **Termux** — из F-Droid (НЕ из Google Play, там старая версия):
   <https://f-droid.org/packages/com.termux/>
2. **F-Droid** ставится отсюда: <https://f-droid.org/>

Если нет желания возиться с F-Droid — поставь Termux из Play Store, тоже сработает,
просто чуть медленнее на современных Android.

---

## Получи Personal Access Token (PAT)

GitHub не пускает по паролю — нужен токен.

1. Зайди на <https://github.com/settings/tokens>
2. **Generate new token → Generate new token (classic)**
3. Note: `scmaker` (любое), Expiration: 90 дней
4. Отметь галочку **`repo`** (даст полный доступ к репозиториям)
5. Внизу **Generate token**
6. 🔑 Скопируй токен сразу — он показывается только один раз.
   Лучше сохранить его в заметках/буфере.

---

## Создай пустой репозиторий

1. <https://github.com/new>
2. Имя: `scmaker` (или любое)
3. **НЕ ставь** галочки на README / .gitignore / license — репо должен быть **пустой**
4. Create repository

---

## Залей проект из Termux

Открой Termux и копируй построчно:

```bash
# дать доступ к скачанным файлам
termux-setup-storage
# (нажми РАЗРЕШИТЬ во всплывающем окне Android)

pkg update -y && pkg install -y git unzip

# распаковать zip (он у тебя в Downloads после моего сообщения)
cd ~/storage/downloads
unzip -o SCMakerApp.zip
cd SCMakerApp

# подсказать git кто ты (один раз)
git config --global user.email "ты@example.com"
git config --global user.name "TwojNick"

# подключить твой репозиторий и запушить
git remote add origin https://github.com/ТВОЙ_ЛОГИН/scmaker.git
git push -u origin main
```

Когда `git push` спросит:
- **Username:** твой логин GitHub
- **Password:** вставь **Personal Access Token** (НЕ пароль от GitHub)

После успешного push — открой репо в браузере, файлы должны быть там.

---

## Дождись APK

1. На странице репо тапни вкладку **Actions** (вверху, в одну строку с Code/Issues/PR).
2. Если просит подтвердить — жми **I understand my workflows, go ahead and enable them**.
3. Уже идёт сборка «Build APK». Подожди **~5 минут** (первая сборка дольше).
4. Когда галочка станет зелёной — открой run → внизу **Artifacts** →
   тапни **SCMakerApp-debug-apk** → скачается ZIP.
5. Распакуй ZIP → внутри `app-debug.apk` → ставь.

> Перед установкой APK Android попросит разрешить установку из неизвестных
> источников. Разреши для того приложения, из которого открываешь APK
> (браузер / файловый менеджер).

---

## Если что-то пошло не так

**`Permission denied`** при `cd ~/storage/downloads`
→ забыл `termux-setup-storage`, повтори и нажми Allow.

**`fatal: not a git repository`**
→ ты не зашёл в папку `SCMakerApp`. Сделай `cd SCMakerApp` ещё раз.

**`Authentication failed`** при push
→ скорее всего вставил пароль вместо токена. Сгенерируй PAT заново
(см. раздел выше) и используй его как пароль.

**`remote: Repository not found`**
→ опечатка в URL. Проверь: `https://github.com/ТВОЙ_ЛОГИН/scmaker.git`,
имя должно точно совпадать с тем, что ты создал.

**В Actions нет ни одного workflow**
→ убедись, что в репо есть папка `.github/workflows/build.yml`.
В Termux: `ls .github/workflows/`.

**Build падает с ошибкой**
→ открой failed run, разверни шаг с красным крестиком, скинь мне последние
30 строк лога — поправлю.
