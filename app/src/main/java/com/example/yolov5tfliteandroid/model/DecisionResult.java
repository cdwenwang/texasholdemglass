package com.example.yolov5tfliteandroid.model;

import com.example.yolov5tfliteandroid.enums.Action;

public class DecisionResult {
    public Action suggestedAction;
    public double ev;
    public String reason;

    @Override
    public String toString() {
        // String.format 在 Android 上是通用的
        return String.format("建议: %s | EV: %.2f | 分析: %s", suggestedAction, ev, reason);
    }
}