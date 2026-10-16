package com.example.healthmon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 自绘折线图。零第三方依赖的代价就是要自己画，好处是云构建几乎不会失败。
 *
 * 【性能要点】画之前必须经 ChartData.downsample 抽稀。
 * 1Hz 跑几小时就是上万点，直接画会卡死主线程 —— 而"卡一下"这种问题
 * 在开发机上不容易复现，等用户跑久了才暴露。
 */
public final class ChartView extends View {

    private static final int PAD_LEFT = 56;      // 给 Y 轴标签留位
    private static final int PAD_BOTTOM = 28;    // 给时间留位
    private static final int PAD_TOP = 12;
    private static final int PAD_RIGHT = 12;
    private static final int MAX_POINTS = 300;   // 抽稀上限，约等于像素宽度的量级

    private List<long[]> pts = new ArrayList<long[]>();
    private String label = "";
    private String unit = "";

    private final Paint axisPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint dotPaint = new Paint();
    private final Path path = new Path();

    public ChartView(Context context) {
        super(context);
        axisPaint.setColor(Color.DKGRAY);
        axisPaint.setStrokeWidth(2f);

        gridPaint.setColor(Color.LTGRAY);
        gridPaint.setStrokeWidth(1f);

        linePaint.setColor(0xFF1565C0);   // 蓝
        linePaint.setStrokeWidth(3f);
        linePaint.setStyle(Paint.Style.STROKE);

        dotPaint.setColor(0xFF1565C0);
        dotPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.DKGRAY);
        textPaint.setTextSize(22f);
    }

    /** 设置数据。pts 每项是 {时间戳, 数值×10}（整数，避免浮点比较的麻烦）。 */
    public void setPoints(List<long[]> pts, String label, String unit) {
        this.pts = pts == null ? new ArrayList<long[]>() : pts;
        this.label = label == null ? "" : label;
        this.unit = unit == null ? "" : unit;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        int left = PAD_LEFT, top = PAD_TOP, right = w - PAD_RIGHT, bottom = h - PAD_BOTTOM;
        if (right <= left || bottom <= top) return;

        // 坐标轴
        canvas.drawLine(left, top, left, bottom, axisPaint);
        canvas.drawLine(left, bottom, right, bottom, axisPaint);

        if (pts.isEmpty()) {
            canvas.drawText("暂无数据", left + 16, (top + bottom) / 2, textPaint);
            return;
        }

        // 抽稀：这一步不能省
        List<long[]> draw = ChartData.downsample(pts, MAX_POINTS);
        long[] r = ChartData.range(draw);
        long minV = r[0], maxV = r[1], minT = r[2], maxT = r[3];

        // 数值范围太窄时给一个最小跨度，否则曲线会贴在边上抖动得看不出趋势
        double span = maxV - minV;
        if (span < 5) {                       // 数值是 ×10 存的，5 表示 0.5 个单位
            long mid = (minV + maxV) / 2;
            minV = mid - 5;
            maxV = mid + 5;
        } else {
            long pad = Math.max(1, (long) (span * 0.1));
            minV -= pad;
            maxV += pad;
        }
        if (maxT == minT) maxT = minT + 1;

        // 横向网格 + Y 轴标签
        int rows = 4;
        for (int i = 0; i <= rows; i++) {
            int y = top + (bottom - top) * i / rows;
            canvas.drawLine(left, y, right, y, gridPaint);
            long v = maxV - (maxV - minV) * i / rows;
            canvas.drawText(Fmt.oneDecimal(v / 10.0), 6, y + 7, textPaint);
        }

        // 折线
        path.reset();
        for (int i = 0; i < draw.size(); i++) {
            long[] p = draw.get(i);
            float x = left + (float) (right - left) * (p[0] - minT) / (float) (maxT - minT);
            float y = bottom - (float) (bottom - top) * (p[1] - minV) / (float) (maxV - minV);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }

        if (draw.size() == 1) {
            // 只有一个点画不出线，画个圆点，否则用户以为没数据
            long[] p = draw.get(0);
            float x = left + (float) (right - left) * (p[0] - minT) / (float) (maxT - minT);
            float y = bottom - (float) (bottom - top) * (p[1] - minV) / (float) (maxV - minV);
            canvas.drawCircle(x, y, 5f, dotPaint);
        } else {
            canvas.drawPath(path, linePaint);
        }

        // 左下角标出单位和点数
        canvas.drawText(label + " (" + unit + ")  " + draw.size() + " 点", left + 4, h - 8, textPaint);
    }
}
