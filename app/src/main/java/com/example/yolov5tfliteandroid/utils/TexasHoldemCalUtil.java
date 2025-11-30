package com.example.yolov5tfliteandroid.utils;

import com.example.yolov5tfliteandroid.enums.Action;
import com.example.yolov5tfliteandroid.enums.Suit;
import com.example.yolov5tfliteandroid.model.Card;
import com.example.yolov5tfliteandroid.model.DecisionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class TexasHoldemCalUtil {

    private static final int SIMULATION_COUNT = 5000;

    // 使用 int 表示牌，0-51。
    // rank = card % 13 (0=2, 12=A)
    // suit = card / 13 (0-3)
    private static final int[] MASTER_DECK_INT = new int[52];

    static {
        for (int i = 0; i < 52; i++) {
            MASTER_DECK_INT[i] = i;
        }
    }

    /**
     * 【对外入口】战术分析报告生成
     */
    public static DecisionResult analyzeAndDecide(List<Card> myHand, List<Card> board, int numOpponents,
                                                  double potSize, double costToCall) {

        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append("=== Texas Hold'em Math Analysis (Optimized) ===\n");

        // 1. 基础信息
        logBuilder.append(String.format("1. Game State:\n   - My Hand: %s\n   - Board: %s\n   - Pot: %.1f, Cost: %.1f\n",
                myHand.toString(), board.toString(), potSize, costToCall));

        // 2. 蒙特卡洛计算 (高性能版)
        long startTime = System.currentTimeMillis();
        double winRate = calculateWinRateFast(myHand, board, numOpponents);
        long duration = System.currentTimeMillis() - startTime;
        logBuilder.append(String.format("2. Probability (Fast Monte Carlo %d runs):\n   - Raw Win Rate: %.2f%% (Time: %dms)\n",
                SIMULATION_COUNT, winRate * 100, duration));

        // 3. 特征提取 (保持原逻辑用于生成文本，这部分只运行一次，不需要过度优化)
        boolean isFlushDraw = isFlushDraw(myHand, board);
        boolean isStraightDraw = isStraightDraw(myHand, board);
        boolean isSetMining = isSetMining(myHand, board);
        boolean isPairOnBoard = isPairOnBoard(board);

        List<String> detectedFeatures = new ArrayList<>();
        if (isFlushDraw) detectedFeatures.add("FLUSH_DRAW");
        if (isStraightDraw) detectedFeatures.add("STRAIGHT_DRAW");
        if (isSetMining) detectedFeatures.add("SET_MINING");
        if (isPairOnBoard) detectedFeatures.add("BOARD_PAIRED");

        String featureStr = detectedFeatures.isEmpty() ? "None" : detectedFeatures.toString();
        logBuilder.append("3. Hand Features:\n   - ").append(featureStr).append("\n");

        // 4. 潜在赔率
        double impliedOddsScale = 0.0;
        int boardSize = board.size();
        String impliedReason = "Low/River";

        if (boardSize > 0 && boardSize < 5) {
            if (isSetMining) {
                impliedOddsScale = 0.9; impliedReason = "Set Mining";
            } else if (isFlushDraw && isStraightDraw) {
                impliedOddsScale = 1.0; impliedReason = "Monster Draw";
            } else if (isFlushDraw) {
                impliedOddsScale = 0.8; impliedReason = "Flush Draw";
            } else if (isStraightDraw) {
                impliedOddsScale = 0.7; impliedReason = "Straight Draw";
            } else {
                impliedOddsScale = 0.1;
            }
            if (boardSize == 4) impliedOddsScale *= 0.5;
        }
        logBuilder.append(String.format("4. Implied Odds: Scale %.2f (%s)\n", impliedOddsScale, impliedReason));

        // 5. 财务与决策
        double potOdds = (costToCall > 0) ? costToCall / (potSize + costToCall) : 0.0;
        double adjustedWinRate = winRate * (1 + impliedOddsScale * 0.2);
        double ev = (adjustedWinRate * (potSize + costToCall)) - costToCall;

        logBuilder.append(String.format("5. Math: PotOdds %.1f%%, AdjWinRate %.1f%%, EV %.2f\n",
                potOdds*100, adjustedWinRate*100, ev));

        DecisionResult result = new DecisionResult();
        result.ev = ev;

        if (costToCall == 0) {
            if (winRate > 0.7) result.suggestedAction = Action.RAISE;
            else result.suggestedAction = Action.CHECK_FOLD;
        } else {
            if (ev > 0) {
                if (adjustedWinRate > 0.6 || (ev > costToCall * 1.5)) result.suggestedAction = Action.RAISE;
                else result.suggestedAction = Action.CALL;
            } else {
                if (potOdds < 0.15 && impliedOddsScale > 0.5) result.suggestedAction = Action.CALL;
                else result.suggestedAction = Action.FOLD;
            }
        }

        result.reason = logBuilder.toString();
        return result;
    }

    // =========================================================================
    //  高性能计算核心区 (High Performance Core)
    //  说明：为了性能，这里全部使用 int 数组和位运算，避免对象创建
    // =========================================================================

    private static double calculateWinRateFast(List<Card> myHandObj, List<Card> boardObj, int numOpponents) {
        // 1. 预处理：将对象转换为 int ID (0-51)
        int[] myHand = toIntArray(myHandObj);
        int[] knownBoard = toIntArray(boardObj);

        // 标记已知牌，用于洗牌时跳过
        boolean[] usedCards = new boolean[52];
        for (int c : myHand) usedCards[c] = true;
        for (int c : knownBoard) usedCards[c] = true;

        // 准备剩余牌堆
        int[] deck = new int[52];
        int deckSize = 0;
        for (int i = 0; i < 52; i++) {
            if (!usedCards[i]) {
                deck[deckSize++] = i;
            }
        }

        // 预分配内存，避免循环内 new
        int[] currentBoard = new int[5]; // 最多5张公牌
        int[] combinedHand = new int[7]; // 2手牌 + 5公牌
        int[] opHand = new int[7];       // 对手的手牌容器
        Random random = new Random();

        int wins = 0;
        int ties = 0;
        int knownBoardSize = knownBoard.length;
        int cardsToDealBoard = 5 - knownBoardSize;
        // 每个对手发2张，加上公牌需要补的张数
        int cardsNeeded = cardsToDealBoard + (numOpponents * 2);

        // --- 核心循环 START ---
        for (int i = 0; i < SIMULATION_COUNT; i++) {
            // 1. 局部洗牌 (Fisher-Yates) - 只洗我们需要发出的那几张牌
            // 相当于从牌堆里随机抽 cardsNeeded 张牌放到 deck 数组的前面
            for (int j = 0; j < cardsNeeded; j++) {
                int r = j + random.nextInt(deckSize - j);
                int temp = deck[r];
                deck[r] = deck[j];
                deck[j] = temp;
            }

            int deckIndex = 0;

            // 2. 补齐公共牌
            // 先复制已知的
            for (int k = 0; k < knownBoardSize; k++) {
                currentBoard[k] = knownBoard[k];
            }
            // 再补发未知的
            for (int k = 0; k < cardsToDealBoard; k++) {
                currentBoard[knownBoardSize + k] = deck[deckIndex++];
            }

            // 3. 计算我的分数
            // 组装7张牌
            combinedHand[0] = myHand[0];
            combinedHand[1] = myHand[1];
            System.arraycopy(currentBoard, 0, combinedHand, 2, 5);
            long myScore = evaluate7CardsFast(combinedHand);

            boolean iWin = true;
            boolean isTie = false;

            // 4. 模拟对手
            for (int op = 0; op < numOpponents; op++) {
                // 发两张牌给对手
                opHand[0] = deck[deckIndex++];
                opHand[1] = deck[deckIndex++];
                // 加上公牌
                System.arraycopy(currentBoard, 0, opHand, 2, 5);

                long opScore = evaluate7CardsFast(opHand);

                if (opScore > myScore) {
                    iWin = false;
                    break;
                } else if (opScore == myScore) {
                    isTie = true;
                }
            }

            if (iWin) {
                if (isTie) ties++;
                else wins++;
            }
        }
        // --- 核心循环 END ---

        return (wins + 0.5 * ties) / SIMULATION_COUNT;
    }

    /**
     * 极速评分算法
     * 不排序，不创建对象，基于数组统计和位运算
     * 输入：int[7] 数组，每个元素 0-51
     * 返回：long 分数 (同 HandEvaluator 格式)
     */
    private static long evaluate7CardsFast(int[] cards) {
        // 统计花色和点数
        int[] rankCounts = new int[13]; // 0=2, 12=A
        int[] suitCounts = new int[4];

        // 位掩码记录存在的点数 (用于快速查顺子)
        int rankBitMask = 0;

        for (int c : cards) {
            int r = c % 13;
            int s = c / 13;
            rankCounts[r]++;
            suitCounts[s]++;
            rankBitMask |= (1 << r);
        }

        // --- 1. 检查同花 ---
        int flushSuit = -1;
        for (int i = 0; i < 4; i++) {
            if (suitCounts[i] >= 5) {
                flushSuit = i;
                break;
            }
        }

        // 如果有同花，检查是不是同花顺
        if (flushSuit != -1) {
            // 收集该花色的所有点数
            int flushRankMask = 0;
            for (int c : cards) {
                if (c / 13 == flushSuit) {
                    flushRankMask |= (1 << (c % 13));
                }
            }
            int sfRank = getStraightHighRank(flushRankMask);
            if (sfRank != -1) {
                return encodeScoreFast(9, sfRank); // 同花顺
            }
        }

        // --- 2. 检查四条/葫芦/三条/两对/一对 ---
        // 寻找频率
        int quadRank = -1;
        int tripRank = -1;
        int pair1 = -1;
        int pair2 = -1;

        // 从大到小遍历 (A -> 2)
        for (int r = 12; r >= 0; r--) {
            int count = rankCounts[r];
            if (count == 4) {
                quadRank = r;
                break; // 只有可能有一个四条
            } else if (count == 3) {
                if (tripRank == -1) tripRank = r;
            } else if (count == 2) {
                if (pair1 == -1) pair1 = r;
                else if (pair2 == -1) pair2 = r;
            }
        }

        // 四条
        if (quadRank != -1) {
            int kicker = -1;
            for (int r = 12; r >= 0; r--) {
                if (r != quadRank && rankCounts[r] > 0) {
                    kicker = r;
                    break;
                }
            }
            return encodeScoreFast(8, quadRank + 2, kicker + 2); // +2 是为了匹配 Rank枚举值
        }

        // 葫芦 (三条 + 另一组三条或一对)
        if (tripRank != -1) {
            // 如果有两个三条，tripRank存的是大的，找次大的作为葫芦的一对
            if (pair1 != -1 || pair2 != -1) {
                // 优先找最大的对子
                int p = (pair1 != -1) ? pair1 : -1;
                // 特殊情况：两个三条，取较小的那个做对子
                for (int r = 12; r >= 0; r--) {
                    if (r != tripRank && rankCounts[r] >= 2) {
                        p = r;
                        break;
                    }
                }
                return encodeScoreFast(7, tripRank + 2, p + 2);
            }
        }

        // 同花 (非同花顺)
        if (flushSuit != -1) {
            // 找最大的5张同花牌
            int[] flushKickers = new int[5];
            int idx = 0;
            for (int r = 12; r >= 0; r--) {
                // 检查该点数是否有该花色的牌
                // 这里为了极致速度，需要重新遍历一下cards或者存bitmap
                // 简单起见，重新遍历cards找到属于flushSuit且rank为r的
                boolean hasRankInSuit = false;
                for(int c : cards) {
                    if (c/13 == flushSuit && c%13 == r) {
                        hasRankInSuit = true;
                        break;
                    }
                }
                if (hasRankInSuit) {
                    flushKickers[idx++] = r + 2;
                    if (idx == 5) break;
                }
            }
            return encodeScoreFast(6, flushKickers);
        }

        // --- 3. 检查顺子 ---
        int straightRank = getStraightHighRank(rankBitMask);
        if (straightRank != -1) {
            return encodeScoreFast(5, straightRank);
        }

        // --- 4. 剩余牌型 ---
        if (tripRank != -1) {
            // 三条，找两个踢脚
            int k1 = -1, k2 = -1;
            for (int r = 12; r >= 0; r--) {
                if (r != tripRank && rankCounts[r] > 0) {
                    if (k1 == -1) k1 = r + 2;
                    else { k2 = r + 2; break; }
                }
            }
            return encodeScoreFast(4, tripRank + 2, k1, k2);
        }

        if (pair1 != -1 && pair2 != -1) {
            // 两对
            int kicker = -1;
            for (int r = 12; r >= 0; r--) {
                if (r != pair1 && r != pair2 && rankCounts[r] > 0) {
                    kicker = r + 2;
                    break;
                }
            }
            return encodeScoreFast(3, pair1 + 2, pair2 + 2, kicker);
        }

        if (pair1 != -1) {
            // 一对
            int k1=-1, k2=-1, k3=-1;
            for (int r = 12; r >= 0; r--) {
                if (r != pair1 && rankCounts[r] > 0) {
                    if (k1==-1) k1=r+2;
                    else if (k2==-1) k2=r+2;
                    else { k3=r+2; break; }
                }
            }
            return encodeScoreFast(2, pair1 + 2, k1, k2, k3);
        }

        // 高牌
        int[] kickers = new int[5];
        int idx = 0;
        for (int r = 12; r >= 0; r--) {
            if (rankCounts[r] > 0) {
                kickers[idx++] = r + 2;
                if (idx == 5) break;
            }
        }
        return encodeScoreFast(1, kickers);
    }

    // 辅助：使用位运算快速判断顺子最大值
    // 返回顺子最大牌的Rank (A=14, ..., 5=5)，如果没有返回 -1
    private static int getStraightHighRank(int rankBitMask) {
        // 将A (bit 12) 复制到 bit -1 的位置 (逻辑上)，但在int里我们检查低位
        // A, K, Q, J, T ... 2
        // bit: 12 ... 0

        // 检查 A-5 (Wheel): 需要 A(12), 2(0), 3(1), 4(2), 5(3)
        // mask & 0b1000000001111
        // 0x100F = 1 0000 0000 1111 (A, 5, 4, 3, 2)
        if ((rankBitMask & 0x100F) == 0x100F) {
            // 注意：如果同时有 6,5,4,3,2，应该返回 6。
            // 所以这里不能直接返回5，还得继续往下检查普通顺子。
            // 但普通顺子逻辑涵盖了 6-2。唯一漏掉的是 A-5 作为最小顺子。
            // 我们先检查普通顺子，如果没有，再返回5。
        }

        // 连续5个1
        // x & (x<<1) & (x<<2) & (x<<3) & (x<<4)
        // 因为 rank 0 是 2，rank 12 是 A。
        // 我们需要向右移位来检测。
        // 比如检测 6,5,4,3,2 (rank 4,3,2,1,0)
        // mask 有 0...011111

        // 稍微粗暴点，直接循环检测 (比创建对象快多了)
        // 从 A (12) 开始往下直到 6 (4)
        for (int i = 12; i >= 4; i--) {
            // 检查 i, i-1, i-2, i-3, i-4 是否都存在
            int mask = (1 << i) | (1 << (i-1)) | (1 << (i-2)) | (1 << (i-3)) | (1 << (i-4));
            if ((rankBitMask & mask) == mask) {
                return i + 2; // rank 12 -> 14 (Ace)
            }
        }

        // 单独检查 A-2-3-4-5
        if ((rankBitMask & 0x100F) == 0x100F) {
            return 5; // 5 high straight
        }

        return -1;
    }

    private static long encodeScoreFast(int catScore, int... kickers) {
        long s = catScore;
        for (int k : kickers) s = (s << 4) + k;
        // 补齐到5个kicker长度 (每个4位)
        return s << (4 * (5 - kickers.length));
    }

    private static int[] toIntArray(List<Card> cards) {
        int[] arr = new int[cards.size()];
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            // Rank: 2=0 ... A=12
            // Suit: 0-3
            int r = c.getRank().getValue() - 2; // Rank enum value is 2-14
            int s = c.getSuit().ordinal();
            arr[i] = s * 13 + r;
        }
        return arr;
    }

    // ==========================================
    // 特征检测辅助方法 (保持原有逻辑，用于生成Reason)
    // ==========================================

    private static boolean isPairOnBoard(List<Card> board) {
        Set<Integer> ranks = new HashSet<>();
        for(Card c : board) {
            if(!ranks.add(c.getRank().getValue())) return true;
        }
        return false;
    }

    private static boolean isFlushDraw(List<Card> myHand, List<Card> board) {
        Map<Suit, Integer> suitCounts = new HashMap<>();
        List<Card> all = new ArrayList<>(myHand);
        all.addAll(board);
        for (Card c : all) {
            Integer count = suitCounts.get(c.getSuit());
            if (count == null) count = 0;
            suitCounts.put(c.getSuit(), count + 1);
        }
        for (Integer count : suitCounts.values()) {
            if (count == 4) return true;
        }
        return false;
    }

    private static boolean isStraightDraw(List<Card> myHand, List<Card> board) {
        List<Card> all = new ArrayList<>(myHand);
        all.addAll(board);
        Set<Integer> ranks = new HashSet<>();
        for (Card c : all) ranks.add(c.getRank().getValue());
        if (ranks.contains(14)) ranks.add(1);

        List<Integer> sortedRanks = new ArrayList<>(ranks);
        Collections.sort(sortedRanks);

        for (int i = 0; i <= sortedRanks.size() - 4; i++) {
            int span = sortedRanks.get(i + 3) - sortedRanks.get(i);
            if (span <= 4) return true;
        }
        return false;
    }

    private static boolean isSetMining(List<Card> myHand, List<Card> board) {
        if (myHand.get(0).getRank() != myHand.get(1).getRank()) return false;
        int rank = myHand.get(0).getRank().getValue();
        int boardCount = 0;
        for(Card c : board) {
            if(c.getRank().getValue() == rank) boardCount++;
        }
        return boardCount == 0;
    }
}