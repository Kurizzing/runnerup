package org.runnerup.view;

import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import androidx.preference.ListPreference;
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
    if (!BuildConfig.OSMDROID_ENABLED) {
      // Lap labels and colors are only drawn on the osmdroid map
      for (int key :
          new int[] {
            R.string.pref_map_lap_labels,
            R.string.pref_map_lap_list_fit,
            R.string.pref_map_lap_values,
            R.string.pref_map_lap_gradient,
            R.string.pref_map_lap_color_start,
            R.string.pref_map_lap_color_end
          }) {
        Preference pref = findPreference(getString(key));
        if (pref != null) {
          pref.setVisible(false);
        }
      }
    }
    addColorDots(R.string.pref_map_lap_color_start);
    addColorDots(R.string.pref_map_lap_color_end);
  }

  /** Shows a dot in each color before the color names (the values are the colors) */
  private void addColorDots(int key) {
    ListPreference pref = findPreference(getString(key));
    if (pref == null) {
      return;
    }
    CharSequence[] names = pref.getEntries();
    CharSequence[] values = pref.getEntryValues();
    CharSequence[] entries = new CharSequence[names.length];
    for (int i = 0; i < names.length; i++) {
      SpannableString entry = new SpannableString("●  " + names[i]);
      entry.setSpan(
          new ForegroundColorSpan(Color.parseColor(values[i].toString())),
          0,
          1,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      entries[i] = entry;
    }
    pref.setEntries(entries);
  }
}
