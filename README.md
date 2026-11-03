# EnduLink · Navegación GPX & Envío Wi-Fi a Pantalla TFT de Moto

Navegador GPX interactivo unificado para **Android** e **iOS** desarrollado con **Kotlin Multiplatform (KMP)** y **Compose Multiplatform**.

Transmite el mapa de navegación en directo por Wi-Fi a pantallas de moto compatibles (**EasyConn / Carbit, `592 × 816` H.264** a 15 FPS) de marcas como Rieju (Aventura 500), CFMoto, Voge, Benelli, etc., funcionando **con el móvil bloqueado en el bolsillo de la chaqueta** y conservando los **datos móviles 4G/5G activos** en el teléfono.

---

## Características Principales (`v0.1.0`)

1. **Mapa Interactivo Unificado (Compose Multiplatform):**
   - Motor gráfico compartido en **Compose Canvas / Skia**, 100 % idéntico en Android e iOS sin dependencias de Google Play Services ni Apple MapKit.
   - Gestos táctiles de desplazamiento libre, pellizco de zoom continuo y rotación con dos dedos.
   - Modos de seguimiento GPS: *Libre*, *Centrado* y *Rumbo arriba*.
   - Brújula interactiva con un toque para restablecer al Norte.

2. **Navegación Off-Road y Motor GPX:**
   - **Algoritmo DMD2 de proyección ortogonal:** Detección de desvío fuera de ruta en metros (`offTrackMeters`) y cálculo de distancia restante a meta (`remainingKm`).
   - **Reconexión dinámica adelantada:** Al salirte de la pista (> 35 m), calcula un vector hacia un punto adelantado sobre la traza para reenganchar en sentido de avance sin obligar a retroceder.
   - **Rumbo directo (*as the crow flies*):** Vector de rumbo y distancia directa estilo rally raid hacia waypoints o destinos.
   - **Inversión de ruta instantánea:** Invierte el sentido del track recalculando todos los puntos y cotas.
   - **Grabador en tiempo real (`REC`):** Filtro de parada inteligente (< 1.5 m con velocidad baja) para evitar marañas de puntos al detener la moto, y exportación estándar a `.gpx`.
   - **Marcadores rápidos de 1 toque:** Cancelas/vallas (`gate`), pasos difíciles (`rock`), fuentes/agua (`water`) y peligros (`danger`).

3. **Caché y Teselas Topográficas 100 % Offline:**
   - Soporte para capas topográficas clave: **IGN Topográfico España (MTN25)**, **Trail OSM (CyclOSM con dificultad de pistas)**, **OpenTopoMap** y ortofotos satélite.
   - **Algoritmo de Sobre-Escalado (*Parent Overzoom*):** Si una tesela Z16 no está descargada en el móvil, busca en disco teselas padre (Z15, Z14, Z13...) y escala dinámicamente el cuadrante correspondiente, garantizando que el mapa nunca muestre huecos negros en zonas sin cobertura.
   - Gestor de descargas por regiones precargadas (Península, Pirineos, Comunidades Autónomas, Alpes, etc.).

4. **Transmisión de Vídeo H.264 por Hardware a la Moto (`592 × 816`):**
   - Servidor TCP nativo en puertos `10920` (Vídeo), `10921` (Control) y `10922` (PXC).
   - Botón de llamada activa (*Knock*) al puerto `10930` para forzar la reconexión de la pantalla de la moto.
   - **Android:** Codificación por hardware con `MediaCodec` y `BikeForegroundService` (notificación persistente + WakeLock + WifiLock).
   - **iOS:** Codificación por hardware con `VTCompressionSession` (VideoToolbox) y `AVAudioSession` en segundo plano para evitar cierres `0xdeadfa11`.
   - **Enrutamiento multired:** Los sockets se atan a la interfaz Wi-Fi de la moto sin perder la conexión a Internet 4G/5G del móvil.

---

## Arquitectura de Módulos (Kotlin Multiplatform)

```text
EnduLinkV2/
├── shared/                             # Módulo compartido (85 % del código)
│   ├── src/commonMain/kotlin/
│   │   ├── bike/                       # Protocolo EasyConn / Carbit, framing PXC/RV y sockets
│   │   ├── trail/                      # Parser GPX, algoritmo DMD2 y grabador REC
│   │   ├── tiles/                      # TileStore, Web Mercator y Parent Overzoom
│   │   ├── platform/                   # Interfaces expect (Location, H264Encoder, BackgroundService)
│   │   └── ui/                         # Interfaz Compose Multiplatform (InteractiveMap, HUD, Modales)
│   ├── src/androidMain/kotlin/         # Implementaciones actual Android (MediaCodec, FusedLocation)
│   └── src/iosMain/kotlin/             # Implementaciones actual iOS (VideoToolbox, CoreLocation)
│
├── androidApp/                         # Entrypoint nativo Android
│   ├── src/main/AndroidManifest.xml
│   └── src/main/java/com/oscar/endulink/MainActivity.kt
│
└── iosApp/                             # Wrapper nativo iOS
    ├── project.yml                     # XcodeGen (generación automática del .xcodeproj)
    └── iosApp/iOSApp.swift             # Entrypoint SwiftUI que aloja MainViewController
```

---

## Compilación y Generación de Entregables

### 1. Compilación de Android (Local desde Windows)

Requisitos: JDK 17+ instalado.

```powershell
# Compilar el APK de Release
./gradlew :androidApp:assembleRelease

# El archivo APK generado se ubica en:
# androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk
```

Instálalo en tu teléfono Android activando la depuración USB o transfiriendo el `.apk`.

### 2. Compilación de iOS (Automática vía GitHub Actions)

Cada `git push` a la rama `main` activa el flujo `.github/workflows/build-all.yml` en runners de **macOS 15**:

1. **`EnduLink-iOS-unsigned-ipa`**: Contiene `EnduLink-unsigned.ipa` listo para instalar en tu iPhone físico con **[Sideloadly](https://sideloadly.io/)** o **SideStore** (coste 0 € con cuenta Apple gratuita).
2. **`EnduLink-appetize-simulator-zip`**: Contiene `EnduLink-appetize-simulator.zip` listo para arrastrar directamente a **[Appetize.io](https://appetize.io/upload)** y probar la aplicación en un simulador iOS interactivo desde el navegador web en Windows.

---

## Simulador de Pantalla Wi-Fi en PC (Windows)

Para probar la transmisión de vídeo a la moto sin necesidad de tener la moto al lado:

1. Conecta el PC y el teléfono a la misma red Wi-Fi local.
2. Abre una terminal en Windows y ejecuta:

```powershell
python Tools/moto_falsa_wifi.py <IP_DEL_MOVIL> salida.h264 45
ffplay -f h264 salida.h264
```

