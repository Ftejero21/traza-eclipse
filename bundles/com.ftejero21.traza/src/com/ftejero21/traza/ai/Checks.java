package com.ftejero21.traza.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.ftejero21.traza.core.J;
import com.ftejero21.traza.core.WorkspaceFiles;
import com.ftejero21.traza.ai.FlowContextCollector;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Una funcion de validacion por operacion (equivalente a los closures `check` de host-ai.ts). Solo comprueba el FORMATO. */
public final class Checks {
	public static final Set<String> FLOW_KEEP_ARRAYS = Set.of("$.lanes", "$.steps");
	public static final Set<String> PROPOSE_KEEP_ARRAYS = Set.of("$.files");
	private static final int MAX_FILE_READ_BYTES = 3 * 1024 * 1024;

	private Checks() {
	}

	/** Resultado de validar: errores y valor (la raiz ya validada u otro valor derivado). */
	public static final class Validation {
		public final List<String> errors;
		public final JsonElement value;

		public Validation(List<String> errors, JsonElement value) {
			this.errors = errors;
			this.value = value;
		}
	}

	/** Valida la entrada de Copilot (objeto, o texto con JSON) con `check`; sustituye el cuerpo de `attempt` de runJson. */
	public static Validation run(JsonElement input, Function<JsonObject, Validation> check) {
		JsonObject root;
		try {
			if (J.isStr(input)) {
				root = Semantics.parseModelJson(input.getAsString());
			} else if (Semantics.isObj(input)) {
				root = input.getAsJsonObject().deepCopy();
			} else {
				return fail("$: la respuesta no es un objeto JSON valido (no es un objeto)");
			}
		} catch (Semantics.ModelJsonException e) {
			return fail("$: la respuesta no es un objeto JSON valido (" + e.getMessage() + ")");
		}
		Validation v = check.apply(root);
		return v.value == null ? new Validation(v.errors, root) : v;
	}

	private static Validation fail(String... errors) {
		List<String> list = new ArrayList<>(List.of(errors));
		return new Validation(list, null);
	}

	private static Validation ok(JsonElement value) {
		return new Validation(new ArrayList<>(), value);
	}

	/* ------------------------------ checks ------------------------------ */

	public static Function<JsonObject, Validation> flow() {
		return root -> {
			SchemaValidator.clampToSchema("FlowDiagram", root, FLOW_KEEP_ARRAYS);
			Semantics.dropInvalidScenario(root);
			List<String> errors = SchemaValidator.validate("FlowDiagram", root);
			return new Validation(errors.isEmpty() ? Semantics.flowSemantics(root) : errors, root);
		};
	}

	/** SIMULATE y LOGS (lineCount = numero de lineas de log; 0 en SIMULATE). */
	public static Function<JsonObject, Validation> diagnosis(boolean simulate, int lineCount) {
		return root -> {
			root.remove("uiRef");
			root.remove("uiRefAfter");
			String definition = simulate ? "SimulationResult" : "LogAnalysisResult";
			SchemaValidator.clampToSchema(definition, root);
			List<String> errors = SchemaValidator.validate(definition, root);
			if (errors.isEmpty()) {
				Semantics.repairDiagnosis(root, lineCount, simulate);
			}
			return new Validation(errors, root);
		};
	}

	public static Function<JsonObject, Validation> uiPlan() {
		return root -> {
			SchemaValidator.clampToSchema("UiPreviewPlan", root);
			Semantics.repairUiPlan(root);
			List<String> errors = SchemaValidator.validate("UiPreviewPlan", root);
			return new Validation(errors.isEmpty() ? Semantics.uiPlanErrors(root) : errors, root);
		};
	}

	public static Function<JsonObject, Validation> uiMock() {
		return raw -> {
			JsonElement wrapped = raw.get("UiMock");
			JsonObject root = raw.size() == 1 && Semantics.isObj(wrapped) ? wrapped.getAsJsonObject().deepCopy() : raw;
			if (root.has("after") && root.get("after").isJsonNull()) {
				root.remove("after");
			}
			SchemaValidator.clampToSchema("UiMock", root);
			Semantics.repairUiMock(root);
			List<String> errors = SchemaValidator.validate("UiMock", root);
			return new Validation(errors.isEmpty() ? Semantics.uiMockErrors(root) : errors, root);
		};
	}

	/** Validacion del FlowDbMap: esquema JSON, reparacion contra el flujo y comprobaciones semanticas. */
	public static Function<JsonObject, Validation> dbMap(JsonObject flow) {
		return raw -> {
			JsonElement wrapped = raw.get("FlowDbMap");
			JsonObject root = raw.size() == 1 && Semantics.isObj(wrapped) ? wrapped.getAsJsonObject().deepCopy() : raw;
			SchemaValidator.clampToSchema("FlowDbMap", root);
			List<String> errors = SchemaValidator.validate("FlowDbMap", root);
			if (!errors.isEmpty()) {
				return new Validation(errors, null);
			}
			Semantics.repairDbMap(root, flow);
			return new Validation(Semantics.dbMapErrors(root, flow), root);
		};
	}

	public static Function<JsonObject, Validation> dbMapSql(JsonObject flow) {
		Function<JsonObject, Validation> base = dbMap(flow);
		return raw -> {
			Validation result = base.apply(raw);
			if (!result.errors.isEmpty()) {
				return result;
			}
			List<String> unresolved = Semantics.dbMapUnresolvedNameErrors(result.value);
			if (!unresolved.isEmpty()) {
				return new Validation(unresolved, null);
			}
			JsonObject map = result.value.getAsJsonObject();
			if (!J.isArr(map.get("tables")) || map.getAsJsonArray("tables").size() == 0 || !J.isArr(map.get("accesses")) || map.getAsJsonArray("accesses").size() == 0) {
				return fail("$.tables/$.accesses: vacío; Traza necesita al menos una tabla y un acceso de un paso del FLUJO: localiza las sentencias SQL o las entidades");
			}
			return result;
		};
	}

	public static Function<JsonObject, Validation> dbExplain(Set<String> allowedPlaces, Set<String> targetColumns) {
		return root -> {
			JsonObject parsed = PromptBuilders.parseDbExplain(root, allowedPlaces, targetColumns);
			return parsed.getAsJsonArray("steps").size() > 0 ? ok(parsed) : fail("$.steps: sin pasos validos (title o text)");
		};
	}

	/**
	 * ChangeProposal (port de proposeCheck). El valor es {answer} (aclaracion) o {change} (propuesta validada y sin
	 * completar: id, request y el resto lo pone el llamador).
	 */
	public static Function<JsonObject, Validation> propose(String question, Set<String> mountIds, boolean multi) {
		return raw -> {
			JsonObject root = raw;
			JsonElement files = root.get("files");
			if (J.isNull(files)) {
				for (String key : new String[] { "change", "proposal", "changeProposal", "ChangeProposal" }) {
					JsonElement inner = root.get(key);
					if (Semantics.isObj(inner) && inner.getAsJsonObject().has("files")) {
						root = inner.getAsJsonObject();
						files = root.get("files");
						break;
					}
				}
			}
			if (J.isNull(files)) {
				String answer = Semantics.txt(root.get("answer")).trim();
				if (!answer.isEmpty()) {
					JsonObject v = new JsonObject();
					v.addProperty("answer", Semantics.cut(answer, 20_000));
					return ok(v);
				}
				return fail("$: falta \"files\" en la raiz del JSON (cambio propuesto, sin envolverlo en otra clave) o \"answer\" (aclaracion)");
			}
			JsonObject change = root.deepCopy();
			change.remove("answer");
			change.addProperty("id", java.util.UUID.randomUUID().toString());
			change.addProperty("request", Semantics.cut(question, Msg.MAX_QUESTION_CHARS));
			SchemaValidator.clampToSchema("ChangeProposal", change, PROPOSE_KEEP_ARRAYS);
			List<String> errors = SchemaValidator.validate("ChangeProposal", change);
			if (errors.isEmpty()) {
				errors = Semantics.proposeSemantics(change, mountIds);
			}
			if (errors.isEmpty()) {
				errors = privateFileErrors(change, multi);
			}
			if (!errors.isEmpty()) {
				return new Validation(errors, null);
			}
			JsonObject v = new JsonObject();
			v.add("change", change);
			return ok(v);
		};
	}

	/** Seguridad, no contenido: rechaza archivos con claves privadas. */
	public static List<String> privateFileErrors(JsonObject change, boolean multi) {
		List<String> errors = new ArrayList<>();
		com.google.gson.JsonArray files = J.arr(change.get("files"));
		for (int i = 0; i < files.size(); i++) {
			JsonObject file = files.get(i).getAsJsonObject();
			String mountId = Semantics.txt(file.get("mountId")).trim();
			String path = Semantics.txt(file.get("path")).trim().replace('\\', '/').replaceFirst("^\\./", "");
			if (multi && mountId.isEmpty()) {
				int slash = path.indexOf('/');
				if (slash > 0) {
					mountId = path.substring(0, slash);
					path = path.substring(slash + 1);
				}
			}
			String original = WorkspaceFiles.readMountFile(mountId, path, "text", MAX_FILE_READ_BYTES);
			if (original != null && FlowContextCollector.PRIVATE_KEY.matcher(original).find()) {
				errors.add("$.files[" + i + "]: archivo no permitido");
			}
		}
		return errors.size() > 20 ? new ArrayList<>(errors.subList(0, 20)) : errors;
	}
}
