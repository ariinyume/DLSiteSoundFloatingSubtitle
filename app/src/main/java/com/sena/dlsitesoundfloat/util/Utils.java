package com.sena.dlsitesoundfloat.util;

import android.content.Context;

public class Utils {
    public static int dip2px(Context ctx, float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
