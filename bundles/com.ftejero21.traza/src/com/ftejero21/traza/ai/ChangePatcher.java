package com.ftejero21.traza.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port de ai/change-patcher.ts: aplica en memoria un cambio propuesto sobre las ventanas de codigo de «Simular cambios»
 * (nunca escribe en disco).
 */
public final class ChangePatcher {
	public static final int MAX_WINDOW = 8000;
	public static final int MAX_WINDOWS = 24;

	private ChangePatcher() {
	}

	public static final class CodeWindow {
		public String path;
		public Integer startLine;
		public Integer endLine;
		public String content;

		public CodeWindow(String path, Integer startLine, Integer endLine, String content) {
			this.path = path;
			this.startLine = startLine;
			this.endLine = endLine;
			this.content = content;
		}
	}

	public static final class ChangeLine {
		public String kind;
		public String text;

		public ChangeLine(String kind, String text) {
			this.kind = kind;
			this.text = text;
		}
	}

	public static final class ChangeHunk {
		public int oldStart;
		public int newStart;
		public List<ChangeLine> lines = new ArrayList<>();
	}

	public static final class ChangeFile {
		public String path;
		public String status;
		public List<ChangeHunk> hunks = new ArrayList<>();
	}

	/** El diff no encaja con las ventanas o el resultado no cabe. El mensaje es un codigo corto. */
	public static final class PatchError extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public PatchError(String reason) {
			super(reason);
		}
	}

	private static final class Placement {
		ChangeHunk hunk;
		int oldStart;
		int oldCount;
		int delta;
	}

	private static int oldEnd(Placement p) {
		return p.oldCount > 0 ? p.oldStart + p.oldCount - 1 : p.oldStart;
	}

	private static int startLine(CodeWindow w) {
		return w.startLine == null || w.startLine < 1 ? 1 : w.startLine;
	}

	private static String[] lines(String content) {
		return content.split("\n", -1);
	}

	private static int windowOf(List<CodeWindow> windows, String path, Placement placement) {
		for (int i = 0; i < windows.size(); i++) {
			CodeWindow w = windows.get(i);
			if (!path.equals(w.path) || w.content == null) {
				continue;
			}
			int start = startLine(w);
			int end = start + lines(w.content).length - 1;
			boolean fits = placement.oldCount > 0
				? start <= placement.oldStart && oldEnd(placement) <= end
				: start - 1 <= placement.oldStart && placement.oldStart <= end;
			if (fits) {
				return i;
			}
		}
		return -1;
	}

	private static String trimEnd(String s) {
		return s.stripTrailing();
	}

	private static void checkContext(CodeWindow w, Placement placement) {
		String[] ls = lines(w.content);
		int offset = placement.oldStart - startLine(w);
		int k = 0;
		for (ChangeLine line : placement.hunk.lines) {
			if (line.kind.equals("add")) {
				continue;
			}
			int index = offset + k;
			if (index < 0 || index >= ls.length || !trimEnd(ls[index]).equals(trimEnd(line.text))) {
				throw new PatchError("context-mismatch");
			}
			k++;
		}
	}

	private static int deltaBefore(List<Placement> all, List<Placement> inside, int windowStart) {
		if (all == null) {
			return 0;
		}
		int delta = 0;
		for (Placement p : all) {
			if (!inside.contains(p) && oldEnd(p) < windowStart) {
				delta += p.delta;
			}
		}
		return delta;
	}

	private static int length(List<String> lines, int from, int to) {
		if (to < from) {
			return 0;
		}
		int total = to - from;
		for (int i = from; i <= to; i++) {
			total += lines.get(i).length();
		}
		return total;
	}

	private static String at(String[] source, int i) {
		return i >= 0 && i < source.length ? source[i] : "";
	}

	private static CodeWindow patchWindow(CodeWindow w, List<Placement> inside, List<Placement> all) {
		int start = startLine(w);
		String[] source = lines(w.content);
		List<Placement> sorted = new ArrayList<>(inside);
		sorted.sort(Comparator.<Placement>comparingInt(p -> p.oldStart).thenComparingInt(p -> p.oldCount));
		List<String> result = new ArrayList<>();
		int cursor = 0;
		int changedFrom = -1;
		int changedTo = -1;
		for (Placement placement : sorted) {
			int at = placement.oldCount > 0 ? placement.oldStart - start : placement.oldStart - start + 1;
			if (at < cursor) {
				throw new PatchError("overlap");
			}
			while (cursor < at) {
				result.add(at(source, cursor++));
			}
			if (changedFrom < 0) {
				changedFrom = result.size();
			}
			for (ChangeLine line : placement.hunk.lines) {
				if (line.kind.equals("context")) {
					result.add(at(source, cursor++));
				} else if (line.kind.equals("del")) {
					cursor++;
				} else {
					result.add(line.text);
				}
			}
			changedTo = result.size() - 1;
		}
		while (cursor < source.length) {
			result.add(source[cursor++]);
		}
		int newStart = start + deltaBefore(all, inside, start);
		int from = 0;
		int to = result.size() - 1;
		boolean trimEnd = true;
		while (length(result, from, to) > MAX_WINDOW) {
			boolean canEnd = to > Math.max(changedTo, from);
			boolean canStart = from < Math.min(changedFrom, to);
			if (!canEnd && !canStart) {
				throw new PatchError("window-too-large");
			}
			if ((trimEnd && canEnd) || !canStart) {
				to--;
			} else {
				from++;
			}
			trimEnd = !trimEnd;
		}
		String content = String.join("\n", result.subList(from, to + 1));
		int first = newStart + from;
		return new CodeWindow(w.path, first, first + (to - from), content);
	}

	/** Ventanas «despues» de aplicar `files` sobre `code`. Lanza PatchError si no encaja. */
	public static List<CodeWindow> afterWindows(List<CodeWindow> code, List<ChangeFile> files) {
		Map<String, ChangeFile> byPath = new LinkedHashMap<>();
		for (ChangeFile file : files) {
			if (byPath.containsKey(file.path)) {
				throw new PatchError("duplicate-path");
			}
			byPath.put(file.path, file);
		}
		List<CodeWindow> windows = code == null ? new ArrayList<>() : code;
		Map<Integer, List<Placement>> byWindow = new HashMap<>();
		Map<String, List<Placement>> byFile = new HashMap<>();
		for (ChangeFile file : byPath.values()) {
			if (!"modified".equals(file.status)) {
				continue;
			}
			List<Placement> all = new ArrayList<>();
			for (ChangeHunk hunk : file.hunks) {
				int oldCount = 0;
				int adds = 0;
				int dels = 0;
				for (ChangeLine line : hunk.lines) {
					if (line.kind.equals("add")) {
						adds++;
					} else {
						oldCount++;
					}
					if (line.kind.equals("del")) {
						dels++;
					}
				}
				Placement placement = new Placement();
				placement.hunk = hunk;
				placement.oldStart = hunk.oldStart;
				placement.oldCount = oldCount;
				placement.delta = adds - dels;
				int index = windowOf(windows, file.path, placement);
				if (index < 0) {
					throw new PatchError("hunk-outside-window");
				}
				checkContext(windows.get(index), placement);
				byWindow.computeIfAbsent(index, k -> new ArrayList<>()).add(placement);
				all.add(placement);
			}
			byFile.put(file.path, all);
		}

		List<CodeWindow> out = new ArrayList<>();
		for (int i = 0; i < windows.size(); i++) {
			CodeWindow w = windows.get(i);
			ChangeFile file = byPath.get(w.path);
			if (file == null) {
				out.add(w);
				continue;
			}
			if ("deleted".equals(file.status)) {
				continue;
			}
			List<Placement> inside = byWindow.get(i);
			if (inside == null || inside.isEmpty()) {
				int start = startLine(w) + deltaBefore(byFile.get(w.path), new ArrayList<>(), startLine(w));
				String[] ls = lines(w.content);
				out.add(new CodeWindow(w.path, start, start + ls.length - 1, w.content));
				continue;
			}
			out.add(patchWindow(w, inside, byFile.get(w.path)));
		}

		for (ChangeFile file : byPath.values()) {
			if (!"added".equals(file.status)) {
				continue;
			}
			List<String> ls = new ArrayList<>();
			for (ChangeHunk hunk : file.hunks) {
				for (ChangeLine line : hunk.lines) {
					ls.add(line.text);
				}
			}
			int start = 1;
			StringBuilder chunk = new StringBuilder();
			int count = 0;
			for (String line : ls) {
				int extra = (count > 0 ? 1 : 0) + line.length();
				if (count > 0 && chunk.length() + extra > MAX_WINDOW) {
					out.add(new CodeWindow(file.path, start, start + count - 1, chunk.toString()));
					start += count;
					chunk = new StringBuilder();
					count = 0;
				}
				if (count > 0) {
					chunk.append('\n');
				}
				chunk.append(line);
				count++;
			}
			if (count > 0) {
				out.add(new CodeWindow(file.path, start, start + count - 1, chunk.toString()));
			}
		}
		if (out.size() > MAX_WINDOWS) {
			throw new PatchError("too-many-windows");
		}
		return out;
	}
}
