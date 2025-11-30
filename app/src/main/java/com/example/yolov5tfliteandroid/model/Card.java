package com.example.yolov5tfliteandroid.model;

import com.example.yolov5tfliteandroid.enums.Rank;
import com.example.yolov5tfliteandroid.enums.Suit;

public class Card implements Comparable<Card> {
    final Rank rank;
    final Suit suit;

    public Card(Rank rank, Suit suit) {
        this.rank = rank;
        this.suit = suit;
    }

    @Override
    public String toString() {
        return rank.name().substring(0, 1) + suit.name().substring(0, 1);
    }

    @Override
    public int compareTo(Card o) {
        return Integer.compare(this.rank.getValue(), o.rank.getValue());
    }

    public Rank getRank() {
        return rank;
    }

    public Suit getSuit() {
        return suit;
    }
}