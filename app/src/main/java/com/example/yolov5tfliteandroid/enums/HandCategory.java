package com.example.yolov5tfliteandroid.enums;

/**
 * 牌型枚举
 */
public enum HandCategory {
    HIGH_CARD(1), ONE_PAIR(2), TWO_PAIR(3), THREE_OF_A_KIND(4),
    STRAIGHT(5), FLUSH(6), FULL_HOUSE(7), FOUR_OF_A_KIND(8), STRAIGHT_FLUSH(9);

    final int score;

    HandCategory(int score) {
        this.score = score;
    }

    public int getScore() {
        return score;
    }
}
