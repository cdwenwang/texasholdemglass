package com.example.yolov5tfliteandroid.model;

import com.example.yolov5tfliteandroid.enums.Action;

public class BetStrategy {
    public Action action;
    public double amount;
    public String betType; // Value(价值), Bluff(诈唬), Protection(保护)
    public String reason;
}
