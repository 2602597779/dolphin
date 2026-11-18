package com.dolphin.test;

import java.util.*;

public class LeetcodeTest {

    public static void main(String[] args) {
        // 测试两数之和
        int[] nums1 = {2, 7, 11, 15};
        int[] res1 = twoSum(nums1, 9);
        System.out.println(Arrays.toString(res1)); // [0, 1]

        // 测试字母异位词分组
        String[] strs = {"eat", "tea", "tan", "ate", "nat", "bat"};
        List<List<String>> res2 = groupAnagrams(strs);
        System.out.println(res2);

        // 测试最长连续序列
        int[] nums3 = {100, 4, 200, 1, 3, 2};
        int res3 = longestConsecutive(nums3);
        System.out.println(res3); // 4

        // 测试移动零
        int[] nums4 = {0, 1, 0, 3, 12};
        moveZeroes(nums4);
        System.out.println(Arrays.toString(nums4)); // [1, 3, 12, 0, 0]

        // 测试盛最多水的容器
        int[] height1 = {1, 8, 6, 2, 5, 4, 8, 3, 7};
        int res4 = maxArea(height1);
        System.out.println(res4); // 49

        int[] height2 = {1, 1};
        int res5 = maxArea(height2);
        System.out.println(res5); // 1

        // 测试三数之和
        int[] nums5 = {-1, 0, 1, 2, -1, -4};
        List<List<Integer>> res6 = threeSum(nums5);
        System.out.println(res6); // [[-1,-1,2],[-1,0,1]]

        int[] nums6 = {0, 1, 1};
        System.out.println(threeSum(nums6)); // []

        int[] nums7 = {0, 0, 0};
        System.out.println(threeSum(nums7)); // [[0,0,0]]

        // 测试无重复字符的最长子串
        System.out.println(lengthOfLongestSubstring("abcabcbb")); // 3

        // 测试最大子数组和
        int[] nums8 = {-2, 1, -3, 4, -1, 2, 1, -5, 4};
        System.out.println(maxSubArray(nums8)); // 6


        // 测试最大子数组和（返回下标版本）
        int[] nums11 = {-2, 1, -3, 4, -1, 2, 1, -5, 4};
        System.out.println(Arrays.toString(maxSubArrayWithIndex(nums11))); // {6, 3, 6}

        // 测试轮转数组
        int[] nums14 = {1, 2, 3, 4, 5, 6, 7};
        rotate(nums14, 3);
        System.out.println(Arrays.toString(nums14)); // [5,6,7,1,2,3,4]

        int[] nums15 = {-1, -100, 3, 99};
        rotate(nums15, 2);
        System.out.println(Arrays.toString(nums15)); // [3,99,-1,-100]

        int[] nums16 = {1, 2, 3};
        rotate(nums16, 4);
        System.out.println(Arrays.toString(nums16)); // [2,3,1]（k=4，n=3，实际轮转1位）

        // 测试除自身以外数组的乘积
        int[] nums17 = {1, 2, 3, 4};
        System.out.println(Arrays.toString(productExceptSelf(nums17))); // [24,12,8,6]

//        int[] nums18 = {-1, 1, 0, -3, 3};
//        System.out.println(Arrays.toString(productExceptSelf(nums18))); // [0,0,9,0,0]

        // 测试除自身以外数组的乘积（哈希版本）
        System.out.println(Arrays.toString(productExceptSelfWithHash(nums17))); // [24,12,8,6]

        // 测试设矩阵为零
        int[][] matrix1 = {{1, 1, 1}, {1, 0, 1}, {1, 1, 1}};
        setZeroes(matrix1);
        System.out.println(Arrays.deepToString(matrix1)); // [[1,0,1],[0,0,0],[1,0,1]]

        int[][] matrix2 = {{0, 1, 2, 0}, {3, 4, 5, 2}, {1, 3, 1, 5}};
        setZeroes(matrix2);
        System.out.println(Arrays.deepToString(matrix2)); // [[0,0,0,0],[0,4,5,0],[0,3,1,0]]

        // 测试螺旋矩阵
        int[][] matrix3 = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}};
        System.out.println(spiralOrder(matrix3)); // [1,2,3,6,9,8,7,4,5]

        int[][] matrix4 = {{1, 2, 3, 4}, {5, 6, 7, 8}, {9, 10, 11, 12}};
        System.out.println(spiralOrder(matrix4)); // [1,2,3,4,8,12,11,10,9,5,6,7]

        int[][] matrix5 = {{1, 2}, {3, 4}};
        System.out.println(spiralOrder(matrix5)); // [1,2,4,3]

        // 测试相交链表
        // 示例1：交点为值8的节点
        // listA = [4,1,8,4,5], listB = [5,6,1,8,4,5], 从8开始相交
        ListNode shared1 = buildList(new int[]{8, 4, 5});
        ListNode headA1 = buildListPrefix(new int[]{4, 1}, shared1);
        ListNode headB1 = buildListPrefix(new int[]{5, 6, 1}, shared1);
        ListNode res13 = getIntersectionNode(headA1, headB1);
        System.out.println(res13 != null ? "交点: " + res13.val : "null"); // 交点: 8

        // 示例2：交点为值2的节点
        ListNode shared2 = buildList(new int[]{2, 4});
        ListNode headA2 = buildListPrefix(new int[]{1, 9, 1}, shared2);
        ListNode headB2 = buildListPrefix(new int[]{3}, shared2);
        ListNode res23 = getIntersectionNode(headA2, headB2);
        System.out.println(res23 != null ? "交点: " + res23.val : "null"); // 交点: 2

        // 示例3：无交点
        ListNode headA3 = buildList(new int[]{2, 6, 4});
        ListNode headB3 = buildList(new int[]{1, 5});
        ListNode res34 = getIntersectionNode(headA3, headB3);
        System.out.println(res34 != null ? "交点: " + res34.val : "null"); // null

        // 测试 decode
        // 表达式中 @ 和 $ 的运算规则：
        // @ 运算：左右两边各取一个数，结果 = 2*左 + 右 + 3，两边都替换成结果
        // $ 运算：左边的当前累积结果 和 右边的数，结果 = 3*左 + 2*右 + 1

        System.out.println(decode("10@5$3"));    // 先@→2*10+5+3=28 再$→3*28+2*3+1=91

        System.out.println(decodeBrute("2@3"));       // 10
        System.out.println(decodeBrute("2$3"));       // 13
        System.out.println(decodeBrute("1@2$3"));     // 22
        System.out.println(decodeBrute("10@5$3"));    // 91

    }

    /**
     * 两数之和
     * 给定一个整数数组 nums 和一个整数目标值 target，找出数组中和为目标值的那两个整数，返回它们的数组下标。
     */
    public static int[] twoSum(int[] nums, int target) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < nums.length; i++) {
            int complement = target - nums[i];
            if (map.containsKey(complement)) {
                return new int[] { map.get(complement), i };
            }
            map.put(nums[i], i);
        }
        return new int[] {};
    }

    /**
     * 字母异位词分组
     * 将字母异位词组合在一起（字母异位词：重新排列字母后能互相形成的字符串）
     */
    public static List<List<String>> groupAnagrams(String[] strs) {
        Map<String, List<String>> map = new HashMap<>();
        for (String s : strs) {
            char[] chars = s.toCharArray();
            Arrays.sort(chars);
            String key = new String(chars);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 最长连续序列
     * 找出数字连续的最长序列的长度，时间复杂度 O(n)
     */
    public static int longestConsecutive(int[] nums) {
        Set<Integer> set = new HashSet<>();
        for (int num : nums) set.add(num);

        int longest = 0;
        for (int num : set) {
            if (!set.contains(num - 1)) {
                int current = num, length = 1;
                while (set.contains(current + 1)) {
                    current++;
                    length++;
                }
                longest = Math.max(longest, length);
            }
        }
        return longest;
    }

    /**
     * 移动零
     * 将所有 0 移动到数组末尾，保持非零元素的相对顺序，原地操作（不复制数组）
     * 
     * 算法思路：双指针法
     * - slow 指向下一个非零元素应该放置的位置
     * - fast 遍历整个数组，遇到非零元素就放到 slow 位置
     * - 遍历完成后，将 slow 之后的所有位置填充为 0
     * 
     * 时间复杂度：O(n)，遍历两遍数组
     * 空间复杂度：O(1)，原地操作
     *  int[] nums4 = {0, 1, 0, 3, 12};
     */
    public static void moveZeroes(int[] nums) {
        // slow 指向下一个非零元素应放置的位置
        int slow = 0;

        // fast 遍历数组，将非零元素前移
        for (int fast = 0; fast < nums.length; fast++) {
            if (nums[fast] != 0) {
                nums[slow] = nums[fast];
                slow++;
            }
        }

        // 将 slow 之后的位置全部填充为 0
        for (int i = slow; i < nums.length; i++) {
            nums[i] = 0;
        }
    }

    /**
     * 盛最多水的容器
     * 给定一个长度为 n 的整数数组 height。有 n 条垂线，第 i 条线的两个端点是 (i, 0) 和 (i, height[i])。
     * 找出其中的两条线，使得它们与 x 轴共同构成的容器可以容纳最多的水。
     * 返回容器可以储存的最大水量。说明：你不能倾斜容器。
     * 
     * 算法思路：双指针法（向中间收缩）
     * - 左右指针分别从数组两端开始，容器面积 = 底 × 高 = (right - left) × min(height[left], height[right])
     * - 每次移动较短的那根线对应的指针，因为容器的"高"由短板决定，
     *   只有移动短板才可能找到更高的线来增大面积；移动长板不可能增加面积（短板没变或更矮，宽度变小）
     * - 当两指针相遇时结束
     * 
     * 时间复杂度：O(n)，两指针各遍历一次
     * 空间复杂度：O(1)，只用了常数个变量
     * 
     * 示例：[1,8,6,2,5,4,8,3,7] → 49（索引1的8和索引8的7，底=7，高=min(8,7)=7，面积=49）
     */
    public static int maxArea(int[] height) {
        // 左右指针从两端开始
        int left = 0;
        int right = height.length - 1;
        int maxWater = 0;

        // 向中间收缩，直到两指针相遇
        while (left < right) {
            // 容器面积 = 底 × 高（高由短板决定）
            int area = (right - left) * Math.min(height[left], height[right]);
            maxWater = Math.max(maxWater, area);

            // 移动较短边的指针——只有移动短板才可能找到更大的面积
            if (height[left] < height[right]) {
                left++;
            } else {
                right--;
            }
        }

        return maxWater;
    }

    /**
     * 三数之和
     * 给定一个整数数组 nums，找出所有和为 0 且不重复的三元组。
     *
     * 算法思路：排序 + 双指针
     * - 先排序，固定第一个数 nums[i]，用左右指针在 i+1..n-1 中找两数之和等于 -nums[i]
     * - 剪枝：nums[i] > 0 时后面不可能凑出 0，直接结束
     * - 去重：i 跳过与前一个相同的值；找到一组解后 left/right 各自跳过重复值
     *
     * 时间复杂度：O(n²)（排序 O(nlogn) + 外层 n × 双指针 n）
     * 空间复杂度：O(logn)（排序栈），不计结果列表
     *
     * 示例：[-1,0,1,2,-1,-4] → [[-1,-1,2],[-1,0,1]]
     */
    public static List<List<Integer>> threeSum(int[] nums) {
        List<List<Integer>> result = new ArrayList<>();
        Arrays.sort(nums);
        int n = nums.length;

        for (int i = 0; i < n - 2; i++) {
            // 第一个数大于 0，后面全为正数，不可能凑出 0
            if (nums[i] > 0) break;
            // 跳过重复的第一个数，避免出现重复三元组
            if (i > 0 && nums[i] == nums[i - 1]) continue;

            int left = i + 1, right = n - 1;
            while (left < right) {
                int sum = nums[i] + nums[left] + nums[right];
                if (sum == 0) {
                    result.add(Arrays.asList(nums[i], nums[left], nums[right]));
                    // 跳过 left/right 的重复值
                    while (left < right && nums[left] == nums[left + 1]) left++;
                    while (left < right && nums[right] == nums[right - 1]) right--;
                    left++;
                    right--;
                } else if (sum < 0) {
                    left++;  // 和偏小，移动左指针增大
                } else {
                    right--; // 和偏大，移动右指针减小
                }
            }
        }
        return result;
    }

    /**
     * 无重复字符的最长子串（LeetCode 3）
     * 给定一个字符串 s，找出其中不含有重复字符的最长子串的长度。
     *
     * 算法思路：滑动窗口（双指针）+ HashMap记录字符最近出现位置
     * - left 为窗口左边界，right 为窗口右边界（即当前遍历到的字符）
     * - Map 中存储每个字符最近一次出现的下标
     * - 当 right 指向的字符已经在窗口内出现过（Map 中记录的下标 >= left），
     *   将 left 直接跳到重复字符的下一位，而不是逐个移动（这是关键优化）
     * - 每步更新答案：当前窗口长度 = right - left + 1
     *
     * 时间复杂度：O(n)，left 和 right 各只向右移动，总共 O(n)
     * 空间复杂度：O(min(n, |字符集|))，HashMap 最多存储窗口内的字符
     *
     * 示例：
     *   "abcabcbb" → 3（"abc"）
     *   "bbbbb" → 1（"b"）
     *   "pwwkew" → 3（"wke"，注意 "pwke" 是子序列不是子串）
     */
    public static int lengthOfLongestSubstring(String s) {
        // HashMap 记录每个字符最近一次出现的下标
        // 例如处理到 "abca" 的第二个 a 时，Map 中 a→0（a 第一次出现在下标0）
        Map<Character, Integer> charIndex = new HashMap<>();

        // left：滑动窗口的左边界，始终指向"当前无重复子串"的起点
        // 初始时窗口左边界在第 0 位
        int left = 0;

        // maxLen：记录遍历过程中出现过的最长无重复子串长度
        int maxLen = 0;

        // right：滑动窗口的右边界，也是当前正在处理的字符下标
        // 外层 for 循环本质就是 right 从 0 一直走到 s.length()-1，每个位置处理一次
        for (int right = 0; right < s.length(); right++) {

            // 取出当前 right 指向的字符
            char c = s.charAt(right);

            /*
             * 核心判断：字符 c 之前是否出现过，且上次出现的位置还在当前窗口内？
             *
             * 条件1: charIndex.containsKey(c)
             *   → c 之前出现过（Map 里有它的记录）
             *
             * 条件2: charIndex.get(c) >= left
             *   说明那个重复字符"还躺在窗口里面"，必须把窗口左边界移过去
             *   → c 上次出现的下标 >= 当前窗口左边界 left
             *
             * 举个具体例子：s = "abcabcbb"
             *   处理到下标3的 'a' 时：
             *     Map 中 a→0，left=0
             *     a 出现过 ✓，下标0 >= left(0) ✓
             *     → left = 0 + 1 = 1
             *     窗口从 [0,3] 变为 [1,3]，即 "bca"（去掉了重复的 a）
             *
             * 反例：如果上次出现的下标 < left，说明那个重复字符已经"被踢出窗口了"，
             *       不影响当前窗口的无重复性，不需要移动 left
             */
            if (charIndex.containsKey(c) && charIndex.get(c) >= left) {
                // 左边界跳到"上次出现的 c"的下一位
                // 为什么是 +1？因为那个旧 c 占了位置，从它的下一个才开始算新的无重复审区
                left = charIndex.get(c) + 1;
            }

            // 把 c 的最新下标写入 Map（覆盖旧值）
            // 这样下次遇到 c 时，Map 里存的就是"最近一次"出现的位置
            charIndex.put(c, right);

            // 此时 [left, right] 是一个合法的无重复窗口
            // 窗口长度 = right - left + 1
            // 不断更新全局最大值
            maxLen = Math.max(maxLen, right - left + 1);
        }

        return maxLen;
    }

    /**
     * 最大子数组和（LeetCode 53）— Kadane's 算法
     *
     * 【题目】
     * 给你一个整数数组 nums，找出一个具有最大和的"连续子数组"（至少含1个元素），返回其最大和。
     *
     * 【核心思想（一句话）】
     * 遍历到每个位置 i，问自己一个问题：
     *   "以 nums[i] 结尾的最优连续子数组，到底是从 nums[i] 自己开始，还是把前面 i-1 的最优子数组接上来？"
     * 用 curSum 记录"以当前 i 结尾的最优子数组的最大和"，用 maxSum 记录"到目前为止见过的所有最大和"。
     *
     * 【状态转移方程】
     *   以 i 结尾的最大子数组和 = max( 以 i-1 结尾的最大子数组和 + nums[i] ,  nums[i] 自己 )
     *   即：curSum = max(curSum + nums[i], nums[i])
     *
     * 为什么只有这两种选择？
     *   连续子数组必须以 i 结尾，而且必须是"连续"的，所以它要么是：
     *   1) 把 [???..i-1] 这段接上 i   →  前面那段已经是最优的（curSum），加上 nums[i]
     *   2) 从 i 自己开始一段新的       →  nums[i]
     *   不可能存在第三种（不能跳过中间的）。
     *
     * 【手动推演】nums = [-2, 1, -3, 4, -1, 2, 1, -5, 4]
     *
     * 位置 i   nums[i]   接上前面的 curSum+nums[i]   自己开始 nums[i]   取max → curSum   全局maxSum
     *   0      -2        （初始，无前面）             -2           -2          -2         -2
     *   1      +1        -2 + 1 = -1                 +1            +1           1          1
     *   2      -3        +1 + (-3) = -2              -3           -2          -2          1
     *   3      +4        -2 + 4 = 2                  +4            4            4          4
     *   4      -1        +4 + (-1) = 3               -1          +3           3          4
     *   5      +2        +3 + 2 = 5                  +2            5            5          5
     *   6      +1        +5 + 1 = 6                  +1            6            6          6   ← 当前最大值
     *   7      -5        +6 + (-5) = 1               -5            1            1          6
     *   8      +4        +1 + 4 = 5                  +4            5            5          6
     *
     * 最终 maxSum = 6，对应的子数组是 [4, -1, 2, 1]（位置3到6）
     *
     * 【时间复杂度】O(n)  只遍历一次数组
     * 【空间复杂度】O(1)  只用 curSum 和 maxSum 两个变量（不需要 dp 数组，因为 curSum 只依赖前一个值）
     *
     * 【示例】
     *   [-2,1,-3,4,-1,2,1,-5,4] → 6（[4,-1,2,1]）
     *   [1] → 1
     *   [5,4,-1,7,8] → 23（整个数组）
     */
    public static int maxSubArray(int[] nums) {

        // ============================================================
        // 初始化：第一个元素 i=0，"以它结尾的子数组"只能是它自己
        // ============================================================

        // curSum：表示"以当前遍历到的位置 i 结尾的，最大连续子数组和"
        // i=0 时没有前驱，所以 curSum 就等于 nums[0] 本身
        // 例如 nums[0] = -2，则 curSum = -2
        int curSum = nums[0];

        // maxSum：表示"从 0 到当前 i 为止，全局见过的最大子数组和"
        // 初始时只看到了 nums[0]，所以全局最大值也是 nums[0]
        int maxSum = nums[0];

        // ============================================================
        // 主循环：从第二个元素 i=1 开始，逐个处理
        // i 的含义：当前正在处理数组的第 i 个元素（从0开始计数）
        // ============================================================
        for (int i = 1; i < nums.length; i++) {

            // ----------------------------------------------------------
            // 核心一步（状态转移）：
            //
            // 站在位置 i 上，有且只有两种"以 i 结尾"的连续子数组可选：
            //
            // 选项A：curSum + nums[i]
            //   含义：把"以 i-1 结尾的最优子数组"（和为 curSum）
            //         再往右边延伸一步，把 nums[i] 也加进来
            //   举例：i=5 时，curSum=3 代表子数组 [4,-1]（位置3-4），
            //         加上 nums[5]=2，得到 [4,-1,2]，和为 5
            //
            // 选项B：nums[i]（单独一个数）
            //   含义：前面的 curSum 是负数，加上去只会"拖后腿"，
            //         不如从 i 这里干脆重新开始一段新的子数组
            //   举例：i=3 时，curSum=-2，nums[3]=4，
            //         -2 + 4 = 2 < 4，所以选择选项B，curSum 变成 4
            //
            // 两者取最大值 → 就是"以 i 结尾"的最优答案
            // ----------------------------------------------------------
            curSum = Math.max(curSum + nums[i], nums[i]);

            // ----------------------------------------------------------
            // 更新全局最大值：
            //
            // curSum 只是"以 i 结尾"的最大和，但全局最优答案
            // 不一定以最后一个位置结尾，它可能在更早的位置就结束了。
            // 所以每走一步，都要拿 curSum 和之前的 maxSum 比一下，
            // 保留更大的那个。
            //
            // 举例：i=6 时 curSum=6，此时 maxSum 从 5 更新为 6；
            //       i=7 时 curSum 降为 1，但 maxSum 保持 6 不变
            // ----------------------------------------------------------
            maxSum = Math.max(maxSum, curSum);
        }

        // 遍历完所有位置，maxSum 就是全局最大子数组和
        // 例如 [-2,1,-3,4,-1,2,1,-5,4] → 返回 6
        return maxSum;
    }

    /**
     * 最大子数组和（返回下标版本）
     *
     * 【题目变化】
     * 不再只返回最大和，而是返回 [最大和, [起始下标, 结束下标]]。
     *
     * 【思路】
     * 在原始 Kadane 算法基础上加三个指针：
     * - start：当前正在考虑的连续子数组的"起点"下标
     * - bestStart / bestEnd：记录全局最优子数组的起止下标
     *
     * 关键点：
     * 1) 当选择"从 i 重新开始"（即 nums[i] > curSum + nums[i]）时，
     *    说明前面的子数组和是负数，拖后腿了，
     *    所以 start 要跳到 i（新子数组从 i 开始）
     * 2) 当 curSum > maxSum（严格大于，保留最早出现的那组）时，
     *    说明找到了更大和的子数组，更新 bestStart = start, bestEnd = i
     *
     * 返回 int[] 格式：{最大和, 起始下标, 结束下标}
     * 例如 [-2,1,-3,4,-1,2,1,-5,4] → {6, 3, 6}（子数组 [4,-1,2,1]）
     *
     * 【时间复杂度】O(n)
     * 【空间复杂度】O(1)
     *
     * 【手动推演】nums = [-2, 1, -3, 4, -1, 2, 1, -5, 4]
     *
     * i  nums[i]  curSum   start  maxSum  bestStart  bestEnd  说明
     * 0   -2      -2       0      -2      0          0        初始化
     * 1   +1      1        1(重开) 1      1          1        1>-2,重新开始
     * 2   -3      -2       1      1      1          1        接上
     * 3   +4      4        3(重开) 4      3          3        4>-2,重新开始
     * 4   -1      3        3      4      3          3        接上
     * 5   +2      5        3      5      3          5        5>4,更新
     * 6   +1      6        3      6      3          6        6>5,更新
     * 7   -5      1        3      6      3          6        接上,没变大
     * 8   +4      5        3      6      3          6        接上,没变大
     *
     * 最终：maxSum=6, bestStart=3, bestEnd=6 → 子数组 [4,-1,2,1]
     */
    public static int[] maxSubArrayWithIndex(int[] nums) {

        // ============================================================
        // 初始化：第一个元素 i=0
        // ============================================================

        // curSum：以当前位置结尾的最大子数组和
        int curSum = nums[0];

        // maxSum：全局见过的最大子数组和
        int maxSum = nums[0];

        // start：当前正在考虑的连续子数组的"起点"下标
        // 初始为 0，表示当前子数组从第一个元素开始
        int start = 0;

        // bestStart / bestEnd：记录全局最优子数组的起止下标
        // 初始也指向第一个元素
        int bestStart = 0;
        int bestEnd = 0;

        for (int i = 1; i < nums.length; i++) {

            // ----------------------------------------------------------
            // 关键：在更新 curSum 之前，先判断要不要"从 i 重新开始"
            //
            // nums[i] > curSum + nums[i] 等价于 curSum < 0：
            //   前面的子数组和是负数，加上去只会拖后腿，
            //   此时 start 必须跳到 i，因为新子数组从 i 开始
            //
            // 注意边界：curSum == 0 时两种选择结果相同，
            // 不重开（保持 start 不动），保证返回"最早出现"的下标。
            // 如果用更新后的 curSum == nums[i] 来判断，
            // curSum 为 0 时会误判成重开，导致下标偏右。
            // ----------------------------------------------------------
            if (curSum < 0) {
                start = i;
            }

            // ----------------------------------------------------------
            // 状态转移（和原版一样）：
            // 比较"接上前面的子数组" vs "从 i 自己重新开始"
            // ----------------------------------------------------------
            curSum = Math.max(curSum + nums[i], nums[i]);

            // ----------------------------------------------------------
            // 如果当前 curSum 超过了历史最大 maxSum，
            // 说明从 start 到 i 的这段子数组是更好的答案，
            // 更新全局最优的起止下标
            //
            // 用严格大于 >，这样如果有多个相同最大和，保留最早出现的
            // ----------------------------------------------------------
            if (curSum > maxSum) {
                maxSum = curSum;
                bestStart = start;  // 起点 = 当前子数组的起点
                bestEnd = i;        // 终点 = 当前位置 i
            }
        }

        // 返回 {最大和, 起始下标, 结束下标}
        // 例如 [-2,1,-3,4,-1,2,1,-5,4] → {6, 3, 6}
        return new int[]{maxSum, bestStart, bestEnd};
    }

    /**
     * 轮转数组（LeetCode 189）
     *
     * 【题目】
     * 给定一个整数数组 nums，将数组中的元素向右轮转 k 个位置。必须原地修改，不许返回新数组。
     *
     * 【什么是"向右轮转 k 个位置"】
     * 把数组末尾的 k 个元素"搬"到最前面，前面剩下的元素整体右移 k 位。
     * 例如 [1,2,3,4,5,6,7]，k=3：
     *   末尾3个 [5,6,7] 搬到前面
     *   前面4个 [1,2,3,4] 整体右移到后面
     *   结果：[5,6,7,1,2,3,4]
     *
     * 【算法：三次数组翻转法】— 空间 O(1)
     *
     * 核心观察：向右轮转 k 位 等价于：
     *   第一步：翻转整个数组
     *   第二步：翻转前 k 个元素
     *   第三步：翻转后 n-k 个元素
     *
     * 用 [1,2,3,4,5,6,7]，k=3 走一遍：
     *
     *   原始：       [1, 2, 3, | 4, 5, 6, 7]
     *                 前 n-k 段   后 k 段
     *
     *   第①步：翻转整个数组
     *           [7, 6, 5, 4, 3, 2, 1]
     *
     *   第②步：翻转前 k 个（前3个）
     *           [5, 6, 7, 4, 3, 2, 1]
     *
     *   第③步：翻转后 n-k 个（后4个）
     *           [5, 6, 7, 1, 2, 3, 4]   ← 正是我们要的结果！
     *
     * 为什么这三步能工作？
     *   原始数组可拆成两段：A = [1,2,3,4]（前 n-k 个），B = [5,6,7]（后 k 个）
     *   目标：从 AB → BA
     *
     *   第①步翻转整个：reverse(AB) = reverse(B) × reverse(A)
     *       即 [7,6,5] × [4,3,2,1]
     *   第②步翻转前k个：reverse(reverse(B)) = B，后 n-k 个不变
     *       即 [5,6,7] × [4,3,2,1]
     *   第③步翻转后 n-k 个：前k个不变，reverse(reverse(A)) = A
     *       即 [5,6,7] × [1,2,3,4]  = BA ✓
     *
     * 【关于 k % n】
     * 轮转 n 个位置等于没轮转（转一圈回到原位）。
     * 所以 k = 4, n = 3 时，实际只需要轮转 4 % 3 = 1 位。
     *
     * 【时间复杂度】O(n)  三次翻转，每次 O(n/2)，总共 O(n)
     * 【空间复杂度】O(1)  只用一个临时变量做交换
     *
     * 【示例】
     *   [1,2,3,4,5,6,7], k=3 → [5,6,7,1,2,3,4]
     *   [-1,-100,3,99], k=2  → [3,99,-1,-100]
     *   [1,2,3], k=4          → [2,3,1]（实际轮转1位）
     */
    public static void rotate(int[] nums, int k) {

        // n：数组长度
        int n = nums.length;

        // ----------------------------------------------------------
        // k 取模：轮转 n 次等于没轮转
        // 例如 n=3, k=4 → 实际只轮转 1 位
        // 同时处理 k=0 的情况（取模后为0，后面三步都是翻转自己，无影响）
        // ----------------------------------------------------------
        k = k % n;

        // 如果取模后 k=0，说明完全不用轮转，直接返回
        // （不返回也可以，后面三次翻转自己等于没变）
        if (k == 0) {
            return;
        }

        // ============================================================
        // 第①步：翻转整个数组
        // 把 [1,2,3,4,5,6,7] 变成 [7,6,5,4,3,2,1]
        // ============================================================
        reverse(nums, 0, n - 1);

        // ============================================================
        // 第②步：翻转前 k 个元素
        // 把 [7,6,5,4,3,2,1] 的前3个翻转
        // → [5,6,7,4,3,2,1]
        // ============================================================
        reverse(nums, 0, k - 1);

        // ============================================================
        // 第③步：翻转后 n-k 个元素
        // 把 [5,6,7,4,3,2,1] 的后4个翻转
        // → [5,6,7,1,2,3,4]  ✓
        // ============================================================
        reverse(nums, k, n - 1);
    }

    /**
     * 辅助方法：原地翻转数组中 [left, right] 区间内的元素
     *
     * 【原理】双指针交换
     * - left 从左边往右走，right 从左边往左走
     * - 每次把 left 和 right 指向的元素交换，然后 left++，right--
     * - 直到 left >= right 时停止（相遇或交叉，说明都翻完了）
     *
     * 例如翻转 [1..5] 的 [2,3,4]：
     *   初始：  1, (2, 3, 4), 5
     *   第1次：交换 2 和 4 → 1, (4, 3, 2), 5
     *   left=3, right=3，相遇，停止
     *
     * 【时间复杂度】O(m)，m = right - left + 1（区间长度）
     * 【空间复杂度】O(1)，只有一个 temp 变量
     */
    private static void reverse(int[] nums, int left, int right) {
        // 双指针：left 从左往右，right 从右往左
        while (left < right) {
            // 交换 nums[left] 和 nums[right]
            int temp = nums[left];
            nums[left] = nums[right];
            nums[right] = temp;

            // 左指针右移，右指针左移
            left++;
            right--;
        }
    }

    /**
     * 除自身以外数组的乘积（LeetCode 238）
     *
     * 【题目】
     * 给你一个整数数组 nums，返回数组 answer，其中 answer[i] 等于 nums 中除 nums[i] 之外
     * 其余各元素的乘积。要求：
     *   1) 不能使用除法
     *   2) 时间复杂度 O(n)
     *
     * 【核心思路：前缀积 × 后缀积】
     *
     * 对于每个位置 i， answer[i] = (i 左边所有元素的乘积) × (i 右边所有元素的乘积)
     *
     * 左边乘积（前缀积）：从前往后扫，prefix[i] = nums[0] * nums[1] * ... * nums[i-1]
     * 右边乘积（后缀积）：从后往前扫，suffix[i] = nums[i+1] * nums[i+2] * ... * nums[n-1]
     *
     * 所以 answer[i] = prefix[i] * suffix[i]
     *
     * 【手动推演】nums = [1, 2, 3, 4]
     *
     * 位置 i    0     1     2     3
     * nums[i]   1     2     3     4
     * 左边乘积  1     1     2     6      ← 从前往后累乘（i=0时左边没元素，积为1）
     * 右边乘积  24    12    4     1      ← 从后往前累乘（i=3时右边没元素，积为1）
     * answer[i] 24    12    8     6
     *
     * 【两个关键优化】
     *
     * 优化1：prefix 数组可以先"借用" answer 数组存储，不用额外开一个数组
     *   第一遍从左到右扫，把前缀积直接写进 answer[i]
     *
     * 优化2：suffix 不需要额外数组，只用一个变量 suffix 从右往左累乘
     *   第二遍从右到左扫，suffix 就是"右边所有元素的乘积"，
     *   直接 answer[i] *= suffix 即可
     *
     * 这样空间复杂度从 O(n) 优化到 O(1)（不算输出数组 answer）
     *
     * 【手动推演】nums = [1, 2, 3, 4]
     *
     * 第一遍（从左到右，算前缀积）：
     *   i=0: prefix=1,        answer[0] = 1
     *   i=1: prefix=1*1=1,    answer[1] = 1
     *   i=2: prefix=1*1*2=2,  answer[2] = 2
     *   i=3: prefix=1*1*2*3=6, answer[3] = 6
     *   → answer = [1, 1, 2, 6]
     *
     * 第二遍（从右到左，用 suffix 变量累乘）：
     *   i=3: suffix=1,        answer[3] = 6 * 1 = 6
     *   i=2: suffix=1*4=4,    answer[2] = 2 * 4 = 8
     *   i=1: suffix=4*3=12,   answer[1] = 1 * 12 = 12
     *   i=0: suffix=12*2=24,  answer[0] = 1 * 24 = 24
     *   → answer = [24, 12, 8, 6] ✓
     *
     * 【为什么不能用除法？】
     * 题目明确禁止。而且数组里可能有 0，一旦有 0，
     * "总积 / nums[i]" 就会除以 0 导致错误，所以除法本身就不靠谱。
     * 前缀积 × 后缀积天然避开了 0 的问题。
     *
     * 【时间复杂度】O(n)  两遍遍历
     * 【空间复杂度】O(1)  只用一个 suffix 变量（不算 answer 数组）
     *
     * 【示例】
     *   [1, 2, 3, 4]     → [24, 12, 8, 6]
     *   [-1, 1, 0, -3, 3] → [0, 0, 9, 0, 0]
     */
    public static int[] productExceptSelf(int[] nums) {
        int n = nums.length;
        // answer：最终结果数组
        // 第一遍用它临时存"前缀积"，第二遍再乘上"后缀积"得到最终答案
        int[] answer = new int[n];

        // ============================================================
        // 第一遍：从左到右，计算每个位置的"前缀积"（左边所有元素的乘积）
        //
        // 前缀积的定义：nums[0] * nums[1] * ... * nums[i-1]
        // 注意：i=0 时左边没有元素，约定积为 1（乘法单位元）
        //
        // 为什么用 1 开头？因为乘法里 1 是"什么都不加"的意思，
        // 类似加法的单位元是 0
        // ============================================================

        // prefix：当前前缀积，初始为 1（还没乘过任何元素）
        int prefix = 1;

        for (int i = 0; i < n; i++) {
            // 先把当前的前缀积存进 answer[i]
            // 此刻 prefix = nums[0]*nums[1]*...*nums[i-1]（i左边的所有元素之积）
            answer[i] = prefix;

            // 然后更新 prefix：把 nums[i] 乘进去
            // 这样下次循环时，prefix 就变成了 nums[0]*...*nums[i]
            prefix *= nums[i];

            System.out.println("第一遍 i=" + i + "  nums[i]=" + nums[i]
                    + "  answer=" + Arrays.toString(answer)
                    + "  prefix=" + prefix);
        }
        // 第一遍结束后：answer[i] = 左边所有元素的乘积
        // 例如 [1,2,3,4] → answer = [1, 1, 2, 6]

        // ============================================================
        // 第二遍：从右到左，计算每个位置的"后缀积"（右边所有元素的乘积）
        // 直接把后缀积乘进 answer[i]
        //
        // 后缀积的定义：nums[i+1] * nums[i+2] * ... * nums[n-1]
        // 注意：最后一个元素 i=n-1 时右边没有元素，约定积为 1
        //
        // 这里不需要额外的数组，只需要一个变量 suffix 从右往左累乘
        // 因为 answer[i] 已经存了前缀积，乘上 suffix 就是最终答案
        // ============================================================

        // suffix：当前后缀积，初始为 1（最右边没有元素）
        int suffix = 1;

        for (int i = n - 1; i >= 0; i--) {
            // 把后缀积乘进 answer[i]
            // answer[i] 原来是前缀积，现在乘上 suffix（后缀积）
            // 结果 = 左边所有元素 × 右边所有元素 = 除自己以外的乘积
            answer[i] *= suffix;

            // 更新 suffix：把 nums[i] 乘进去
            // 这样下次循环（i-1）时，suffix 就是 nums[i]*nums[i+1]*...*nums[n-1]
            suffix *= nums[i];

            System.out.println("第二遍 i=" + i + "  nums[i]=" + nums[i]
                    + "  answer=" + Arrays.toString(answer)
                    + "  suffix=" + suffix);
        }
        // 第二遍结束后：answer[i] = 左边乘积 × 右边乘积 = 除 nums[i] 外所有元素的乘积
        // 例如 [1,2,3,4] → answer = [24, 12, 8, 6]

        return answer;
    }

    public static int[] productExceptSelfWithHash(int[] nums) {
        int n = nums.length;
        Map<Integer, Integer> prefixMap = new HashMap<>();

        int prefix = 1;
        for (int i = 0; i < n; i++) {
            prefixMap.put(i, prefix);
            prefix *= nums[i];
        }

        int[] answer = new int[n];
        int suffix = 1;
        for (int i = n - 1; i >= 0; i--) {
            answer[i] = prefixMap.get(i) * suffix;
            suffix *= nums[i];
        }

        return answer;
    }

    /**
     * 设矩阵为零（LeetCode 73）
     *
     * 【题目】
     * 给定一个 m x n 的矩阵，如果一个元素为 0，则将其所在行和列的所有元素都设为 0。要求原地算法。
     *
     * 【为什么不能直接改成 0？】
     * 如果把 matrix[i][j] 改成 0，那么后续处理矩阵时，
     * 无法区分"这个 0 是原来就有的"还是"被我改掉的"，
     * 导致本该保留的非零元素被误杀。
     *
     * 【核心思路：用第一行和第一列当"标记区"】
     *
     * 空间要求是 O(1)，不能开额外的 boolean[m][n] 记录哪些行/列该置零。
     * 但我们可以**借用矩阵的第一行和第一列**来存储行/列的标记：
     * - matrix[0][j] == 0 → 说明第 j 列需要置零
     * - matrix[i][0] == 0 → 说明第 i 行需要置零
     *
     * 但第一行和第一列自己本身呢？用一个 `firstRowZero` 和 `firstColZero`
     * 来记录它们原本是否包含 0。
     *
     * 【执行步骤】
     *
     * 第①步：记录第一行、第一列原来是否有 0
     *   firstRowZero = 第一行是否有0
     *   firstColZero = 第一列是否有0
     *
     * 第②步：遍历矩阵（跳过第一行第一列），发现 0 就标记
     *   matrix[i][j] == 0 时：
     *     matrix[i][0] = 0   ← 标记"第 i 行要置零"
     *     matrix[0][j] = 0   ← 标记"第 j 列要置零"
     *
     * 第③步：根据标记，把矩阵内部（不含第一行第一列）置零
     *   for i 从 1 到 m-1：
     *     for j 从 1 到 n-1：
     *       if matrix[i][0] == 0 || matrix[0][j] == 0:
     *         matrix[i][j] = 0
     *
     * 第④步：处理第一列（根据 firstColZero）
     *   if firstColZero: 第一列全部置 0
     *
     * 第⑤步：处理第一行（根据 firstRowZero）
     *   if firstRowZero: 第一行全部置 0
     *
     * 第④⑤步必须在第③步之后，因为第一行第一列的标记值在第③步还要用，
     * 如果提前改了就会丢失信息。
     *
     * 【手动推演】
     * 矩阵 = {{0, 1, 2, 0}, {3, 4, 5, 2}, {1, 3, 1, 5}}
     *
     * 第①步：
     *   第一行 = [0,1,2,0]，有0 → firstRowZero = true
     *   第一列 = [0,3,1]，有0 → firstColZero = true
     *
     * 第②步：遍历 i=1..2, j=1..3
     *   没有发现 0，标记区不变：
     *   第一行标记 = [0, 1, 2, 0]  （第0列、第3列要置零）
     *   第一列标记 = [0, 3, 1]     （第0行要置零——但第0行已在firstRowZero里记录）
     *
     * 第③步：根据标记置零内部
     *   matrix[1][j]: matrix[1][0]=3≠0, 但 matrix[0][j] 中 j=3 时为 0 → matrix[1][3]=0
     *   matrix[2][j]: matrix[2][0]=1≠0, 但 matrix[0][j] 中 j=3 时为 0 → matrix[2][3]=0
     *   结果内部：
     *     {3,4,5,0}
     *     {1,3,1,0}
     *
     * 第④步：firstColZero=true，第一列全置 0
     *   column0 = [0, 0, 0]
     *
     * 第⑤步：firstRowZero=true，第一行全置 0
     *   row0 = [0, 0, 0, 0]
     *
     * 最终：
     *   {{0,0,0,0}, {0,4,5,0}, {0,3,1,0}} ✓
     *
     * 【时间复杂度】O(m × n)  遍历矩阵常数次
     * 【空间复杂度】O(1)  只用 firstRowZero/firstColZero 两个布尔变量
     *
     * 【示例】
     *   [[1,1,1],[1,0,1],[1,1,1]] → [[1,0,1],[0,0,0],[1,0,1]]
     *   [[0,1,2,0],[3,4,5,2],[1,3,1,5]] → [[0,0,0,0],[0,4,5,0],[0,3,1,0]]
     */
    public static void setZeroes(int[][] matrix) {
        if (matrix == null || matrix.length == 0 || matrix[0].length == 0) {
            return;
        }

        int m = matrix.length;
        int n = matrix[0].length;

        // ============================================================
        // 第①步：检查第一行和第一列是否原本包含 0
        // 这两个标记必须在任何修改之前记录，因为后面我们会用第一行第一列做标记区
        // ============================================================

        // firstRowZero：第一行是否原本就有 0
        // 如果第一行有 0，最终整行都要置 0
        boolean firstRowZero = false;
        for (int j = 0; j < n; j++) {
            if (matrix[0][j] == 0) {
                firstRowZero = true;
                break;
            }
        }

        // firstColZero：第一列是否原本就有 0
        // 如果第一列有 0，最终整列都要置 0
        boolean firstColZero = false;
        for (int i = 0; i < m; i++) {
            if (matrix[i][0] == 0) {
                firstColZero = true;
                break;
            }
        }

        // ============================================================
        // 第②步：遍历矩阵内部（跳过第一行第一列），
        // 发现 0 就在对应的行标记（matrix[i][0]）和列标记（matrix[0][j]）位置打标记
        // ============================================================
        for (int i = 1; i < m; i++) {
            for (int j = 1; j < n; j++) {
                if (matrix[i][j] == 0) {
                    // matrix[i][0] = 0 表示"第 i 行需要置零"
                    matrix[i][0] = 0;
                    // matrix[0][j] = 0 表示"第 j 列需要置零"
                    matrix[0][j] = 0;
                }
            }
        }

        // ============================================================
        // 第③步：根据标记，把矩阵内部（不含第一行第一列）的元素置零
        //
        // 注意：这里必须先于第④⑤步，因为第一行第一列的标记值
        // 在第④⑤步之前还要用来决定哪些内部元素该置零
        // ============================================================
        for (int i = 1; i < m; i++) {
            for (int j = 1; j < n; j++) {
                // 如果第 i 行标记为 0（matrix[i][0]==0）
                // 或者第 j 列标记为 0（matrix[0][j]==0）
                // 则 matrix[i][j] 必须置为 0
                if (matrix[i][0] == 0 || matrix[0][j] == 0) {
                    matrix[i][j] = 0;
                }
            }
        }

        // ============================================================
        // 第④步：处理第一列
        // 如果第一列原本就有 0，整列都要置 0
        // 包括 matrix[0][0]（它同时属于第一行第一列，但在这里统一处理）
        // ============================================================
        if (firstColZero) {
            for (int i = 0; i < m; i++) {
                matrix[i][0] = 0;
            }
        }

        // ============================================================
        // 第⑤步：处理第一行
        // 如果第一行原本就有 0，整行都要置 0
        // 注意：第⑤步必须在第④步之后，
        // 否则第④步会把第一列的标记改掉，影响第⑤步对第一行其他元素的处理
        // （实际上第一行第一列只有一个元素，但保持这个顺序更安全）
        // ============================================================
        if (firstRowZero) {
            for (int j = 0; j < n; j++) {
                matrix[0][j] = 0;
            }
        }
    }

    /**
     * 螺旋矩阵（LeetCode 54）
     *
     * 【题目】
     * 给你一个 m 行 n 列的矩阵，按照顺时针螺旋顺序返回矩阵中的所有元素。
     *
     * 【什么是顺时针螺旋】
     * 从左上角开始，依次走：→ 右、↓ 下、← 左、↑ 上，走完一圈后收缩边界，继续走内圈。
     *
     * 以 [[1,2,3],[4,5,6],[7,8,9]] 为例：
     *   → 右走第一行：  1, 2, 3
     *   ↓ 下走最后一列：6, 9
     *   ← 左走最后一行：8, 7
     *   ↑ 上走第一列：  4
     *   → 右走中间剩余：5
     *   结果：[1, 2, 3, 6, 9, 8, 7, 4, 5]
     *
     * 【核心思路：四边界收缩法】
     *
     * 维护四个边界：top, bottom, left, right
     * 每走完一个方向，对应的边界就收缩一格：
     *   向右 → 走完后 top++（第一行已经走过了）
     *   向下 → 走完后 right--（最后一列已经走过了）
     *   向左 → 走完后 bottom--（最后一行已经走过了）
     *   向上 → 走完后 left++（第一列已经走过了）
     *
     * 循环条件：top <= bottom && left <= right
     * 每次走完一个方向后，检查边界是否已交叉，交叉了就跳出
     *
     * 【手动推演】[[1,2,3],[4,5,6],[7,8,9]]
     *
     * 初始：top=0, bottom=2, left=0, right=2
     *
     * ① 向右遍历第 top 行（row=0），从 left 到 right：
     *    访问 matrix[0][0]=1, matrix[0][1]=2, matrix[0][2]=3
     *    结果 = [1, 2, 3]
     *    top++ → top=1
     *
     * ② 向下遍历第 right 列（col=2），从 top 到 bottom：
     *    访问 matrix[1][2]=6, matrix[2][2]=9
     *    结果 = [1, 2, 3, 6, 9]
     *    right-- → right=1
     *
     * ③ 向左遍历第 bottom 行（row=2），从 right 到 left：
     *    访问 matrix[2][1]=8, matrix[2][0]=7
     *    结果 = [1, 2, 3, 6, 9, 8, 7]
     *    bottom-- → bottom=1
     *
     * ④ 向上遍历第 left 列（col=0），从 bottom 到 top：
     *    访问 matrix[1][0]=4
     *    结果 = [1, 2, 3, 6, 9, 8, 7, 4]
     *    left++ → left=1
     *
     * 检查：top=1<=bottom=1, left=1<=right=1，继续
     *
     * ⑤ 向右遍历第 top 行（row=1），从 left 到 right：
     *    访问 matrix[1][1]=5
     *    结果 = [1, 2, 3, 6, 9, 8, 7, 4, 5]
     *    top++ → top=2
     *
     * 检查：top=2 > bottom=1，停止
     *
     * 最终：[1, 2, 3, 6, 9, 8, 7, 4, 5] ✓
     *
     * 【为什么要检查边界？】
     * 走完一个方向后，剩下的可能只是一行或一列（甚至一个点）。
     * 如果不检查直接走下一步，会重复访问或越界。
     * 例如走完 top 行后，如果 top > bottom，说明只剩一行已经走完了，
     * 再走 down 方向就会越界，必须在每步检查。
     *
     * 【一个关键细节】
     * 在"向左"和"向上"的步骤里，遍历范围是 right 到 left（而不是 left 到 right），
     * 以及 bottom 到 top（而不是 top 到 bottom），方向是反的。
     * 这是因为螺旋是顺时针的，走完右边后开始往回走。
     *
     * 【时间复杂度】O(m × n)  每个元素恰好访问一次
     * 【空间复杂度】O(1)  除了结果数组，只用四个边界变量
     *
     * 【示例】
     *   [[1,2,3],[4,5,6],[7,8,9]] → [1,2,3,6,9,8,7,4,5]
     *   [[1,2,3,4],[5,6,7,8],[9,10,11,12]] → [1,2,3,4,8,12,11,10,9,5,6,7]
     *   [[1,2],[3,4]] → [1,2,4,3]
     */
    public static List<Integer> spiralOrder(int[][] matrix) {
        List<Integer> result = new ArrayList<>();
        if (matrix == null || matrix.length == 0 || matrix[0].length == 0) {
            return result;
        }

        int m = matrix.length;
        int n = matrix[0].length;

        // top：上边界，初始为第 0 行
        int top = 0;
        // bottom：下边界，初始为最后一行
        int bottom = m - 1;
        // left：左边界，初始为第 0 列
        int left = 0;
        // right：右边界，初始为最后一列
        int right = n - 1;

        // 当四个边界没有交叉时，还有未访问的区域，继续走
        while (top <= bottom && left <= right) {

            for (int col = left; col <= right; col++) {
                result.add(matrix[top][col]);
            }
            // 走完了 top 行，上边界下移一格
            top++;

            if (top > bottom) break;

            for (int row = top; row <= bottom; row++) {
                result.add(matrix[row][right]);
            }
            // 走完了 right 列，右边界左移一格
            right--;

            // 检查：如果 left 超过了 right，说明只剩一列已经走完了
            if (left > right) break;

            for (int col = right; col >= left; col--) {
                result.add(matrix[bottom][col]);
            }
            // 走完了 bottom 行，下边界上移一格
            bottom--;

            // 检查：如果 top 超过了 bottom
            if (top > bottom) break;

            for (int row = bottom; row >= top; row--) {
                result.add(matrix[row][left]);
            }
            // 走完了 left 列，左边界右移一格
            left++;
        }

        return result;
    }

    /**
     * 链表节点定义
     */
    static class ListNode {
        int val;
        ListNode next;

        ListNode(int val) {
            this.val = val;
            this.next = null;
        }
    }

    /**
     * 辅助方法：根据 int[] 构建链表，返回头节点
     */
    private static ListNode buildList(int[] vals) {
        ListNode dummy = new ListNode(0);
        ListNode cur = dummy;
        for (int v : vals) {
            cur.next = new ListNode(v);
            cur = cur.next;
        }
        return dummy.next;
    }

    /**
     * 辅助方法：构建前缀链表，末尾接到 shared 节点上
     * 用于模拟两条链表在 shared 处相交
     */
    private static ListNode buildListPrefix(int[] prefix, ListNode shared) {
        ListNode dummy = new ListNode(0);
        ListNode cur = dummy;
        for (int v : prefix) {
            cur.next = new ListNode(v);
            cur = cur.next;
        }
        cur.next = shared; // 结尾接到共享节点
        return dummy.next;
    }

    /**
     * 辅助方法：把链表转成字符串，方便打印
     */
    private static String listToString(ListNode head) {
        List<Integer> vals = new ArrayList<>();
        while (head != null) {
            vals.add(head.val);
            head = head.next;
        }
        return vals.toString();
    }

    /**
     * 相交链表（LeetCode 160）
     *
     * 【题目】
     * 给你两个单链表的头节点 headA 和 headB，找出并返回两个单链表相交的起始节点。
     * 如果不存在，返回 null。
     *
     * 【关键提醒：相交 = 内存中同一个节点，不是值相同】
     * 题目特别强调：相交节点是**在内存中指向同一个位置**的节点，
     * 而不是值相同的节点。
     *
     * 例如 listA = [4,1,8,4,5], listB = [5,6,1,8,4,5]，
     * A 中第二个节点值=1，B 中第三个节点值=1，
     * 但它们**不是同一个节点**（内存地址不同，只是值恰好相同），不算相交。
     * 真正相交的是 A 中第三个节点 和 B 中第四个节点，它们值都是 8，
     * 且 **是同一个对象**（next 指针指向同一块内存），这才是交点。
     *
     * 所以判断相交必须用 **==**（引用比较，判断是否同一对象），
     * 不能用 **equals** 或比较 val 值。
     *
     * 【核心思路：双指针等长对齐法】
     *
     * 问题本质：两条链表在尾部有一段公共部分，前面各有一段"私有前缀"。
     *
     *       A 的私有部分    B 的私有部分
     *          len(a)           len(b)
     *             ↓                 ↓
     *          [A: 4,1]        [B: 5,6,1]
     *              \                 /
     *               \               /
     *                ↓             ↓
     *              公共尾部 [8, 4, 5]
     *
     * 如果 a < b，链表 A 比 B 短，
     * 让 B 先走 (b - a) 步，走到和 A 的"起点"对齐的位置，
     * 然后 A 和 B 同时往前走，第一个相遇的节点就是交点。
     *
     * 【更简洁的写法：双指针接力法】
     *
     * 不需要先求长度！让两个指针各自走到链表尾后"接力"到另一条链表头：
     *   指针 p 从 headA 出发，走完后接到 headB 的起点继续走
     *   指针 q 从 headB 出发，走完后接到 headA 的起点继续走
     *
     * p 走的总步数 = a + b
     * q 走的总步数 = b + a
     *
     * 两条路径长度相等，所以 p 和 q **一定**在同一步到达交点（或同时到 null）。
     *
     * 为什么一定同时到达交点？
     *   设 A 私有长度为 a，B 私有长度为 b，公共尾部长度为 c。
     *   交点位置（从各自头节点算起）：
     *     从 A 头到交点 = a + c  （但 p 在走完 A 后还要走 B 的私有段才到交点）
     *     p 的路径：headA → 走 a 步到 A 尾 → 接力到 headB → 走 b 步到交点
     *     p 到交点总共走了 a + b 步
     *
     *     同理 q 到交点也走了 b + a 步
     *
     *     所以 p 和 q 同时到达交点。
     *
     *   如果没有交点，p 和 q 同时到达 null（走了 a + b 步后都到了另一条链表的尾）。
     *
     * 【手动推演】
     *
     * 示例1：listA = [4,1,8,4,5], listB = [5,6,1,8,4,5]
     * A 私有 = [4,1]（a=2）, B 私有 = [5,6,1]（b=3）, 公共尾部 = [8,4,5]（c=3）
     *
     * p 走 A:  4 → 1 → 8 → 4 → 5 → null  （5步）
     *          接力到 B: 5 → 6 → 1 → 8 ← 交点  （此时 p 总共走了 5+4=9步）
     *
     * q 走 B:  5 → 6 → 1 → 8 → 4 → 5 → null  （6步）
     *          接力到 A: 4 → 1 → 8 ← 交点  （此时 q 总共走了 6+3=9步）
     *
     * p 和 q 都走了 9 步，同时到达值为 8 的交点 ✓
     *
     * 无交点示例：listA = [2,6,4], listB = [1,5]
     * A 长 a=3, B 长 b=2
     *
     * p 走 A:  2 → 6 → 4 → null  （3步）
     *          接力到 B: 1 → 5 → null  （共 5 步）  (a+b=5)
     *
     * q 走 B:  1 → 5 → null  （2步）
     *          接力到 A: 2 → 6 → 4 → null  （共 5 步）  (b+a=5)
     *
     * p 和 q 同时到达 null，返回 null ✓
     *
     * 【时间复杂度】O(a + b)，两个指针各走 a + b 步
     * 【空间复杂度】O(1)，只用两个指针变量
     *
     * 【为什么不用 HashMap？】
     * HashMap 存 A 的所有节点，再遍历 B 找交集，时间 O(a+b)，空间 O(a)。
     * 双指针法空间 O(1)，更优。
     *
     * 【示例】
     *   intersectVal=8, listA=[4,1,8,4,5], listB=[5,6,1,8,4,5] → 交点值8
     *   intersectVal=2, listA=[1,9,1,2,4], listB=[3,2,4]      → 交点值2
     *   intersectVal=0, listA=[2,6,4], listB=[1,5]            → null
     */
    public static ListNode getIntersectionNode(ListNode headA, ListNode headB) {
        // 边界判断：任一链表为空，不可能相交
        if (headA == null || headB == null) {
            return null;
        }

        // p 指针：从 headA 出发，走到 null 后接力到 headB
        ListNode p = headA;
        // q 指针：从 headB 出发，走到 null 后接力到 headA
        ListNode q = headB;

        // p 和 q 不同时为 null 就继续走
        // 为什么是 != null 而不是 == null？
        // 因为 p 走到 null 后会接力到 headB，不会真正变成 null（除非两条链表都为空）
        // 实际上 p 和 q 一定能在某一步同时到达同一个位置（交点或 null）
        // 用 while (p != q) 更简洁
        while (p != q) {
            // p 走到 A 的尾部后，接力到 B 的头
            // 如果 p 是 null（走完了 A），就跳到 headB
            p = (p == null) ? headB : p.next;
            // q 走到 B 的尾部后，接力到 A 的头
            q = (q == null) ? headA : q.next;
        }

        // 此时 p == q，要么是指向交点，要么是同时到了 null（无交点）
        // 直接返回 p（或 q，它们是同一个引用）
        return p;
    }


    /**
     * 表达式解码
     *
     * 【题目】
     * 给定一个字符串 message，包含数字和 @ 或 $ 运算符，
     * 按照以下规则计算表达式的值：
     *
     * 【运算规则】
     *
     * ① @ 运算（优先级高，先算）：
     *    a @ b → 结果 = 2 * a + b + 3
     *    左右两边的数都替换成这个结果
     *    例：5 @ 7 → 2*5+7+3 = 20，变成 [20, 20]
     *
     * ② $ 运算（优先级低，后算）：
     *    a $ b → 结果 = 3 * a + 2 * b + 1
     *    注意：a 是"当前累积的结果"（包含前面所有已算完的），不是单边的数
     *    例：10 $ 3 → 3*10+2*3+1 = 37
     *
     * 多个 $ 时，从左到右依次计算，前一个 $ 的结果作为下一个 $ 的左操作数
     *
     * 【执行顺序】
     * 先算所有 @，再从左到右算所有 $
     *
     * 【手动推演】
     *
     * "1@2$3"：
     *   解析：nums=[1, 2, 3], ops=[@, $]
     *   第①步算 @：1 @ 2 → 2*1+2+3 = 5，nums=[5, 5, 3], ops=[#, $]
     *   第②步算 $：5 $ 3 → 3*5+2*3+1 = 22
     *   结果：22
     *
     * "10@5$3"：
     *   解析：nums=[10, 5, 3], ops=[@, $]
     *   第①步算 @：10 @ 5 → 2*10+5+3 = 28，nums=[28, 28, 3], ops=[#, $]
     *   第②步算 $：28 $ 3 → 3*28+2*3+1 = 91
     *   结果：91
     *
     * 【时间复杂度】O(n)，n 是字符串长度
     * 【空间复杂度】O(n)，存数字和运算符
     */
    public static int decode(String message) {

        // ============================================================
        // 第1步：词法分析（解析）
        // 把字符串拆成"数字列表"和"运算符列表"
        // 例如 "10@5$3" → nums=[10, 5, 3], ops=[@, $]
        //
        // 注意一个数字可能有多位（如 10, 25），
        // 所以遇到 @ 或 $ 时，把 sb 里累积的数字整体解析
        // ============================================================

        List<Long> nums = new ArrayList<>();
        List<Character> ops = new ArrayList<>();

        // StringBuilder 用来累积当前的多位数字
        StringBuilder sb = new StringBuilder();
        for (char c : message.toCharArray()) {
            // 遇到运算符，说明前面的数字结束了
            if (c == '@' || c == '$') {
                nums.add(Long.parseLong(sb.toString()));
                ops.add(c);
                sb.setLength(0); // 清空 sb，准备读下一个数字
            } else {
                // 遇到数字字符，累积起来（支持多位数）
                sb.append(c);
            }
        }
        // 最后一个数字后面没有运算符，循环结束后手动加入
        nums.add(Long.parseLong(sb.toString()));

        // ============================================================
        // 第2步：先算所有 @ 运算
        //
        // @ 的规则：a @ b → res = 2*a + b + 3
        // 左右两边的数都替换成 res
        //
        // 为什么先算 @？因为 @ 是"高优先级"，
        // 而且 @ 是"对称"的（左右都替换），不影响相邻的其他运算
        //
        // 算完后把 @ 标记成 '#'（已处理），避免第3步重复处理
        // ============================================================
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i) == '@') {
                // 左边的数 = nums[i]，右边的数 = nums[i+1]
                long res = 2 * nums.get(i) + nums.get(i + 1) + 3;
                // 左右两边都替换成结果
                nums.set(i, res);
                if (i + 1 < nums.size()) {
                    nums.set(i + 1, res);
                }
                // 标记为 '#'，第3步跳过
                ops.set(i, '#');
            }
        }

        // ============================================================
        // 第3步：从左到右算所有 $ 运算
        //
        // $ 的规则：result $ b → new_result = 3*result + 2*b + 1
        // 注意：result 是"累积值"，不是 nums[i]
        // 第一个 $ 的左操作数是 nums[0]（或 @ 算完后 nums[0] 的值）
        // 后面每个 $ 的左操作数是前一个 $ 的结果
        //
        // 例如 [5, 5, 3], ops=[#, $]：
        //   result = 5（从 nums[0] 开始）
        //   遇到 $：result = 3*5 + 2*3 + 1 = 22
        // ============================================================

        // 初始累积值取第一个数（@ 已经算完了，所以已经是 @ 的结果）
        long result = nums.get(0);

        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i) == '$') {
                // 右操作数是 nums[i+1]
                // 左操作数是 result（累积值）
                result = 3 * result + 2 * nums.get(i + 1) + 1;
            }
        }

        return (int) result;
    }

    public static int decodeBrute(String message) {
        Deque<Long> stack = new ArrayDeque<>();

        StringBuilder sb = new StringBuilder();
        for (char c : message.toCharArray()) {
            if (Character.isDigit(c)) {
                sb.append(c);
            } else {
                stack.push(Long.parseLong(sb.toString()));
                sb.setLength(0);

                long right = stack.pop();
                long left = stack.pop();
                long res;
                if (c == '@') {
                    res = 2 * left + right + 3;
                } else {
                    res = 3 * left + 2 * right + 1;
                }
                stack.push(res);
            }
        }
        stack.push(Long.parseLong(sb.toString()));

        return stack.pop().intValue();
    }

}
