package com.example.yolov5tfliteandroid;

import android.util.Log;

import com.example.yolov5tfliteandroid.enums.Rank;
import com.example.yolov5tfliteandroid.enums.Suit;
import com.example.yolov5tfliteandroid.model.Card;
import com.example.yolov5tfliteandroid.model.DecisionResult;
import com.example.yolov5tfliteandroid.utils.TexasHoldemCalUtil;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TexasHoldemCalUtilTest {

    @Test
    public void test_analyzeAndDecide() {
        List<Card> myHand = new ArrayList<>();
        myHand.add(new Card(Rank.THREE, Suit.HEARTS));
        myHand.add(new Card(Rank.SEVEN, Suit.DIAMONDS));

        List<Card> board = new ArrayList<>();
        board.add(new Card(Rank.TEN, Suit.CLUBS));
        board.add(new Card(Rank.THREE, Suit.CLUBS));
        board.add(new Card(Rank.THREE, Suit.SPADES));

        // 这里的参数调整一下，模拟 3 个对手，底池 1000，跟注 0 (过牌)
        DecisionResult result = TexasHoldemCalUtil.analyzeAndDecide(myHand, board, 8, 1000, 50, 10000, 10);

        // 【修改点】使用 Java 标准输出，不要使用 Android Log
        System.out.println("建议动作: " + result.suggestedAction.name());

        // 建议把详细的分析日志也打印出来，方便查看 AI 的计算过程
        System.out.println("详细分析:\n" + result.reason);
    }
}
