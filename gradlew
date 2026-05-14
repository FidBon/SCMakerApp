#!/usr/bin/env sh
# Минимальный gradlew. Если есть system gradle — использует его,
# иначе подсказывает как поставить.
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    echo "Gradle не установлен. Варианты:"
    echo "  1) Termux:  pkg install -y && wget gradle-8.4-bin.zip..."
    echo "  2) Используй GitHub Actions (см. BUILD_FROM_PHONE.md)"
    echo "  3) Открой проект в Android Studio — оно само поставит wrapper"
    exit 1
fi
