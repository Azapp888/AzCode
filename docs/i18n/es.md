# AzCode (Android)

[简体中文](zh-CN.md) | [English](en.md) | [日本語](ja.md) | [한국어](ko.md) | [Français](fr.md) | [Deutsch](de.md) | [Русский](ru.md)

AzCode es un asistente de automatización local para Android. Describe una tarea en lenguaje natural y el bucle de agente integrado lee la pantalla, pulsa controles, desliza y ejecuta comandos a través del servicio de accesibilidad hasta completar la tarea. Todos los datos permanecen en el teléfono, sin sincronización en la nube.

- Nombre de la aplicación: **AzCode**
- Etiqueta de forma GUI: **magic**
- Etiqueta de forma CLI: **Listen**
- Versión actual: **1.9.15** (versionCode 70)
- Requisitos: Android 8.0+ (minSdk 26), targetSdk 34

AzCode funciona de forma autónoma o expone un puente de capacidades enlazado solo a `127.0.0.1:8848` para que la CLI Listen u otros clientes reutilicen las mismas capacidades mediante `adb forward`.

## Funciones

| Capacidad | Descripción |
| --- | --- |
| Tareas en lenguaje natural | Entrada por chat, planificación multipaso del agente, visualización de llamadas a herramientas en vivo |
| Control de pantalla | Leer nodos, pulsar por texto o coordenadas, deslizar, navegación del sistema, entrada de texto Unicode |
| Inicio de apps | Inicio por paquete o nombre de app con respaldo shell y de accesibilidad |
| Ejecución de comandos | Canales Termux, shell integrado, Shizuku y Root según el modo de privilegios |
| Varios modelos | Varias cuentas de proveedor, protocolos compatible con OpenAI, Anthropic y Gemini |
| Profundidad de pensamiento | Cuatro niveles (apagado, rápido, estándar, profundo) con compatibilidad de parámetros por modelo |
| Sesiones | Sesiones separadas, cajón de historial, títulos automáticos, continuación y compresión de contexto |
| Adjuntos | Imágenes, audio, PDF, Office, texto y código |
| Entrada de voz | Reconocimiento sin conexión del sistema y reconocimiento en línea de Xunfei conmutables |
| Asistente flotante | Entrada de asistente digital predeterminado, halo transparente, preguntas en la página y teclado |
| Memoria a largo plazo | Tokens sensibles, hábitos, métodos y personalización local |
| Habilidades y complementos | Habilidades integradas y propias, instalación de GitHub SKILL.md, escaneo de complementos populares |
| GitHub | Leer y escribir archivos, explorar ramas y commits, gestionar issues y pull requests |
| Generación de imágenes | Texto a imagen con varios proveedores, mostrada y guardable en el chat |
| Documentos Office | Generación de docx / xlsx / pptx mediante GenOffice MCP con vista previa |
| Método de entrada | Teclado AzCode integrado como respaldo para escribir texto Unicode |
| Puente de capacidades | API HTTP en loopback y CLI Listen |
| Registros | Registros de fallos y ejecución en disco, consultables, copiables y borrables |

## Inicio rápido

### Instalación

Descarga un APK desde las GitHub Releases `android-v*` o toma el artefacto `azcode-magic-apk` de GitHub Actions. Las compilaciones debug y release comparten la misma clave de firma del repositorio, por lo que pueden instalarse una sobre otra.

### Configuración inicial

1. Abre Ajustes > Capacidades del dispositivo > Accesibilidad y activa **AzCode Screen Control** en los ajustes del sistema.
2. Ve a Ajustes > Gestión de modelos, añade y activa un proveedor e introduce una clave API. La URL base y el modelo pueden usar plantillas.
3. Vuelve a la pantalla principal y confirma el modelo activo en el selector.
4. Escribe una tarea y envíala, por ejemplo «Abrir ajustes y ver la versión de Android».

### Permisos

| Permiso | Uso |
| --- | --- |
| Servicio de accesibilidad | Leer pantalla, pulsar, deslizar, navegación del sistema, escribir texto |
| Micrófono | Entrada de voz y asistente flotante |
| Notificaciones | Avisos de fin de tarea y de preguntas |
| Mostrar sobre otras apps | Halo del asistente flotante |
| Cámara | Adjuntos de foto |
| Termux RUN_COMMAND | Ejecutar comandos Linux completos a través de Termux |
| Shizuku | Ejecutar comandos de nivel sistema como adb |

### Compilar desde el código fuente

Se necesitan JDK 17 y el SDK de Android. El repositorio no incluye `gradle-wrapper.jar`, así que usa el `gradle` del sistema:

```bash
gradle assembleDebug
gradle assembleRelease
```

Tras subir el código, `.github/workflows/android.yml` compila APK Debug y Release en GitHub Actions. Subir una etiqueta `android-v*` crea una GitHub Release.

## Capacidades del dispositivo y ejecución de comandos

| Modo | Canal | Identidad | Requisito | Tiempo límite |
| --- | --- | --- | --- | --- |
| NORMAL + Termux | Termux `RUN_COMMAND` | uid de Termux | Termux instalado, RUN_COMMAND concedido, `allow-external-apps=true` | 90 s |
| NORMAL + shell integrado | `/system/bin/sh -c` | uid de la app | Ninguno | 60 s |
| SHIZUKU | Shizuku binder | adb uid 2000 | Shizuku iniciado y autorizado | 60 s |
| ROOT | `su -c` | uid 0 | Dispositivo con root | 60 s |

El modo NORMAL intenta primero Termux y recurre al shell integrado. Acceder a directorios privados de otras apps o cambiar ajustes del sistema requiere Shizuku o Root.

## Datos locales y almacenamiento

AzCode no usa SQLite, Room ni base de datos de servidor. Su capa de datos son `SharedPreferences` privadas de la app y archivos JSON:

| Ruta | Contenido |
| --- | --- |
| `SharedPreferences` | Proveedores de modelo, cursor de sesión, habilidades, memoria de comandos, configuración GitHub / GenOffice, modo de voz y privilegios |
| `files/sessions/<id>.json` | Historial de chat y contexto del agente de una sesión |
| `files/memory.json` | Entradas de memoria a largo plazo |
| `files/logs/YYYY-MM-DD.log` | Registros rotativos diarios, hasta 10 archivos, 512 KB por archivo |
| `files/genoffice/` | Documentos Office generados |
| `cache/az_images/`, `cache/captures/` | Caché de imágenes y archivos temporales de foto |

Las sesiones se escriben mediante archivo temporal y renombrado. El contexto del agente conserva como máximo 40 mensajes y los anteriores se resumen. Desinstalar la app borra todos los datos locales.

## Puente de capacidades y CLI Listen

El puente escucha solo en `127.0.0.1:8848`, no es accesible desde internet y no tiene autenticación. El acceso remoto requiere `adb forward`.

| Método | Ruta | Descripción |
| --- | --- | --- |
| GET | `/health` | Estado de accesibilidad, Shizuku, Root y modo de privilegios |
| GET | `/screen` | Nodos visibles de la pantalla, hasta 200 |
| POST | `/tap` | `{"x":123,"y":456}` o `{"text":"OK"}` |
| POST | `/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"duration":300}` |
| POST | `/global` | `{"action":"back"}`, admite back / home / recents / notifications |
| POST | `/shell` | `{"cmd":"pm list packages"}`, ejecutado según el modo de privilegios actual |
| POST | `/notify` | `{"title":"..","body":".."}` |
| POST | `/agent` | `{"task":"..","session":"id opcional"}`, devuelve `answer` y `events` |

Con el teléfono conectado por adb:

```bash
curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/main/tools/install.sh | bash
listen run "Abrir ajustes y ver la versión de Android"
listen screen
listen tap --text "OK"
```

## Privacidad y seguridad

- Las claves API de modelos, tokens de GitHub, tokens de GenOffice y credenciales de Xunfei se guardan solo en el almacenamiento privado de la app; la app no lee variables de entorno.
- Las instantáneas de personalización local se generan en el dispositivo y nunca se inyectan en conversaciones en la nube.
- El puente se enlaza solo a loopback; el acceso remoto depende del reenvío adb.
- El reconocimiento en línea de Xunfei envía audio a la plataforma abierta de Xunfei; el reconocimiento del sistema integrado prioriza el procesamiento sin conexión.
- Shizuku y Root pueden ejecutar comandos de nivel sistema y un error puede dañar el dispositivo o los datos. Úsalos solo en dispositivos que poseas o tengas autorización explícita para operar.

## Limitaciones conocidas

- La lectura de pantalla y el control de la interfaz dependen del servicio de accesibilidad, que algunos fabricantes restringen en segundo plano.
- El acceso shell de nivel sistema depende de Shizuku o Root; el modo NORMAL está limitado por el sandbox de la app.
- La entrada de audio e imagen depende de la capacidad multimodal del modelo elegido.
- La generación de imágenes depende de las API de los proveedores, que difieren en tamaños y cantidades compatibles.
- No hay sincronización en la nube; los datos se eliminan con la app.

## Enlaces

- Documentación completa: `README.md` en la raíz
- Descripción del producto: `PRODUCT.md`
- Sistema de diseño: `DESIGN.md`
- Herramientas CLI: `tools/listen` y `tools/install.sh`

## Licencia

MIT. Los módulos de accesibilidad y ejecución de Shizuku están adaptados de [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android) (MIT). La API de Shizuku proviene de [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API).
