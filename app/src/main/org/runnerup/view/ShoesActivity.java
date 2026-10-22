/*
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.runnerup.view;

import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import org.runnerup.R;
import org.runnerup.db.DBHelper;
import org.runnerup.db.Shoes;
import org.runnerup.util.SafeParse;
import org.runnerup.util.ViewUtil;

/** Settings: list, add, edit, retire and delete shoes, and choose the default shoe */
public class ShoesActivity extends AppCompatActivity {

  private SQLiteDatabase mDB = null;
  private LinearLayout list;
  private TextView empty;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    EdgeToEdge.enable(this);
    Window window = getWindow();
    WindowCompat.getInsetsController(window, window.getDecorView())
        .setAppearanceLightStatusBars(false);
    super.onCreate(savedInstanceState);
    setContentView(R.layout.shoes);
    setTitle(org.runnerup.common.R.string.Shoes);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    mDB = DBHelper.getWritableDatabase(this);
    list = findViewById(R.id.shoes_list);
    empty = findViewById(R.id.shoes_empty);
    findViewById(R.id.shoes_add).setOnClickListener(v -> showDialog(null));
    ViewUtil.Insets(findViewById(R.id.shoes_view), true);
    refresh();
  }

  @Override
  public boolean onSupportNavigateUp() {
    // Same as back: return to the settings
    getOnBackPressedDispatcher().onBackPressed();
    return true;
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    DBHelper.closeDB(mDB);
  }

  private void refresh() {
    list.removeAllViews();
    List<Shoes.Shoe> shoes = Shoes.list(mDB, true);
    long defaultShoe = Shoes.getDefault(this);
    LayoutInflater inflater = getLayoutInflater();
    for (Shoes.Shoe shoe : shoes) {
      View row = ShoeViews.row(inflater, list, shoe, shoe.id == defaultShoe, true);
      row.setOnClickListener(v -> showDialog(shoe));
      list.addView(row);
    }
    list.setVisibility(shoes.isEmpty() ? View.GONE : View.VISIBLE);
    empty.setVisibility(shoes.isEmpty() ? View.VISIBLE : View.GONE);
  }

  /**
   * @param edited the shoe to edit, or null to add a new one
   */
  private void showDialog(@Nullable Shoes.Shoe edited) {
    Shoes.Shoe shoe = edited != null ? edited : new Shoes.Shoe();
    View view = getLayoutInflater().inflate(R.layout.shoe_dialog, null);
    EditText name = view.findViewById(R.id.shoe_dialog_name);
    EditText date = view.findViewById(R.id.shoe_dialog_date);
    EditText price = view.findViewById(R.id.shoe_dialog_price);
    EditText size = view.findViewById(R.id.shoe_dialog_size);
    EditText notes = view.findViewById(R.id.shoe_dialog_notes);
    CheckBox isDefault = view.findViewById(R.id.shoe_dialog_default);
    CheckBox retired = view.findViewById(R.id.shoe_dialog_retired);

    long defaultShoe = Shoes.getDefault(this);
    // Purchase date being edited (seconds), 0 if not set
    long[] purchaseDate = {shoe.purchaseDate};
    name.setText(shoe.name);
    date.setText(purchaseDate[0] != 0 ? ShoeViews.date(this, purchaseDate[0]) : null);
    if (shoe.price != null) {
      price.setText(
          shoe.price == Math.floor(shoe.price)
              ? String.format(Locale.US, "%.0f", shoe.price)
              : String.format(Locale.US, "%.2f", shoe.price));
    }
    size.setText(shoe.size);
    notes.setText(shoe.notes);
    if (edited != null) {
      isDefault.setChecked(shoe.id == defaultShoe);
      retired.setChecked(shoe.retired);
    } else {
      // The first shoe becomes the default
      isDefault.setChecked(Shoes.validate(mDB, defaultShoe) == Shoes.NONE);
      retired.setVisibility(View.GONE);
    }
    date.setOnClickListener(
        v ->
            pickDate(
                purchaseDate[0],
                seconds -> {
                  purchaseDate[0] = seconds;
                  date.setText(seconds != 0 ? ShoeViews.date(this, seconds) : null);
                }));
    // A retired shoe cannot be the default
    retired.setOnCheckedChangeListener(
        (b, checked) -> {
          if (checked) {
            isDefault.setChecked(false);
          }
        });
    isDefault.setOnCheckedChangeListener(
        (b, checked) -> {
          if (checked) {
            retired.setChecked(false);
          }
        });

    AlertDialog.Builder builder =
        new AlertDialog.Builder(this)
            .setTitle(
                edited == null
                    ? org.runnerup.common.R.string.Shoe_add
                    : org.runnerup.common.R.string.Shoe_edit)
            .setView(view)
            .setPositiveButton(org.runnerup.common.R.string.Save, null)
            .setNegativeButton(org.runnerup.common.R.string.Cancel, null);
    if (edited != null) {
      builder.setNeutralButton(
          org.runnerup.common.R.string.Delete, (d, w) -> confirmDelete(edited));
    }
    AlertDialog dialog = builder.create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String shoeName = name.getText().toString().trim();
                      if (TextUtils.isEmpty(shoeName)) {
                        name.setError(getString(org.runnerup.common.R.string.Shoe_name));
                        return;
                      }
                      shoe.name = shoeName;
                      shoe.purchaseDate = purchaseDate[0];
                      String priceText = price.getText().toString().trim();
                      shoe.price = priceText.isEmpty() ? null : SafeParse.parseDouble(priceText, 0);
                      shoe.size = size.getText().toString().trim();
                      shoe.notes = notes.getText().toString().trim();
                      shoe.retired = retired.isChecked();
                      long id = Shoes.save(mDB, shoe);
                      if (isDefault.isChecked()) {
                        Shoes.setDefault(this, id);
                      } else if (id == defaultShoe) {
                        Shoes.setDefault(this, Shoes.NONE);
                      }
                      dialog.dismiss();
                      refresh();
                    }));
    dialog.show();
  }

  private interface OnDatePicked {
    /**
     * @param seconds start of the picked day (seconds since epoch), 0 if cleared
     */
    void onDatePicked(long seconds);
  }

  private void pickDate(long seconds, OnDatePicked listener) {
    Calendar cal = Calendar.getInstance();
    if (seconds != 0) {
      cal.setTimeInMillis(seconds * 1000);
    }
    DatePickerDialog picker =
        new DatePickerDialog(
            this,
            R.style.AppDatePickerTheme,
            (p, year, month, day) -> {
              Calendar picked = Calendar.getInstance();
              picked.clear();
              picked.set(year, month, day);
              listener.onDatePicked(picked.getTimeInMillis() / 1000);
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH));
    picker.setButton(
        DialogInterface.BUTTON_NEUTRAL,
        getString(org.runnerup.common.R.string.Shoe_clear),
        (d, w) -> listener.onDatePicked(0));
    picker.show();
  }

  private void confirmDelete(Shoes.Shoe shoe) {
    new AlertDialog.Builder(this)
        .setTitle(org.runnerup.common.R.string.Shoe_delete)
        .setMessage(org.runnerup.common.R.string.Shoe_delete_message)
        .setPositiveButton(
            org.runnerup.common.R.string.Delete,
            (d, w) -> {
              Shoes.delete(mDB, shoe.id);
              if (shoe.id == Shoes.getDefault(this)) {
                Shoes.setDefault(this, Shoes.NONE);
              }
              refresh();
            })
        .setNegativeButton(org.runnerup.common.R.string.Cancel, null)
        .show();
  }
}
