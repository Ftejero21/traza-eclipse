package com.ftejero21.traza.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ftejero21.traza.TrazaPlugin;
import com.google.gson.JsonObject;

/** Almacen unico (en memoria, thread-safe) de los flujos generados, sus propuestas y mapas de datos. */
public final class FlowStore {
	private static final int MAX_RECENT_FLOWS = 10;
	private static final int MAX_PROPOSALS = 10;

	/** Flujo generado con la IA (port de GeneratedFlow). */
	public static final class GeneratedFlow {
		public final JsonObject flow;
		public final String title;
		public final String model;
		public final boolean scenarioAsked;
		public final String folder;

		public GeneratedFlow(JsonObject flow, String title, String model, boolean scenarioAsked, String folder) {
			this.flow = flow;
			this.title = title;
			this.model = model;
			this.scenarioAsked = scenarioAsked;
			this.folder = folder;
		}
	}

	private static GeneratedFlow last;
	private static final Map<String, GeneratedFlow> RECENT = new LinkedHashMap<>();
	private static final Map<String, List<JsonObject>> PROPOSALS = new LinkedHashMap<>();

	private FlowStore() {
	}

	/** Guarda el flujo como ultimo generado y entre los recientes (maximo 10). */
	public static synchronized void store(GeneratedFlow generated) {
		last = generated;
		JsonObject flow = generated.flow;
		if (!J.isStr(flow.get("id"))) {
			return;
		}
		String id = flow.get("id").getAsString();
		RECENT.remove(id);
		RECENT.put(id, generated);
		while (RECENT.size() > MAX_RECENT_FLOWS) {
			Iterator<String> it = RECENT.keySet().iterator();
			String oldest = it.next();
			it.remove();
			PROPOSALS.remove(oldest);
		}
	}

	public static synchronized GeneratedFlow last() {
		return last;
	}

	public static synchronized GeneratedFlow flowById(String id) {
		return RECENT.get(id);
	}

	/** Guarda una propuesta asociada a un flujo reciente (max. 10 por flujo; descarta las mas antiguas). */
	public static synchronized void rememberProposal(String flowId, JsonObject proposal) {
		if (!RECENT.containsKey(flowId)) {
			return;
		}
		List<JsonObject> list = new ArrayList<>(PROPOSALS.getOrDefault(flowId, new ArrayList<>()));
		list.add(proposal);
		while (list.size() > MAX_PROPOSALS) {
			list.remove(0);
		}
		PROPOSALS.put(flowId, list);
	}

	/** Guarda el mapa de datos generado en el flujo reciente, para reabrirlo sin regenerarlo. */
	public static synchronized void rememberDbMap(String flowId, JsonObject dbMap) {
		GeneratedFlow g = RECENT.get(flowId);
		if (g == null) {
			return;
		}
		g.flow.add("dbMap", dbMap);
		TrazaPlugin.info("DB_MAP · flujo guardado en la sesión");
	}

	public static synchronized List<JsonObject> proposalsFor(String flowId) {
		return new ArrayList<>(PROPOSALS.getOrDefault(flowId, new ArrayList<>()));
	}
}
