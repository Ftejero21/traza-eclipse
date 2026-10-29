# Changelog

## 0.1.1

- Modo **Traza** en el selector del chat de GitHub Copilot for Eclipse (sustituye al participante `@traza` de VS Code): escribes la
  peticion y Copilot genera el flujo o propone un cambio con las herramientas MCP de Traza.
- Archivo de agente gestionado `.github/agents/Traza.agent.md`, escrito automaticamente en un solo proyecto del workspace y excluido
  de Git (`.git/info/exclude`). Nunca sobrescribe ni borra un archivo modificado por el usuario.
- Nueva preferencia `Preferencias > Traza` para activar o desactivar el modo (activado por defecto; al desactivarlo se borran los
  archivos gestionados sin modificar).
- «Generar flujo» y «Proponer cambio» abren el chat de Copilot en modo Traza (con modo Agent como alternativa).

## 0.1.0

Primera version: port a Eclipse IDE (2024-12 o posterior, Java 17) de la extension de VS Code Traza 0.1.4.

- Vista «Traza: Flujo» con la webview Angular original en un SWT Browser, servida por un servidor HTTP local en loopback.
- Modos: Recorrido, Simular datos, Vista de pantalla, Analizar logs, Base de datos y Simular cambios.
- IA mediante GitHub Copilot for Eclipse: servidor MCP en Java (streamable HTTP en 127.0.0.1, con token) registrado automaticamente
  por el punto de extension `com.microsoft.copilot.eclipse.ui.mcpRegistration`, sin dependencia con Copilot.
- Herramientas MCP `traza_flow`, `traza_submit_flow`, `traza_change`, `traza_submit_change`, `traza_search`, `traza_read`,
  `traza_list`, `traza_get_job`, `traza_submit_job` y prompts `flujo` y `cambio`.
- Metadatos de base de datos (PostgreSQL, MySQL, MariaDB) en solo lectura con drivers JDBC incluidos; contrasenas en el almacen
  seguro de Eclipse.
- Comandos, menu «Traza», menus contextuales, paginas de preferencias y enlaces `traza://`.
- Sitio de actualizacion p2 generado con Maven Tycho.
