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

package org.runnerup.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import java.text.DateFormatSymbols;
import java.util.Calendar;
import org.runnerup.R;

/**
 * Calendar grid of one month. Days with activities are drawn as a filled circle in the given color;
 * today and the selected day are outlined. Tapping a day reports it to the listener.
 */
public class CalendarMonthView extends View {

  public interface OnDayClickListener {
    void onDayClick(int dayOfMonth);
  }

  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int textColor;
  private final int mutedColor;
  private final int dayTextOnColor;
  private final int todayColor;
  private final int selectedColor;
  private final float headerHeight;

  private int year;
  private int month; // Calendar.JANUARY ...
  private int firstDayOfWeek = Calendar.getInstance().getFirstDayOfWeek();
  private SparseIntArray dayColors = new SparseIntArray();
  private int today = 0; // day of month, 0 if today is not in this month
  private int selectedDay = 0;
  private String[] weekdayNames;
  @Nullable private OnDayClickListener listener;

  public CalendarMonthView(Context context) {
    this(context, null);
  }

  public CalendarMonthView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    textColor = ContextCompat.getColor(context, R.color.colorText);
    mutedColor = ContextCompat.getColor(context, R.color.colorTextMuted);
    dayTextOnColor = ContextCompat.getColor(context, R.color.appBackground);
    todayColor = ContextCompat.getColor(context, R.color.colorAccent);
    selectedColor = textColor;
    headerHeight = dp(28);

    textPaint.setTextAlign(Paint.Align.CENTER);
    textPaint.setTextSize(sp(15));
    headerPaint.setTextAlign(Paint.Align.CENTER);
    headerPaint.setTextSize(sp(12));
    headerPaint.setColor(mutedColor);
    ringPaint.setStyle(Paint.Style.STROKE);
    ringPaint.setStrokeWidth(dp(2));

    // Short weekday names, e.g. "S M T W T F S"
    String[] names = new DateFormatSymbols().getShortWeekdays();
    weekdayNames = new String[8];
    for (int i = Calendar.SUNDAY; i <= Calendar.SATURDAY; i++) {
      String n = names[i];
      weekdayNames[i] = n.isEmpty() ? n : n.substring(0, 1);
    }

    Calendar now = Calendar.getInstance();
    year = now.get(Calendar.YEAR);
    month = now.get(Calendar.MONTH);
  }

  private float dp(float v) {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
  }

  private float sp(float v) {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
  }

  /**
   * @param month Calendar.JANUARY ... Calendar.DECEMBER
   * @param dayColors color per day of month that has activities
   */
  public void setMonth(int year, int month, SparseIntArray dayColors) {
    this.year = year;
    this.month = month;
    this.dayColors = dayColors;
    Calendar now = Calendar.getInstance();
    today =
        now.get(Calendar.YEAR) == year && now.get(Calendar.MONTH) == month
            ? now.get(Calendar.DAY_OF_MONTH)
            : 0;
    requestLayout();
    invalidate();
  }

  public void setSelectedDay(int dayOfMonth) {
    selectedDay = dayOfMonth;
    invalidate();
  }

  public void setOnDayClickListener(@Nullable OnDayClickListener listener) {
    this.listener = listener;
  }

  private Calendar firstOfMonth() {
    Calendar c = Calendar.getInstance();
    c.clear();
    c.set(year, month, 1);
    return c;
  }

  /** Column (0-6) of the first day of the month */
  private int firstColumn() {
    return (firstOfMonth().get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7;
  }

  private int daysInMonth() {
    return firstOfMonth().getActualMaximum(Calendar.DAY_OF_MONTH);
  }

  private int rows() {
    return (firstColumn() + daysInMonth() + 6) / 7;
  }

  private float cellWidth() {
    return (getWidth() - getPaddingLeft() - getPaddingRight()) / 7f;
  }

  private float cellHeight(float cellWidth) {
    return Math.min(cellWidth, dp(48));
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    float cell = cellHeight((width - getPaddingLeft() - getPaddingRight()) / 7f);
    int height = (int) (getPaddingTop() + headerHeight + rows() * cell + getPaddingBottom());
    setMeasuredDimension(width, height);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float cw = cellWidth();
    float ch = cellHeight(cw);
    float left = getPaddingLeft();
    float top = getPaddingTop();

    for (int col = 0; col < 7; col++) {
      int weekday = (firstDayOfWeek - 1 + col) % 7 + 1;
      canvas.drawText(
          weekdayNames[weekday], left + (col + 0.5f) * cw, top + headerHeight * 0.65f, headerPaint);
    }

    float radius = Math.min(cw, ch) * 0.4f;
    int first = firstColumn();
    int days = daysInMonth();
    float textOffset = (textPaint.descent() + textPaint.ascent()) / 2;
    for (int day = 1; day <= days; day++) {
      int index = first + day - 1;
      float cx = left + (index % 7 + 0.5f) * cw;
      float cy = top + headerHeight + (index / 7 + 0.5f) * ch;
      int color = dayColors.get(day, 0);
      if (color != 0) {
        circlePaint.setColor(color);
        canvas.drawCircle(cx, cy, radius, circlePaint);
        textPaint.setColor(dayTextOnColor);
        textPaint.setFakeBoldText(true);
      } else {
        textPaint.setColor(day == today ? textColor : mutedColor);
        textPaint.setFakeBoldText(false);
      }
      if (day == selectedDay) {
        ringPaint.setColor(selectedColor);
        canvas.drawCircle(cx, cy, radius + dp(3), ringPaint);
      } else if (day == today) {
        ringPaint.setColor(todayColor);
        canvas.drawCircle(cx, cy, radius, ringPaint);
      }
      canvas.drawText(Integer.toString(day), cx, cy - textOffset, textPaint);
    }
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (event.getAction() == MotionEvent.ACTION_DOWN) {
      return true;
    }
    if (event.getAction() != MotionEvent.ACTION_UP || listener == null) {
      return super.onTouchEvent(event);
    }
    performClick();
    float cw = cellWidth();
    float ch = cellHeight(cw);
    int col = (int) ((event.getX() - getPaddingLeft()) / cw);
    int row = (int) ((event.getY() - getPaddingTop() - headerHeight) / ch);
    if (col < 0 || col > 6 || row < 0 || event.getY() < getPaddingTop() + headerHeight) {
      return true;
    }
    int day = row * 7 + col - firstColumn() + 1;
    if (day >= 1 && day <= daysInMonth()) {
      listener.onDayClick(day);
    }
    return true;
  }
}
