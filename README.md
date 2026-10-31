# Nokia 3650 Cam (Android, Kotlin + Camera2)

Apri la cartella in Android Studio (Koala o successivo), sincronizza Gradle ed esegui su un dispositivo reale (API 29+).

- Foto: JPEG 640x480, qualità Basic/Normal/High (25/45/70, da tarare), con look "VGA 2003".
- Video: 3GP, 176x144 a 10 fps, H.263 + AMR-NB (se l'encoder H.263 manca, ripiega su H.264 nel 3GP).
- Salvataggio in Pictures/Nokia3650 e Movies/Nokia3650.

Parametri del look: NokiaLook.kt. Messa a fuoco fissa: CameraEngine.FIXED_FOCUS.
Non compilato né testato su dispositivo.

## Compilare senza Android Studio (GitHub Actions)
1. Crea un repository su github.com e carica il contenuto di questa cartella (compresa `.github`).
2. Vai su Actions > "Build APK" > run più recente > Artifacts: scarica `Nokia3650Cam-debug-apk`.
3. Estrai l'APK, copialo sul telefono e installalo (abilita "installa app sconosciute").
