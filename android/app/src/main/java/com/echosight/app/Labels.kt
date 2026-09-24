package com.echosight.app

/** COCO 80 类中文名（按类别 id 排序）、口语别名、真实高度（米）。 */
object Labels {

    val CLASS_CN = arrayOf(
        "人", "自行车", "汽车", "摩托车", "飞机", "公交车", "火车", "卡车", "船",
        "红绿灯", "消防栓", "停车标志", "停车计时器", "长凳", "鸟", "猫", "狗", "马",
        "羊", "牛", "大象", "熊", "斑马", "长颈鹿", "背包", "雨伞", "手提包", "领带",
        "行李箱", "飞盘", "滑雪板", "滑雪板", "球", "风筝", "棒球棒", "棒球手套",
        "滑板", "冲浪板", "网球拍", "瓶子", "酒杯", "杯子", "叉子", "刀", "勺子", "碗",
        "香蕉", "苹果", "三明治", "橙子", "西兰花", "胡萝卜", "热狗", "披萨", "甜甜圈",
        "蛋糕", "椅子", "沙发", "盆栽", "床", "餐桌", "马桶", "电视", "笔记本电脑",
        "鼠标", "遥控器", "键盘", "手机", "微波炉", "烤箱", "烤面包机", "水槽", "冰箱",
        "书", "时钟", "花瓶", "剪刀", "玩具熊", "吹风机", "牙刷"
    )

    /** 中文口语别名 -> 类别 id。 */
    val ALIASES: Map<String, Int> = buildMap {
        put("人", 0); put("行人", 0); put("大人", 0); put("小孩", 0)
        put("自行车", 1); put("单车", 1)
        put("汽车", 2); put("小汽车", 2); put("轿车", 2); put("车子", 2)
        put("摩托车", 3); put("电动车", 3)
        put("飞机", 4)
        put("公交车", 5); put("巴士", 5); put("公共汽车", 5)
        put("火车", 6)
        put("卡车", 7); put("货车", 7)
        put("船", 8)
        put("红绿灯", 9); put("交通灯", 9)
        put("消防栓", 10)
        put("停车标志", 11)
        put("长凳", 13); put("长椅", 13)
        put("鸟", 14); put("小鸟", 14)
        put("猫", 15); put("猫咪", 15)
        put("狗", 16); put("小狗", 16)
        put("马", 17)
        put("羊", 18)
        put("牛", 19)
        put("大象", 20)
        put("熊", 21)
        put("斑马", 22)
        put("长颈鹿", 23)
        put("背包", 24); put("书包", 24)
        put("雨伞", 25); put("伞", 25)
        put("手提包", 26); put("包", 26)
        put("领带", 27)
        put("行李箱", 28); put("箱子", 28)
        put("飞盘", 29)
        put("球", 32)
        put("风筝", 33)
        put("滑板", 36)
        put("网球拍", 38)
        put("瓶子", 39); put("水瓶", 39)
        put("酒杯", 40)
        put("杯子", 41); put("水杯", 41)
        put("叉子", 42)
        put("刀", 43)
        put("勺子", 44)
        put("碗", 45)
        put("香蕉", 46)
        put("苹果", 47)
        put("三明治", 48)
        put("橙子", 49); put("橘子", 49)
        put("西兰花", 50)
        put("胡萝卜", 51)
        put("热狗", 52)
        put("披萨", 53)
        put("甜甜圈", 54)
        put("蛋糕", 55)
        put("椅子", 56)
        put("沙发", 57)
        put("盆栽", 58)
        put("床", 59)
        put("餐桌", 60); put("桌子", 60)
        put("马桶", 61)
        put("电视", 62); put("电视机", 62)
        put("笔记本电脑", 63); put("笔记本", 63); put("电脑", 63)
        put("鼠标", 64)
        put("遥控器", 65)
        put("键盘", 66)
        put("手机", 67); put("电话", 67)
        put("微波炉", 68)
        put("烤箱", 69)
        put("烤面包机", 70)
        put("水槽", 71)
        put("冰箱", 72)
        put("书", 73); put("书本", 73)
        put("时钟", 74); put("钟", 74)
        put("花瓶", 75)
        put("剪刀", 76)
        put("玩具熊", 77); put("小熊", 77); put("泰迪熊", 77)
        put("吹风机", 78)
        put("牙刷", 79)
    }

    /** 真实世界高度（米），用于单目测距；缺失时用默认 0.3。 */
    val REAL_HEIGHT = mapOf(
        0 to 1.7f, 1 to 1.1f, 2 to 1.5f, 3 to 1.2f, 5 to 3.0f, 7 to 3.0f,
        39 to 0.25f, 41 to 0.10f, 67 to 0.15f, 65 to 0.18f, 73 to 0.20f,
        63 to 0.25f, 56 to 0.9f, 24 to 0.5f, 62 to 0.7f, 66 to 0.15f,
        64 to 0.04f, 25 to 0.9f, 15 to 0.25f, 16 to 0.45f
    )
    const val DEFAULT_HEIGHT = 0.3f

    private val FOUND_WORDS = arrayOf("找到了", "找着了")
    private val TARGET_PREFIXES = arrayOf(
        "帮我找一个", "帮我找下", "帮我找", "我要找一个", "我要找下", "我要找",
        "我想找一个", "我想找下", "我想找", "请找一个", "请找下", "请找", "找一个",
        "找下", "找", "换成", "换一个", "换个", "换"
    )

    sealed class Command {
        data object Found : Command()
        data class Target(val classId: Int) : Command()
    }

    /** 从一句话里按最长别名匹配，返回类别 id；匹配不到返回 null。 */
    fun matchTarget(text: String): Int? {
        for (alias in ALIASES.keys.sortedByDescending { it.length }) {
            if (text.contains(alias)) return ALIASES[alias]
        }
        return null
    }

    /** 解析 ASR 文本，返回 Found / Target / null。 */
    fun parseCommand(text: String): Command? {
        if (text.isBlank()) return null
        if (FOUND_WORDS.any { text.contains(it) }) return Command.Found
        for (p in TARGET_PREFIXES.sortedByDescending { it.length }) {
            if (text.contains(p)) {
                val rest = text.replace(p, "", ignoreCase = false)
                val id = matchTarget(rest) ?: matchTarget(text)
                if (id != null) return Command.Target(id)
            }
        }
        return matchTarget(text)?.let { Command.Target(it) }
    }
}
