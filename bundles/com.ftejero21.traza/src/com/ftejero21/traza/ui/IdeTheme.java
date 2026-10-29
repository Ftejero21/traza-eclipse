package com.ftejero21.traza.ui;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AbstractTextEditor;

import com.ftejero21.traza.TrazaPlugin;

/** Colores y fuentes de Eclipse mapeados a las variables CSS --vscode-* que usa la webview (aproximacion). */
public final class IdeTheme {
	public final boolean dark;
	public final String css;

	private IdeTheme(boolean dark, String css) {
		this.dark = dark;
		this.css = css;
	}

	/** Calcula el tema actual. Debe llamarse desde el hilo UI (usa Ui.sync si no se esta en el). */
	public static IdeTheme current() {
		IdeTheme t = Ui.sync(IdeTheme::compute);
		return t != null ? t : new IdeTheme(true, "");
	}

	private static RGB sys(Display d, int id) {
		return d.getSystemColor(id).getRGB();
	}

	private static RGB editorColor(Display d, String key, String systemDefaultKey, int systemId) {
		try {
			IPreferenceStore store = EditorsUI.getPreferenceStore();
			if (store != null && !store.getBoolean(systemDefaultKey) && store.contains(key)) {
				return PreferenceConverter.getColor(store, key);
			}
		} catch (RuntimeException e) {
			TrazaPlugin.warn("No se pudo leer el color del editor (" + e.getClass().getSimpleName() + ").");
		}
		return sys(d, systemId);
	}

	private static double luminance(RGB c) {
		return (0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue) / 255.0;
	}

	private static RGB mix(RGB a, RGB b, double t) {
		return new RGB((int) Math.round(a.red + (b.red - a.red) * t), (int) Math.round(a.green + (b.green - a.green) * t),
			(int) Math.round(a.blue + (b.blue - a.blue) * t));
	}

	private static String hex(RGB c) {
		return String.format("#%02x%02x%02x", c.red, c.green, c.blue);
	}

	private static String fontFamily(Font font, String generic) {
		if (font != null && !font.isDisposed()) {
			FontData[] data = font.getFontData();
			if (data.length > 0 && data[0].getName() != null && !data[0].getName().isEmpty()) {
				return "\"" + data[0].getName().replace("\"", "") + "\", " + generic;
			}
		}
		return generic;
	}

	private static IdeTheme compute() {
		Display d = Display.getCurrent();
		if (d == null) {
			return new IdeTheme(true, "");
		}
		RGB bg = editorColor(d, AbstractTextEditor.PREFERENCE_COLOR_BACKGROUND, AbstractTextEditor.PREFERENCE_COLOR_BACKGROUND_SYSTEM_DEFAULT,
			SWT.COLOR_LIST_BACKGROUND);
		RGB fg = editorColor(d, AbstractTextEditor.PREFERENCE_COLOR_FOREGROUND, AbstractTextEditor.PREFERENCE_COLOR_FOREGROUND_SYSTEM_DEFAULT,
			SWT.COLOR_LIST_FOREGROUND);
		boolean dark = luminance(bg) < 0.5;
		RGB side = sys(d, SWT.COLOR_WIDGET_BACKGROUND);
		RGB accent = sys(d, SWT.COLOR_LIST_SELECTION);
		RGB accentText = sys(d, SWT.COLOR_LIST_SELECTION_TEXT);
		RGB link = sys(d, SWT.COLOR_LINK_FOREGROUND);
		RGB white = new RGB(255, 255, 255);
		RGB black = new RGB(0, 0, 0);
		RGB lift = mix(bg, dark ? white : black, 0.06);
		RGB border = mix(bg, fg, 0.22);
		String ui = fontFamily(JFaceResources.getDefaultFont(), "sans-serif");
		String mono = fontFamily(JFaceResources.getTextFont(), "monospace");
		StringBuilder css = new StringBuilder(":root{");
		put(css, "font-family", ui);
		put(css, "editor-font-family", mono);
		put(css, "foreground", hex(fg));
		put(css, "editor-foreground", hex(fg));
		put(css, "editor-background", hex(bg));
		put(css, "sideBar-background", hex(side));
		put(css, "editorWidget-background", hex(lift));
		put(css, "input-background", hex(lift));
		put(css, "descriptionForeground", hex(mix(fg, bg, 0.35)));
		put(css, "disabledForeground", hex(mix(fg, bg, 0.55)));
		put(css, "button-background", hex(accent));
		put(css, "button-foreground", hex(accentText));
		put(css, "list-activeSelectionBackground", hex(accent));
		put(css, "list-hoverBackground", hex(mix(bg, fg, 0.1)));
		put(css, "panel-border", hex(border));
		put(css, "widget-border", hex(border));
		put(css, "widget-shadow", "rgba(0,0,0,0.36)");
		put(css, "scrollbarSlider-background", "rgba(" + fg.red + "," + fg.green + "," + fg.blue + ",0.25)");
		put(css, "textLink-foreground", hex(link));
		put(css, "errorForeground", dark ? "#f48771" : "#a1260d");
		put(css, "editorWarning-foreground", dark ? "#cca700" : "#bf8803");
		put(css, "charts-green", dark ? "#89d185" : "#388a34");
		put(css, "charts-purple", dark ? "#b180d7" : "#652d90");
		put(css, "charts-red", dark ? "#f14c4c" : "#e51400");
		put(css, "charts-yellow", dark ? "#cca700" : "#bf8803");
		put(css, "debugTokenExpression-number", dark ? "#b5cea8" : "#098658");
		put(css, "debugTokenExpression-string", dark ? "#ce9178" : "#a31515");
		put(css, "symbolIcon-classForeground", dark ? "#ee9d28" : "#d67e00");
		put(css, "symbolIcon-functionForeground", dark ? "#b180d7" : "#652d90");
		put(css, "symbolIcon-keywordForeground", dark ? "#d4d4d4" : "#616161");
		css.append('}');
		return new IdeTheme(dark, css.toString());
	}

	private static void put(StringBuilder css, String name, String value) {
		css.append("--vscode-").append(name).append(':').append(value).append(';');
	}
}
