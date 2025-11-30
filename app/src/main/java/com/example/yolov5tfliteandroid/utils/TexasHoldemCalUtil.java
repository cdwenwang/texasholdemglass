package com.example.yolov5tfliteandroid.utils;

import com.example.yolov5tfliteandroid.enums.Action;
import com.example.yolov5tfliteandroid.enums.Suit;
import com.example.yolov5tfliteandroid.model.BetStrategy;
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
     * 【对外入口】战术分析报告生成 (含加注策略)
     *
     * @param myHand       我的手牌
     * @param board        公共牌
     * @param numOpponents 对手数量
     * @param potSize      当前底池金额
     * @param costToCall   需要跟注的金额 (0表示过牌)
     * @param myStack      我当前的剩余筹码 (用于计算 SPR 和加注上限)
     * @param minRaise     当前桌面的最小加注额 (通常是 1BB 或 上次加注额)
     */
    public static DecisionResult analyzeAndDecide(List<Card> myHand, List<Card> board, int numOpponents,
                                                  double potSize, double costToCall,
                                                  double myStack, double minRaise) {

        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append("=== Texas Hold'em Strategy Report ===\n");

        // 1. 基础局势
        double spr = (potSize > 0) ? myStack / potSize : 0;
        logBuilder.append(String.format("1. Game State:\n   - Hand: %s | Board: %s\n   - Pot: %.1f, Cost: %.1f, Stack: %.1f\n   - SPR: %.2f (%s)\n",
                myHand.toString(), board.toString(), potSize, costToCall, myStack, spr, getSprDescription(spr)));

        // 2. 极速蒙特卡洛计算
        long startTime = System.currentTimeMillis();
        double winRate = calculateWinRateFast(myHand, board, numOpponents);
        long duration = System.currentTimeMillis() - startTime;
        logBuilder.append(String.format("2. Equity (Monte Carlo %d runs):\n   - Win Rate: %.2f%% (Calc Time: %dms)\n",
                SIMULATION_COUNT, winRate * 100, duration));

        // 3. 听牌特征与牌面湿度
        boolean isFlushDraw = isFlushDraw(myHand, board);
        boolean isStraightDraw = isStraightDraw(myHand, board);
        boolean isSetMining = isSetMining(myHand, board);
        boolean isPairOnBoard = isPairOnBoard(board);
        boolean isWetBoard = checkBoardTexture(board); // 检查牌面是否湿润(危险)

        List<String> features = new ArrayList<>();
        if (isFlushDraw) features.add("FLUSH_DRAW");
        if (isStraightDraw) features.add("STRAIGHT_DRAW");
        if (isSetMining) features.add("SET_MINING");
        if (isPairOnBoard) features.add("BOARD_PAIRED");
        if (isWetBoard) features.add("WET_BOARD (Dynamic)");

        logBuilder.append("3. Features:\n   - ").append(features.isEmpty() ? "Dry / Made Hand" : features.toString()).append("\n");

        // 4. 潜在赔率与EV
        double impliedOddsScale = calculateImpliedOddsScale(isSetMining, isFlushDraw, isStraightDraw, board.size());
        double adjustedWinRate = winRate * (1 + impliedOddsScale * 0.2); // 经验修正
        double totalPotIfCall = potSize + costToCall;
        double ev = (adjustedWinRate * totalPotIfCall) - costToCall;
        double potOdds = (costToCall > 0) ? costToCall / totalPotIfCall : 0.0;

        logBuilder.append(String.format("4. Math:\n   - PotOdds: %.1f%% vs WinRate: %.1f%%\n   - EV: %.2f\n",
                potOdds * 100, adjustedWinRate * 100, ev));

        // 5. 策略生成 (核心升级部分)
        DecisionResult result = new DecisionResult();
        result.ev = ev;

        // 调用加注策略计算器
        BetStrategy strategy = calculateBetStrategy(
                winRate, adjustedWinRate, ev, potSize, costToCall, myStack, minRaise, isWetBoard, spr
        );

        result.suggestedAction = strategy.action;
        logBuilder.append("5. Strategy & Sizing:\n");
        logBuilder.append(String.format("   - Action: %s\n", strategy.action));

        if (strategy.action == Action.RAISE || strategy.action == Action.ALL_IN) {
            logBuilder.append(String.format("   - Recommended Amount: %.1f (%.1f%% Pot)\n", strategy.amount, (strategy.amount / potSize) * 100));
            logBuilder.append(String.format("   - Type: %s\n", strategy.betType));
        }

        logBuilder.append(String.format("   - Logic: %s\n", strategy.reason));

        result.reason = logBuilder.toString();
        return result;
    }

    // =========================================================================
    //  加注策略核心逻辑 (Strategy Core)
    // =========================================================================



    /**
     * 计算具体的下注/加注策略
     */
    private static BetStrategy calculateBetStrategy(double rawWinRate, double adjWinRate, double ev,
                                                    double potSize, double costToCall, double myStack,
                                                    double minRaise, boolean isWetBoard, double spr) {
        BetStrategy s = new BetStrategy();

        // --- 基础状态判断 ---
        boolean isNutHand = rawWinRate > 0.85;       // 坚果牌/超强牌
        boolean isStrongHand = rawWinRate > 0.65;    // 强牌
        boolean isDrawHand = adjWinRate > 0.45 && rawWinRate < 0.4; // 听牌(现在弱未来强)

        // --- 场景 A: 没人下注 (Cost == 0) ---
        if (costToCall == 0) {
            if (isNutHand) {
                // 坚果牌：如果是干燥面，可以慢打(Check)；如果是湿润面，必须下注防抽
                if (isWetBoard) {
                    s.action = Action.RAISE; // 在没人下注时Raise即Bet
                    s.amount = calculateSizing(potSize, 0.75, myStack); // 湿面打重注 75%
                    s.betType = "Value/Protection";
                    s.reason = "Nut hand on wet board, bet big to charge draws.";
                } else {
                    // 慢打，或者打小注引诱
                    s.action = Action.RAISE;
                    s.amount = calculateSizing(potSize, 0.33, myStack); // 33% 小注
                    s.betType = "Value/Trap";
                    s.reason = "Nut hand on dry board, small bet to induce action.";
                }
            } else if (isStrongHand) {
                // 强牌：通常打价值
                s.action = Action.RAISE;
                double sizeRatio = isWetBoard ? 0.66 : 0.5; // 湿面 2/3池，干面 半池
                s.amount = calculateSizing(potSize, sizeRatio, myStack);
                s.betType = "Value";
                s.reason = "Strong hand, extracting value.";
            } else if (isDrawHand) {
                // 听牌：可以半诈唬 (Semi-Bluff)
                // 只有在有后手优势(SPR合适)时才做
                if (spr > 3) {
                    s.action = Action.RAISE;
                    s.amount = calculateSizing(potSize, 0.5, myStack);
                    s.betType = "Semi-Bluff";
                    s.reason = "Good draw, betting to fold out better hands or build pot.";
                } else {
                    s.action = Action.CHECK_FOLD; // 这里Action枚举可能没CHECK，用Check_Fold代替
                    s.reason = "Draw hand but stack too shallow for bluff.";
                }
            } else {
                s.action = Action.CHECK_FOLD;
                s.reason = "Weak hand, check/fold.";
            }
        }
        // --- 场景 B: 面临下注 (Cost > 0) ---
        else {
            // 如果加注额太高，导致SPR很低，强制变为 All-in 判断
            // double commitRatio = costToCall / myStack;

            if (ev > 0) {
                // 正EV，基础是 Call

                // 1. 什么时候加注 (Raise)?
                // - 牌极强 (Value Raise)
                // - 听牌极好且需要弃牌率 (Semi-Bluff Raise)

                if (isNutHand || (isStrongHand && isWetBoard)) {
                    // 强牌面临湿面，加注保护
                    s.action = Action.RAISE;
                    // 加注通常是底池的 3倍 或 对方下注的 2.5倍+
                    // 公式：Pot + Cost + RaiseAmount
                    double raiseTotal = (potSize + costToCall) * 0.5 + costToCall;
                    // 确保至少是最小加注额
                    if (raiseTotal < minRaise + costToCall) raiseTotal = minRaise + costToCall;

                    s.amount = calculateSizing(0, 0, myStack, raiseTotal);
                    s.betType = "Value Raise";
                    s.reason = "Re-raising for value and protection.";
                } else if (isDrawHand && spr > 5 && Math.random() > 0.6) {
                    // 听牌有时(40%概率)可以加注诈唬，混合策略
                    s.action = Action.RAISE;
                    double raiseTotal = (potSize + costToCall) * 0.4 + costToCall;
                    if (raiseTotal < minRaise + costToCall) raiseTotal = minRaise + costToCall;

                    s.amount = calculateSizing(0, 0, myStack, raiseTotal);
                    s.betType = "Semi-Bluff Raise";
                    s.reason = "Aggressive play with strong draw.";
                } else {
                    // 普通强牌或听牌，Call
                    s.action = Action.CALL;
                    s.reason = "Positive EV, calling to keep opponent in or see river.";
                }
            } else {
                // 负EV
                double potOdds = costToCall / (potSize + costToCall);
                // 投机跟注：赔率极好且有潜在赔率
                if (potOdds < 0.15 && adjWinRate > 0.2) {
                    s.action = Action.CALL;
                    s.reason = "Speculative call due to excellent pot odds.";
                } else {
                    s.action = Action.FOLD;
                    s.reason = "Negative EV, folding.";
                }
            }

            // 修正：如果计算出的加注额超过剩余筹码的 40%，直接 All-in
            if (s.action == Action.RAISE && s.amount > myStack * 0.4) {
                s.action = Action.ALL_IN;
                s.amount = myStack;
                s.reason += " (Committed, All-in)";
            }
        }

        // 最后兜底检查：如果建议Action是RAISE但金额不够，修正为ALL_IN或CALL
        if (s.action == Action.RAISE) {
            if (s.amount >= myStack) {
                s.action = Action.ALL_IN;
                s.amount = myStack;
            }
        }

        return s;
    }

    // 辅助：计算注码大小 (百分比)
    private static double calculateSizing(double pot, double ratio, double stack) {
        double target = pot * ratio;
        if (target >= stack) return stack;
        return target;
    }

    // 辅助：计算注码大小 (直接目标值)
    private static double calculateSizing(double placeholder, double p2, double stack, double target) {
        if (target >= stack) return stack;
        return target;
    }

    private static String getSprDescription(double spr) {
        if (spr > 10) return "Deep Stack";
        if (spr > 5) return "Medium Stack";
        if (spr > 2) return "Shallow Stack";
        return "Commited/All-in Mode";
    }

    // 检查牌面是否“湿润”（容易有同花/顺子）
    private static boolean checkBoardTexture(List<Card> board) {
        if (board.size() < 3) return false;
        // 检查是否有3张同花色
        int[] suits = new int[4];
        for (Card c : board) suits[c.getSuit().ordinal()]++;
        for (int s : suits) if (s >= 3) return true; // 有同花面

        // 检查连接性 (比如 7,8,9)
        // 简单处理：如果牌面有3张牌跨度在5以内
        // 这里使用简单的排序判断
        List<Integer> ranks = new ArrayList<>();
        for (Card c : board) ranks.add(c.getRank().getValue());
        Collections.sort(ranks);

        // 去重
        List<Integer> uniqueRanks = new ArrayList<>();
        if (!ranks.isEmpty()) {
            uniqueRanks.add(ranks.get(0));
            for (int i = 1; i < ranks.size(); i++) {
                if (!ranks.get(i).equals(ranks.get(i - 1))) uniqueRanks.add(ranks.get(i));
            }
        }

        // 检查3张连续
        for (int i = 0; i <= uniqueRanks.size() - 3; i++) {
            if (uniqueRanks.get(i + 2) - uniqueRanks.get(i) <= 4) return true;
        }

        return false;
    }

    private static double calculateImpliedOddsScale(boolean isSetMining, boolean isFlushDraw, boolean isStraightDraw, int boardSize) {
        if (boardSize >= 5) return 0.0;
        double scale = 0.1;
        if (isSetMining) scale = 0.9;
        else if (isFlushDraw && isStraightDraw) scale = 1.0;
        else if (isFlushDraw) scale = 0.8;
        else if (isStraightDraw) scale = 0.7;
        if (boardSize == 4) scale *= 0.5;
        return scale;
    }

    // =========================================================================
    //  高性能计算核心区 (High Performance Core)
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
                for (int c : cards) {
                    if (c / 13 == flushSuit && c % 13 == r) {
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
                    else {
                        k2 = r + 2;
                        break;
                    }
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
            int k1 = -1, k2 = -1, k3 = -1;
            for (int r = 12; r >= 0; r--) {
                if (r != pair1 && rankCounts[r] > 0) {
                    if (k1 == -1) k1 = r + 2;
                    else if (k2 == -1) k2 = r + 2;
                    else {
                        k3 = r + 2;
                        break;
                    }
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
    private static int getStraightHighRank(int rankBitMask) {
        // 检查 A-5 (Wheel): 需要 A(12), 2(0), 3(1), 4(2), 5(3)
        // mask & 0b1000000001111
        // 0x100F = 1 0000 0000 1111 (A, 5, 4, 3, 2)
        // 注意：如果同时有 6,5,4,3,2，应该返回 6。
        // 但普通顺子逻辑涵盖了 6-2。唯一漏掉的是 A-5 作为最小顺子。
        // 我们先检查普通顺子，如果没有，再返回5。

        // 连续5个1
        // 从 A (12) 开始往下直到 6 (4)
        for (int i = 12; i >= 4; i--) {
            // 检查 i, i-1, i-2, i-3, i-4 是否都存在
            int mask = (1 << i) | (1 << (i - 1)) | (1 << (i - 2)) | (1 << (i - 3)) | (1 << (i - 4));
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
        for (Card c : board) {
            if (!ranks.add(c.getRank().getValue())) return true;
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
        for (Card c : board) {
            if (c.getRank().getValue() == rank) boardCount++;
        }
        return boardCount == 0;
    }
}