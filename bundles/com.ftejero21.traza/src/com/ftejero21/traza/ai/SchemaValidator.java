package com.ftejero21.traza.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ftejero21.traza.TrazaPlugin;
import com.ftejero21.traza.core.BundleFiles;
import com.ftejero21.traza.core.J;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Port de ai/schema-validator.ts: validador JSON Schema minimo para la salida estructurada de la IA (subconjunto usado
 * por nexo-ia.schemas.json). Como mucho 20 errores con ruta. `clampToSchema` aplica el recorte mecanico previo.
 */
public final class SchemaValidator {
	private static final int MAX_ERRORS = 20;
	private static final int MAX_DEPTH = 64;
	private static JsonObject root;
	private static JsonObject defs;

	private SchemaValidator() {
	}

	private static synchronized void load() {
		if (root != null) {
			return;
		}
		try {
			root = J.parse(BundleFiles.readText("resources/ai/nexo-ia.schemas.json")).getAsJsonObject();
		} catch (Exception e) {
			TrazaPlugin.error("No se pudo cargar el esquema de la IA.", e);
			root = new JsonObject();
		}
		JsonObject d = J.obj(root.get("$defs"));
		defs = d != null ? d : new JsonObject();
	}

	private static JsonObject definitionNode(String definition) {
		load();
		JsonObject top = J.obj(root.get(definition));
		if (top != null && !definition.equals("$defs")) {
			return top;
		}
		return J.obj(defs.get(definition));
	}

	private static JsonObject resolve(String ref) {
		load();
		if (!ref.startsWith("#/$defs/")) {
			return null;
		}
		return J.obj(defs.get(ref.substring("#/$defs/".length())));
	}

	private static String typeName(JsonElement v) {
		if (v == null || v.isJsonNull()) {
			return "null";
		}
		if (v.isJsonArray()) {
			return "array";
		}
		if (v.isJsonObject()) {
			return "object";
		}
		JsonPrimitive p = v.getAsJsonPrimitive();
		if (p.isString()) {
			return "string";
		}
		if (p.isNumber()) {
			return "number";
		}
		return p.isBoolean() ? "boolean" : "unknown";
	}

	private static boolean matchesSingle(String type, JsonElement v) {
		switch (type) {
		case "object":
			return v != null && v.isJsonObject();
		case "array":
			return v != null && v.isJsonArray();
		case "string":
			return J.isStr(v);
		case "integer":
			return J.isNum(v) && J.integer(v) != null;
		case "number":
			return J.num(v) != null;
		case "boolean":
			return J.isBool(v);
		case "null":
			return v == null || v.isJsonNull();
		default:
			return true;
		}
	}

	private static boolean matchesType(JsonElement type, JsonElement v) {
		if (type != null && type.isJsonArray()) {
			for (JsonElement t : type.getAsJsonArray()) {
				if (matchesSingle(J.isStr(t) ? t.getAsString() : t.toString(), v)) {
					return true;
				}
			}
			return false;
		}
		return matchesSingle(type == null ? "undefined" : (J.isStr(type) ? type.getAsString() : type.toString()), v);
	}

	private static void add(List<String> errors, String message) {
		if (errors.size() < MAX_ERRORS) {
			errors.add(message);
		}
	}

	private static Double number(JsonElement e) {
		return J.isNum(e) ? e.getAsDouble() : null;
	}

	private static String numText(JsonElement e) {
		return Semantics.txt(e);
	}

	private static void check(JsonElement schemaEl, JsonElement node, String path, List<String> errors, int depth) {
		if (errors.size() >= MAX_ERRORS || !Semantics.isObj(schemaEl) || schemaEl.getAsJsonObject().size() == 0) {
			return;
		}
		JsonObject schema = schemaEl.getAsJsonObject();
		if (depth > MAX_DEPTH) {
			add(errors, path + ": demasiado anidado");
			return;
		}
		JsonElement value = node == null ? JsonNull.INSTANCE : node;
		JsonElement ref = schema.get("$ref");
		if (J.isStr(ref)) {
			JsonObject target = resolve(ref.getAsString());
			if (target == null) {
				add(errors, path + ": $ref desconocida " + ref.getAsString());
			} else {
				check(target, value, path, errors, depth + 1);
			}
		}
		JsonElement allOf = schema.get("allOf");
		if (J.isArr(allOf)) {
			for (JsonElement sub : allOf.getAsJsonArray()) {
				check(sub, value, path, errors, depth + 1);
			}
		}
		JsonElement oneOf = schema.get("oneOf");
		if (J.isArr(oneOf)) {
			int matches = 0;
			List<String> best = null;
			for (JsonElement sub : oneOf.getAsJsonArray()) {
				List<String> branch = new ArrayList<>();
				check(sub, value, path, branch, depth + 1);
				if (branch.isEmpty()) {
					matches++;
				} else if (best == null || branch.size() < best.size()) {
					best = branch;
				}
			}
			if (matches == 0 && best != null) {
				for (String e : best) {
					add(errors, e);
				}
			} else if (matches > 1) {
				add(errors, path + ": oneOf coincide con " + matches + " opciones");
			}
		}
		JsonElement type = schema.get("type");
		if (type != null && !matchesType(type, value)) {
			add(errors, path + ": type " + (type.isJsonArray() ? J.GSON.toJson(type) : Semantics.txt(type)) + " (es " + typeName(value) + ")");
			return;
		}
		JsonElement enumNode = schema.get("enum");
		if (J.isArr(enumNode)) {
			boolean found = false;
			for (JsonElement option : enumNode.getAsJsonArray()) {
				if (option.equals(value)) {
					found = true;
					break;
				}
			}
			if (!found) {
				add(errors, path + ": enum " + J.GSON.toJson(enumNode));
			}
		}
		if (J.isStr(value)) {
			String s = value.getAsString();
			int length = s.codePointCount(0, s.length());
			Double min = number(schema.get("minLength"));
			Double max = number(schema.get("maxLength"));
			if (min != null && length < min) {
				add(errors, path + ": minLength " + numText(schema.get("minLength")));
			}
			if (max != null && length > max) {
				add(errors, path + ": maxLength " + numText(schema.get("maxLength")));
			}
		}
		if (J.isNum(value)) {
			double v = value.getAsDouble();
			Double min = number(schema.get("minimum"));
			Double max = number(schema.get("maximum"));
			if (min != null && v < min) {
				add(errors, path + ": minimum " + numText(schema.get("minimum")));
			}
			if (max != null && v > max) {
				add(errors, path + ": maximum " + numText(schema.get("maximum")));
			}
		}
		if (value.isJsonArray()) {
			JsonArray arr = value.getAsJsonArray();
			Double min = number(schema.get("minItems"));
			Double max = number(schema.get("maxItems"));
			if (min != null && arr.size() < min) {
				add(errors, path + ": minItems " + numText(schema.get("minItems")));
			}
			if (max != null && arr.size() > max) {
				add(errors, path + ": maxItems " + numText(schema.get("maxItems")));
			}
			JsonElement items = schema.get("items");
			if (Semantics.isObj(items)) {
				for (int i = 0; i < arr.size() && errors.size() < MAX_ERRORS; i++) {
					check(items, arr.get(i), path + "[" + i + "]", errors, depth + 1);
				}
			}
		}
		if (value.isJsonObject()) {
			JsonObject obj = value.getAsJsonObject();
			JsonElement required = schema.get("required");
			if (J.isArr(required)) {
				for (JsonElement field : required.getAsJsonArray()) {
					String f = Semantics.txt(field);
					if (!obj.has(f)) {
						add(errors, path + "." + f + ": required");
					}
				}
			}
			JsonObject properties = J.obj(schema.get("properties"));
			if (properties != null) {
				for (Map.Entry<String, JsonElement> e : properties.entrySet()) {
					if (errors.size() >= MAX_ERRORS) {
						break;
					}
					if (obj.has(e.getKey())) {
						check(e.getValue(), obj.get(e.getKey()), path + "." + e.getKey(), errors, depth + 1);
					}
				}
			}
			JsonElement ap = schema.get("additionalProperties");
			if (J.isBool(ap) && !ap.getAsBoolean()) {
				for (String name : obj.keySet()) {
					if (properties == null || !properties.has(name)) {
						add(errors, path + "." + name + ": additionalProperties false");
					}
				}
			}
		}
	}

	/** Errores de validacion del valor contra la definicion indicada (FlowDiagram, SimulationResult...). */
	public static List<String> validate(String definition, JsonElement value) {
		List<String> errors = new ArrayList<>();
		JsonObject schema = definitionNode(definition);
		if (schema == null) {
			errors.add("$: esquema desconocido " + definition);
			return errors;
		}
		check(schema, value, "$", errors, 0);
		return errors;
	}

	private static JsonElement branchType(JsonElement branch) {
		if (!Semantics.isObj(branch)) {
			return null;
		}
		JsonObject b = branch.getAsJsonObject();
		if (b.has("type")) {
			return b.get("type");
		}
		JsonElement ref = b.get("$ref");
		JsonObject target = J.isStr(ref) ? resolve(ref.getAsString()) : null;
		return target != null ? target.get("type") : null;
	}

	private static JsonElement clampNode(JsonElement schemaEl, JsonElement node, String path, Set<String> keep, int depth) {
		if (!Semantics.isObj(schemaEl) || schemaEl.getAsJsonObject().size() == 0 || depth > MAX_DEPTH) {
			return node;
		}
		JsonObject schema = schemaEl.getAsJsonObject();
		JsonElement value = node;
		JsonElement ref = schema.get("$ref");
		if (J.isStr(ref)) {
			JsonObject target = resolve(ref.getAsString());
			if (target != null) {
				value = clampNode(target, value, path, keep, depth + 1);
			}
		}
		JsonElement allOf = schema.get("allOf");
		if (J.isArr(allOf)) {
			for (JsonElement sub : allOf.getAsJsonArray()) {
				value = clampNode(sub, value, path, keep, depth + 1);
			}
		}
		JsonElement oneOf = schema.get("oneOf");
		if (J.isArr(oneOf)) {
			List<JsonElement> candidates = new ArrayList<>();
			for (JsonElement sub : oneOf.getAsJsonArray()) {
				JsonElement t = branchType(sub);
				if (t != null && matchesType(t, value)) {
					candidates.add(sub);
				}
			}
			if (candidates.size() == 1) {
				value = clampNode(candidates.get(0), value, path, keep, depth + 1);
			}
		}
		if (J.isStr(value)) {
			Double max = number(schema.get("maxLength"));
			String s = value.getAsString();
			if (max != null && s.codePointCount(0, s.length()) > max) {
				int end = s.offsetByCodePoints(0, (int) Math.max(0, max.intValue()));
				value = new JsonPrimitive(s.substring(0, end));
			}
		} else if (J.num(value) != null) {
			Double min = number(schema.get("minimum"));
			Double max = number(schema.get("maximum"));
			double n = value.getAsDouble();
			if (min != null && n < min) {
				value = new JsonPrimitive(schema.get("minimum").getAsNumber());
			}
			if (max != null && value.getAsDouble() > max) {
				value = new JsonPrimitive(schema.get("maximum").getAsNumber());
			}
		} else if (value != null && value.isJsonArray()) {
			Double max = number(schema.get("maxItems"));
			JsonArray arr = value.getAsJsonArray();
			if (max != null && arr.size() > max && !keep.contains(path)) {
				JsonArray cut = new JsonArray();
				for (int i = 0; i < max.intValue() && i < arr.size(); i++) {
					cut.add(arr.get(i));
				}
				arr = cut;
			}
			JsonElement items = schema.get("items");
			if (Semantics.isObj(items)) {
				JsonArray mapped = new JsonArray();
				for (int i = 0; i < arr.size(); i++) {
					mapped.add(clampNode(items, arr.get(i), path + "[" + i + "]", keep, depth + 1));
				}
				arr = mapped;
			}
			value = arr;
		} else if (value != null && value.isJsonObject()) {
			JsonObject properties = J.obj(schema.get("properties"));
			JsonObject obj = value.getAsJsonObject();
			JsonElement ap = schema.get("additionalProperties");
			if (properties != null && J.isBool(ap) && !ap.getAsBoolean()) {
				for (String key : new ArrayList<>(obj.keySet())) {
					if (!properties.has(key)) {
						obj.remove(key);
					}
				}
			}
			if (properties != null) {
				for (Map.Entry<String, JsonElement> e : properties.entrySet()) {
					if (obj.has(e.getKey())) {
						obj.add(e.getKey(), clampNode(e.getValue(), obj.get(e.getKey()), path + "." + e.getKey(), keep, depth + 1));
					}
				}
			}
		}
		return value;
	}

	/** Valida un valor contra un esquema suelto (por ejemplo el inputSchema de una herramienta MCP). */
	public static List<String> validateWith(JsonObject schema, JsonElement value) {
		List<String> errors = new ArrayList<>();
		check(schema, value, "$", errors, 0);
		return errors;
	}

	/** Recorte mecanico previo a validate (modifica el valor en sitio). */
	public static void clampToSchema(String definition, JsonElement value, Set<String> keepArrays) {
		JsonObject schema = definitionNode(definition);
		if (schema == null) {
			return;
		}
		clampNode(schema, value, "$", keepArrays, 0);
	}

	public static void clampToSchema(String definition, JsonElement value) {
		clampToSchema(definition, value, Set.of());
	}
}
