package com.shashti.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** On-screen mouse pointer for sites that are not built for a TV remote. */
public class CursorView extends View {
    public float x = -1, y = -1;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float s;

    public CursorView(Context c) {
        super(c);
        s = c.getResources().getDisplayMetrics().density;
        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        edge.setColor(0xFFE50914);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2.5f * s);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (x < 0) { x = getWidth() / 2f; y = getHeight() / 2f; }
        float k = 1.6f * s;
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x, y + 18 * k);
        p.lineTo(x + 4.5f * k, y + 13.5f * k);
        p.lineTo(x + 8 * k, y + 21 * k);
        p.lineTo(x + 11 * k, y + 19.5f * k);
        p.lineTo(x + 7.5f * k, y + 12.5f * k);
        p.lineTo(x + 13 * k, y + 12.5f * k);
        p.close();
        c.drawPath(p, fill);
        c.drawPath(p, edge);
    }
}
