<p align="center">
  <img src="iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png" alt="Logo EnduLink / motillOS" width="128" />
</p>

# EnduLink (`motillOS` en iOS)

**Navegación trail / enduro por GPX con envío en directo a la pantalla TFT de tu moto.**

Conecta tu móvil por Wi-Fi a cuadros TFT compatibles con **EasyConn / Carbit Ride** (como Rieju Aventura 500/307, CFMoto, Voge o Benelli) y lleva el mapa directamente en el cuadro de la moto mientras el teléfono va **protegido y bloqueado en el bolsillo**. Todo sin perder tus datos móviles 4G/5G y pudiendo rodar **100 % sin cobertura o en modo avión** si descargas los mapas antes de salir.

---

## ¿Qué puedes hacer con EnduLink?

- **Mapas topo, satélite y trail listos para rodar:**
  Visualiza tus tracks GPX con fluidez en móvil, tablet o iPad, elige entre distintas capas (*IGN MTN25*, *CyclOSM*, *OpenTopoMap*, ortofotos, *OSM*) y sigue la ruta con flechas de dirección claras y modos *Libre*, *Centrado* o *Rumbo arriba*.
- **Descarga de mapas offline:**
  Descarga una zona completa o únicamente el corredor alrededor de tu track GPX para navegar sin gastar datos ni depender de la cobertura en montaña.
- **Navegación off-road pensada para campo:**
  - Toca cualquier track en el mapa para navegarlo, invertir su sentido, cambiarle el nombre/color o ver su perfil de altitud.
  - Aviso de salida de ruta con distancia al track, línea directa de regreso y recálculo automático.
  - Grabación de ruta en vivo (`REC`) con exportación a `.gpx` y marcado de paradas o incidencias (cancelas, pasos técnicos, agua, gasolineras, peligros).
- **Envío por Wi-Fi al cuadro TFT de la moto:**
  - Escanea el código QR de Carbit/EasyConn desde la app para guardar y vincular el Wi-Fi de la moto automáticamente.
  - Reconexión automática al dar contacto y pausa inteligente para ahorrar batería cuando apagas la moto.
  - **Auto-Zoom dinámico por velocidad** en el cuadro (trialera, pista o carretera) totalmente configurable.
- **Rutas en grupo en tiempo real:**
  Crea o únete a un grupo mediante código o QR para ver a tus compañeros en el mapa y recibir avisos en la pantalla TFT, tanto por Internet como en red local sin cobertura.

---

## Cómo instalarla

Descarga siempre la última versión disponible desde la sección de [**Releases en GitHub**](https://github.com/laita-dev/EnduLink/releases).

---

### Android (`EnduLink`)

> **Requisitos:** Android 8.0 o superior.

1. Entra desde el móvil en [**Releases**](https://github.com/laita-dev/EnduLink/releases) y descarga el archivo **`androidApp-release.apk`** de la última versión publicada.
2. Abre el archivo descargado. Si es la primera vez que instalas una app fuera de Google Play, Android te pedirá permiso para **instalar aplicaciones desconocidas** desde tu navegador o gestor de archivos; acéptalo.
3. Pulsa **Instalar** y listo.

#### Actualizaciones en Android
No hace falta volver a entrar en GitHub para actualizar: **EnduLink se actualiza desde dentro de la propia aplicación**.
- Toca el icono de **Ajustes (⚙️)** en la barra superior de la app.
- Si hay una nueva versión disponible, pulsa **Descargar e Instalar APK** y la app se encargará de bajarla y abrir el instalador automáticamente.

---

### iOS / iPadOS (`motillOS`) con Sideloadly

> **Requisitos:** iPhone o iPad con **iOS / iPadOS 16.0 o superior**, un ordenador (**Windows** o **Mac**), cable USB y una cuenta **Apple ID** (sirve una cuenta gratuita normal).
>
> **Nota importante sobre cuentas Apple gratuitas:** Las apps instaladas con un Apple ID gratuito caducan cada **7 días** (y Apple permite un máximo de 3 apps firmadas a la vez). Pasados 7 días basta con volver a pasar el archivo por Sideloadly para renovarla **sin perder tus tracks ni tus mapas descargados**.

#### Paso previo común
1. Descarga el archivo **`EnduLink-unsigned.ipa`** desde [**Releases**](https://github.com/laita-dev/EnduLink/releases) en tu ordenador.
2. Descarga e instala **Sideloadly** desde su web oficial: [sideloadly.io](https://sideloadly.io).

---

#### Instalación desde Windows

En Windows, Sideloadly necesita las versiones clásicas de Apple (no las de la Microsoft Store) para poder comunicarse con el iPhone:

1. **Instala iTunes y iCloud en su versión web oficial de Apple** *(muy importante: **no** uses las versiones de Microsoft Store; si las tienes instaladas, desinstálalas primero)*:
   - Descarga **iTunes** (instalador `.exe` de 64 bits desde la web de Apple / enlace directo indicado en [sideloadly.io](https://sideloadly.io)).
   - Descarga **iCloud para Windows** (instalador `.exe` clásico indicado en [sideloadly.io](https://sideloadly.io)).
   - Abre iTunes una vez tras instalarlo para asegurarte de que los servicios de Apple quedan activos.
2. **Conecta el iPhone o iPad por cable USB** al PC y, si aparece el aviso en el teléfono, pulsa **Confiar en este ordenador**.
3. **Abre Sideloadly**:
   - Verifica que tu dispositivo aparece en el desplegable **iDevice**.
   - Arrastra el archivo **`EnduLink-unsigned.ipa`** sobre el icono de IPA en Sideloadly.
   - Introduce tu **Apple ID** en el campo correspondiente.
4. Pulsa **Start** (la primera vez te pedirá tu contraseña de Apple ID y el código de verificación en dos factores).
5. Espera a que aparezca **`Done`** en la parte inferior.

---

#### Instalación desde Mac (macOS)

En Mac no necesitas instalar iTunes ni iCloud por separado:

1. **Abre Sideloadly** (si macOS bloquea la apertura la primera vez, ve a *Ajustes del Sistema > Privacidad y seguridad* y pulsa *Abrir igualmente*).
2. **Conecta el iPhone o iPad por cable USB** al Mac y pulsa **Confiar en este ordenador** en la pantalla del teléfono si te lo solicita.
3. Si Sideloadly te ofrece configurar el método de firma (*Local Anisette* o el *Mail Plug-in*), sigue las instrucciones en pantalla para activarlo.
4. En Sideloadly:
   - Comprueba que tu dispositivo aparece en **iDevice**.
   - Arrastra el archivo **`EnduLink-unsigned.ipa`** a Sideloadly.
   - Introduce tu **Apple ID**.
5. Pulsa **Start**, introduce tu contraseña/código de Apple ID si es la primera vez y espera a que muestre **`Done`**.

---

#### Primer arranque en el iPhone / iPad (Windows y Mac)

Antes de abrir **motillOS** por primera vez tras instalarla:
1. **Activa el Modo de desarrollador** (si no lo tenías activado):
   Ve a **Ajustes > Privacidad y seguridad > Modo de desarrollador**, actívalo y reinicia el teléfono cuando te lo pida.
2. **Confía en tu certificado de Apple ID**:
   Ve a **Ajustes > General > Gestión de dispositivos y VPN** (o *Perfiles*), toca sobre tu **Apple ID** y pulsa **Confiar**.
3. Ya puedes abrir **motillOS** desde la pantalla de inicio y conceder los permisos de Ubicación (*Siempre* para que funcione con el móvil en el bolsillo), Cámara (para escanear el QR de la moto) y Red local.

#### Actualizaciones en iOS
Por limitaciones de Apple, iOS no permite instalar actualizaciones automáticamente desde dentro de la app. Cuando pulses el icono de **Ajustes (⚙️)**, la app te avisará si hay una versión nueva disponible; para actualizarla, descarga el nuevo `EnduLink-unsigned.ipa` desde Releases y vuelve a instalarlo con **Sideloadly** encima del anterior (conservarás todos tus tracks y mapas offline).

> *Nota avanzada:* Si usas **SideStore** en tu iPhone, también puedes añadir la fuente `https://raw.githubusercontent.com/laita-dev/EnduLink/main/sidestore-source.json` para instalar y renovar la app directamente desde el teléfono sin ordenador.
