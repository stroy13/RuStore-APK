#!/usr/bin/env python3
"""Иконка приложения: берёт готовый мастер-файл icon-master.png (192px,
скруглённые прозрачные углы) и кладёт его в ресурсы. Без зависимостей."""
import shutil
import os

HERE = os.path.dirname(__file__)
SRC = os.path.join(HERE, "icon-master.png")
DST = os.path.join(HERE, "res", "mipmap", "ic_launcher.png")
os.makedirs(os.path.dirname(DST), exist_ok=True)
shutil.copyfile(SRC, DST)
print("иконка: res/mipmap/ic_launcher.png (из icon-master.png)")
