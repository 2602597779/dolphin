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
        System.out.println(lengthOfLongestSubstring("bbbbb")); // 1
        System.out.println(lengthOfLongestSubstring("pwwkew")); // 3
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
             *   → c 上次出现的下标 >= 当前窗口左边界 left
             *   说明那个重复字符"还躺在窗口里面"，必须把窗口左边界移过去
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

}
