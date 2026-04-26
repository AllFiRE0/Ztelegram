#!/bin/bash
echo "Downloading Minecraft item textures..."
mkdir -p src/main/resources/textures

# Скачиваем текстуры из репозитория paper-telegram-bridge
curl -L "https://raw.githubusercontent.com/pbl0/paper-telegram-bridge/master/scripts/download-item-icons.sh" -o /tmp/download-icons.sh
bash /tmp/download-icons.sh

# Копируем скачанные текстуры в наш проект
cp -r textures/* src/main/resources/textures/ 2>/dev/null || echo "No textures to copy"
rm /tmp/download-icons.sh
echo "Done!"
