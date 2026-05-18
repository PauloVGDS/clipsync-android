# ClipSync - app Android

App Android nativo do ClipSync. Vai conversar via BLE com o firmware
ESP32 (https://github.com/PauloVGDS/clipsync-firmware) para receber/
enviar conteudo da clipboard sob comando do display touch.

Status: skeleton inicial (Android Studio template). Implementacao em
andamento - vai substituir a configuracao temporaria via Tasker
descrita no README do firmware.

Projeto irmao no umbrella: https://github.com/PauloVGDS/clipsync

## Setup

1. Abra a pasta no Android Studio.
2. Sync Gradle.
3. Run em device fisico (BLE nao funciona em emulador padrao).

## Protocolo BLE

UUIDs e codigos de comando definidos em
https://github.com/PauloVGDS/clipsync-firmware/blob/main/src/main.cpp
e documentados no README do firmware. Cliente PC de referencia em
https://github.com/PauloVGDS/clipsync-pc-client.
