package com.ftejero21.traza.prefs;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;

public class PreferenceInitializer extends AbstractPreferenceInitializer {
	@Override
	public void initializeDefaultPreferences() {
		TrazaPreferences.initDefaults();
	}
}
