# Traza para Eclipse

**Pregunta como funciona algo de tu proyecto y miralo pasar, paso a paso, por tu propio codigo.**
Diagramas de secuencia animados, simulacion de datos, analisis de logs, mapa de base de datos y simulacion de cambios,
con la IA de **GitHub Copilot for Eclipse** (a traves de MCP).

Es el port a Eclipse IDE de la extension de VS Code [Traza](https://github.com/Ftejero21/Traza) (v0.1.4), con la misma
vista (la webview Angular original) y la misma funcionalidad.

> English summary: Traza for Eclipse is a port of the Traza VS Code extension. It shows how a feature flows through your
> code (sequence diagrams, data simulation, log analysis, database map, change simulation). The AI part is done by GitHub
> Copilot for Eclipse in Agent mode through a local MCP server bundled in the plugin; everything that does not use AI
> (sample flow, opening `.flow.json` files, walkthrough, read-only database metadata, applying changes) works without Copilot.

---

## Que hace

La vista **Traza: Flujo** aloja la webview de Traza con estos modos:

| Modo | Descripcion |
|---|---|
| Recorrido | Diagrama de secuencia animado, con el codigo de cada paso al lado y salto directo al editor en su linea. |
| Simular datos | Elige un escenario («contrasena incorrecta», «usuario inexistente»...) y la IA recorre el flujo hasta el paso donde falla. |
| Vista de pantalla | Maqueta de la pantalla que ve el usuario en un paso (ui-plan / ui-mock). |
| Analizar logs | Pega los logs de una ejecucion y Traza senala en que paso se rompio. |
| Base de datos | Tablas y columnas que se leen o escriben en cada paso, con el esquema real si conectas tu BD (solo lectura). |
| Simular cambios | Compara un cambio propuesto antes y despues en todos los escenarios. Nunca modifica archivos por si solo. |

## Requisitos

- Eclipse IDE **2024-12 o posterior**, ejecutandose con **Java 17** o superior.
- Para la IA: [GitHub Copilot for Eclipse](https://github.com/microsoft/copilot-eclipse) con sesion iniciada y el chat en **modo Agent**.
  Sin Copilot todo lo que no usa IA sigue funcionando.
- En Windows, el runtime **Microsoft Edge WebView2** (el navegador integrado de SWT lo necesita). En Linux, WebKitGTK.

## Instalacion

Desde un sitio de actualizacion p2 (zip local o URL publicada en GitHub Releases):

1. `Help > Install New Software... > Add... > Archive...`
2. Elige `com.ftejero21.traza.repository-0.1.0-SNAPSHOT.zip` (o pega la URL del sitio p2 publicado).
3. Marca **Traza** (categoria Traza), acepta la licencia (MIT) y reinicia Eclipse.

## Primer uso con IA (GitHub Copilot)

El plugin incluye un servidor MCP local (solo en `127.0.0.1`, protegido con un token) y se **registra solo** en Copilot.

1. Abre el chat de GitHub Copilot en modo **Agent**.
2. Copilot mostrara un aviso rojo en el icono de herramientas MCP del chat: abre el dialogo de aprobacion y **aprueba el
   servidor "Traza: traza"** (tambien en `Preferencias > GitHub Copilot > MCP`). Se aprueba una sola vez: el puerto y el token son
   estables entre arranques. Si regeneras el token o cambia el puerto, hay que aprobarlo otra vez.
3. Opcional: las herramientas de Traza son de solo lectura (`readOnlyHint`); puedes activar en las preferencias de Copilot la
   autoaprobacion de herramientas de solo lectura.
4. En el chat escribe, por ejemplo: `usa Traza para explicar como funciona el login`, o usa los prompts MCP `flujo` y `cambio`.

**Plan B** si la politica de tu organizacion desactiva las contribuciones MCP de plugins: `Preferencias > Traza > Copiar
configuracion MCP para Copilot` y pegala en `Preferencias > GitHub Copilot > MCP`.

### Herramientas MCP

`traza_flow`, `traza_submit_flow`, `traza_change`, `traza_submit_change`, `traza_search`, `traza_read`, `traza_list`,
`traza_get_job`, `traza_submit_job`. Todas son de solo lectura (ninguna escribe archivos ni ejecuta nada; las de entrega solo
actualizan la vista). Prompts: `flujo` y `cambio`.

Las acciones de la vista que en VS Code llamaban al modelo (simular, logs, pantalla, mapa de datos, simular cambios, explicar una
tabla) se convierten en **jobs**: Traza abre el chat de Copilot con un prompt prellenado, Copilot lee la tarea con `traza_get_job` y
entrega el resultado con `traza_submit_job`.

## Comandos

Menu principal **Traza** y Quick Access (`Ctrl+3`):

- Abrir flujo... (archivo JSON, JSON del editor activo o ejemplo; un `*.flow.json` seleccionado se abre directamente)
- Abrir flujo de ejemplo
- Generar flujo y Proponer cambio (abren el chat de Copilot con el prompt enviado)
- Elegir modelo (el modelo se elige en el selector del chat de Copilot)
- Mostrar ultimo flujo generado
- Visualizar flujo (oculto; parametros `flowId` y `mode`)
- Anadir, Contrasena, Probar y Quitar conexion de base de datos

El menu contextual del Project Explorer ofrece **Traza: Abrir flujo** sobre archivos `*.flow.json`, y el del editor de texto **Traza: Generar flujo**.

## Bases de datos

`Preferencias > Traza > Bases de datos`: PostgreSQL, MySQL y MariaDB (drivers JDBC incluidos). Conexiones de **solo lectura** (solo
consultas fijas y parametrizadas a `information_schema` / `pg_catalog`; nunca se leen filas). La contrasena se guarda en el
**almacen seguro de Eclipse**, nunca en las preferencias, y nunca se envia a la vista ni a Copilot. Los servidores remotos exigen
TLS con verificacion del certificado; sin TLS solo se permite `localhost`. «Anadir» detecta datasources de Spring
(`application*.properties`, solo URL y usuario).

## Apariencia

`Preferencias > Traza`: idioma (es/en) y tema. Con `traza` se usa la paleta original; con `ide` se mapean los colores y fuentes de
Eclipse (aproximacion). El cuerpo de la vista recibe la clase `vscode-dark` o `vscode-light` segun el tema de Eclipse.

## Enlaces `traza://`

- `traza://flow?q=<pregunta>&mode=sim|logs|db`: genera un flujo.
- `traza://flow?q=<pregunta>&mode=change&change=<cambio>`: propone un cambio.
- `traza://show?id=<uuid>[&mode=changes]`: reabre un flujo ya generado en esta sesion.

Activa el esquema en `Preferencias > General > Link Handlers`. Los enlaces que usan IA piden siempre una confirmacion modal.

## Privacidad

El codigo relevante del proyecto (rutas y fragmentos que Copilot busca y lee con las herramientas de Traza) se envia **en claro** al
proveedor del modelo de GitHub Copilot, igual que en la version de VS Code. Nunca se envian archivos sensibles (`.env`, claves...) y
los secretos se enmascaran. Traza avisa la primera vez que usas la IA. El servidor MCP escucha solo en loopback y exige
`Authorization: Bearer <token>`; el token (256 bits) se guarda en el almacen seguro de Eclipse y solo sale de tu maquina dentro de
la configuracion que Copilot guarda localmente.

## Diferencias con la extension de VS Code

- No existe el participante `@traza` con `/flujo` y `/cambio`: se sustituye por las herramientas MCP en modo Agent, los prompts MCP
  `flujo` / `cambio` y los comandos que abren el chat.
- No hay seleccion de modelo propia (`traza.model`) ni respuesta en streaming propia: el modelo lo elige Copilot y es su agente quien
  investiga y redacta la respuesta.
- Los botones y anclas del chat se sustituyen por la apertura automatica de la vista y por texto `carpeta/ruta:linea`.
- Las conexiones de base de datos viven en las preferencias de la instancia de Eclipse (no en archivos del proyecto).
- No se porta la migracion de ajustes antiguos.
- El enlace `traza://` requiere activar el esquema en Link Handlers.

## Compilar (build headless)

Se necesita JDK 17+ (se ha probado con el JDK 18) y red la primera vez (Maven 3.9.9, Tycho 4.0.13, metadatos de Eclipse 2024-12 y los
jars de `lib`). No se usa Node en el build de Maven.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-18'; .\mvnw.cmd -B clean verify
```

Artefactos:

- `releng/com.ftejero21.traza.repository/target/com.ftejero21.traza.repository-0.1.0-SNAPSHOT.zip` (sitio p2 en zip)
- `releng/com.ftejero21.traza.repository/target/repository/` (sitio p2)
- `bundles/com.ftejero21.traza/target/com.ftejero21.traza-0.1.0-SNAPSHOT.jar`

### Scripts manuales (Node 18)

- `tools/sync-webview.ps1` / `tools/sync-webview.sh`: compila la webview de `../nexo-vscode` y la copia a
  `bundles/com.ftejero21.traza/webview/`. **Ejecutalo antes de cada release** (la webview commiteada puede estar desactualizada).
- `node tools/export-prompts.mjs ../nexo-vscode`: regenera `resources/ai/prompts.json` y copia `nexo-ia.schemas.json`.

## Pruebas manuales pendientes (requieren Eclipse + GitHub Copilot)

No se pueden verificar sin un Eclipse real con Copilot; conviene probarlas tras instalar:

- Que el language server de Copilot acepte `type: http` con `requestInit.headers` sobre `http://127.0.0.1` sin TLS.
- Que la politica active la contribucion MCP (`isMcpContributionPointEnabled`) y que aparezca el dialogo de aprobacion de "Traza: traza".
- Que `openChatView` con `autoSend` en modo Agent invoque realmente las herramientas.
- El tamano maximo de resultado de herramienta que admite el language server (ajustar los 45 000 / 100 000 caracteres si se truncan).
- Que los prompts MCP `flujo` / `cambio` aparezcan como comandos con barra en el chat.
- Como nombra Copilot las herramientas (los prompts del sistema citan `traza_search/read/list` literalmente).
- Que Edge WebView2 cargue modulos desde 127.0.0.1 y que la `BrowserFunction` este disponible antes del mensaje `ready`.
- Que JDBC funcione en OSGi (ServiceLoader de MariaDB con el context classloader).
- Que el secure storage no pida contrasena maestra en Linux o macOS.
- El registro del esquema `traza://`, los menus contextuales y los colores del tema `ide`.

## Licencia

MIT (ver `LICENSE`). Avisos de terceros: Gson (Apache-2.0), PostgreSQL JDBC (BSD-2-Clause), MariaDB Connector/J (LGPL-2.1, jar sin
modificar) y las dependencias de la webview (`bundles/com.ftejero21.traza/webview/3rdpartylicenses.txt`).
