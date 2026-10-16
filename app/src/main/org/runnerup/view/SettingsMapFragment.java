package org.runnerup.view;

import android.os.Bundle;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import org.runnerup.BuildConfig;
import org.runnerup.R;

public class SettingsMapFragment extends PreferenceFragmentCompat {

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    setPreferencesFromResource(R.xml.settings_map, rootKey);
    if (!BuildConfig.MAPBOX_ENABLED) {
      Preference mapbox = findPreference(getString(R.string.pref_mapbox_default_style));
      if (mapbox != null) {
        mapbox.setVisible(false);
      }
    }
  }
}
