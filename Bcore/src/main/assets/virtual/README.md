# Virtual Media Resources

Папка `virtual/` содержит заменяющие ресурсы для гостевых приложений.

## Структура

```
<filesDir>/virtual/
├── <profileId>/
│   └── <packageName>/
│       ├── camera/
│       │   ├── preview.jpg     # кадр для Camera1.PreviewCallback (JPEG/YUV)
│       │   ├── photo.jpg       # JPEG для takePicture callback
│       │   └── record.mp4      # видео для MediaRecorder
│       ├── mic/
│       │   └── audio.wav       # PCM-данные для AudioRecord.read() (отсутствует = тишина)
│       └── sensors/
│           └── scenario.json   # сценарий движения датчиков
└── default/
    └── <packageName>/          # если нет профиль-специфичного файла
        └── ...
```

## scenario.json

```json
{
  "motion": "walking",          // stationary | walking | running | cycling
  "initialStepCount": 5000,     // начальное значение шагомера
  "accelerometer": true,        // перехватывать акселерометр
  "gyroscope": true,            // перехватывать гироскоп
  "stepCounter": true           // перехватывать шагомер
}
```

## Поставка файлов

Файлы можно положить через adb или из кода хост-приложения:
```
adb push preview.jpg /data/data/<hostPkg>/files/virtual/profile1/com.example.app/camera/preview.jpg
```

Или через `VirtualResourceManager.ensureRoot(profileId, pkg, "camera")`.
