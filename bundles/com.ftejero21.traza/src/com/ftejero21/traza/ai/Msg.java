package com.ftejero21.traza.ai;

import java.util.Map;

import com.ftejero21.traza.prefs.TrazaPreferences;

/** Mensajes es/en del host de IA (port de MESSAGES y t() de host-ai.ts, adaptados a Eclipse y GitHub Copilot). */
public final class Msg {
	public static final int MAX_QUESTION_CHARS = 4000;

	private static final Map<String, String> ES = Map.ofEntries(
		Map.entry("blocked", "El proveedor ha bloqueado la petición (límite o política). Inténtalo más tarde."),
		Map.entry("failed", "El modelo no pudo completar la petición."),
		Map.entry("timeout", "El modelo no respondió a tiempo."),
		Map.entry("schema", "La respuesta del modelo no tiene el formato esperado."),
		Map.entry("tooLarge", "La respuesta del modelo es demasiado grande."),
		Map.entry("invalid", "El contexto de la petición no es válido."),
		Map.entry("uiTooLarge", "El contexto de la pantalla es demasiado grande."),
		Map.entry("question", "La pregunta es obligatoria y no puede superar " + MAX_QUESTION_CHARS + " caracteres."),
		Map.entry("noFolders", "Abre una carpeta del proyecto para generar el flujo."),
		Map.entry("dbTooLong", "El contexto de la tabla es demasiado largo para generar la explicación."),
		Map.entry("dbInvalid", "El contexto de la tabla no es válido."),
		Map.entry("dbBadOutput", "La IA no devolvió pasos válidos."),
		Map.entry("needCopilot", "Esta acción necesita GitHub Copilot for Eclipse con el servidor MCP de Traza aprobado."),
		Map.entry("cancelled", "Operación cancelada."),
		Map.entry("copilotHint", "Copilot no ha llamado a la herramienta de Traza. Aprueba el servidor \"Traza: traza\" en el icono de herramientas del chat de GitHub Copilot y usa el modo Agent."),
		Map.entry("sentToChat", "Enviado al chat de GitHub Copilot…"),
		Map.entry("copilotWorking", "Copilot está trabajando (tarea {i} de {n})…"),
		Map.entry("notice", "Traza usa GitHub Copilot: el código relevante del proyecto que Copilot busca y lee con las herramientas de Traza (solo lectura, sin archivos sensibles y con los secretos enmascarados) se envía al proveedor del modelo de Copilot."));

	private static final Map<String, String> EN = Map.ofEntries(
		Map.entry("blocked", "The provider blocked the request (quota or policy). Please try again later."),
		Map.entry("failed", "The model could not complete the request."),
		Map.entry("timeout", "The model did not respond in time."),
		Map.entry("schema", "The model response does not have the expected format."),
		Map.entry("tooLarge", "The model response is too large."),
		Map.entry("invalid", "The request context is not valid."),
		Map.entry("uiTooLarge", "The screen context is too large."),
		Map.entry("question", "The question is required and cannot exceed " + MAX_QUESTION_CHARS + " characters."),
		Map.entry("noFolders", "Open a project folder to generate the flow."),
		Map.entry("dbTooLong", "The table context is too long to generate the explanation."),
		Map.entry("dbInvalid", "The table context is not valid."),
		Map.entry("dbBadOutput", "The AI did not return valid steps."),
		Map.entry("needCopilot", "This action needs GitHub Copilot for Eclipse with the Traza MCP server approved."),
		Map.entry("cancelled", "Operation cancelled."),
		Map.entry("copilotHint", "Copilot did not call the Traza tool. Approve the \"Traza: traza\" server in the tools icon of the GitHub Copilot chat and use Agent mode."),
		Map.entry("sentToChat", "Sent to the GitHub Copilot chat…"),
		Map.entry("copilotWorking", "Copilot is working (task {i} of {n})…"),
		Map.entry("notice", "Traza uses GitHub Copilot: the relevant project code that Copilot searches and reads with the Traza tools (read-only, without sensitive files and with masked secrets) is sent to the provider of the Copilot model."));

	private Msg() {
	}

	public static String language() {
		return TrazaPreferences.language();
	}

	public static String t(String key) {
		return t(key, language());
	}

	public static String t(String key, String lang) {
		Map<String, String> m = "en".equals(lang) ? EN : ES;
		String v = m.get(key);
		return v == null ? key : v;
	}
}
