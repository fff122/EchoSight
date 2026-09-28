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
        "书", "时钟", "花瓶", "剪刀", "玩具熊", "吹风机", "牙刷",
        // ---- YOLO-World 家居扩展词表（id 80-119，顺序与 make_world_onnx.py 的 EXT 一致）----
        "耳机", "药盒", "钥匙", "眼镜", "充电器", "充电宝", "数据线",
        "保温杯", "拖鞋", "纸巾盒", "梳子", "钱包", "手表", "笔",
        "记事本", "水壶", "电饭煲", "电风扇", "药瓶", "毛巾", "肥皂",
        "洗发水", "牙膏", "饭盒", "牛奶", "鸡蛋", "台灯", "闹钟",
        "插线板", "拐杖", "轮椅", "助行器", "收音机", "计算器", "帽子",
        "围巾", "手套", "垃圾桶", "手电筒", "电池"
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
        // ---- 家居扩展（id 80-119）----
        put("耳机", 80); put("蓝牙耳机", 80); put("耳麦", 80)
        put("药盒", 81); put("药箱", 81)
        put("钥匙", 82); put("钥匙串", 82)
        put("眼镜", 83); put("老花镜", 83); put("太阳镜", 83)
        put("充电器", 84); put("充电头", 84)
        put("充电宝", 85); put("移动电源", 85)
        put("数据线", 86); put("充电线", 86)
        put("保温杯", 87); put("保温壶", 87)
        put("拖鞋", 88)
        put("纸巾盒", 89); put("抽纸", 89)
        put("梳子", 90)
        put("钱包", 91)
        put("手表", 92); put("腕表", 92)
        put("笔", 93); put("钢笔", 93); put("圆珠笔", 93)
        put("记事本", 94); put("本子", 94)
        put("水壶", 95); put("烧水壶", 95); put("电水壶", 95)
        put("电饭煲", 96); put("电饭锅", 96)
        put("电风扇", 97); put("风扇", 97)
        put("药瓶", 98); put("药罐", 98)
        put("毛巾", 99); put("浴巾", 99)
        put("肥皂", 100); put("香皂", 100)
        put("洗发水", 101); put("洗发露", 101)
        put("牙膏", 102)
        put("饭盒", 103); put("便当盒", 103)
        put("牛奶", 104); put("牛奶盒", 104)
        put("鸡蛋", 105)
        put("台灯", 106); put("桌灯", 106)
        put("闹钟", 107)
        put("插线板", 108); put("排插", 108)
        put("拐杖", 109); put("拐棍", 109); put("手杖", 109)
        put("轮椅", 110)
        put("助行器", 111)
        put("收音机", 112)
        put("计算器", 113)
        put("帽子", 114)
        put("围巾", 115)
        put("手套", 116)
        put("垃圾桶", 117); put("纸篓", 117)
        put("手电筒", 118)
        put("电池", 119)
    }

    /** 真实世界高度（米），用于单目测距；缺失时用默认 0.3。 */
    val REAL_HEIGHT = mapOf(
        0 to 1.7f, 1 to 1.1f, 2 to 1.5f, 3 to 1.2f, 5 to 3.0f, 7 to 3.0f,
        39 to 0.25f, 41 to 0.10f, 67 to 0.15f, 65 to 0.18f, 73 to 0.20f,
        63 to 0.25f, 56 to 0.9f, 24 to 0.5f, 62 to 0.7f, 66 to 0.15f,
        64 to 0.04f, 25 to 0.9f, 15 to 0.25f, 16 to 0.45f,
        80 to 0.06f, 81 to 0.08f, 82 to 0.03f, 83 to 0.04f, 84 to 0.05f,
        85 to 0.10f, 86 to 0.05f, 87 to 0.18f, 88 to 0.08f, 89 to 0.12f,
        90 to 0.03f, 91 to 0.03f, 92 to 0.03f, 93 to 0.02f, 94 to 0.02f,
        95 to 0.20f, 96 to 0.25f, 97 to 0.40f, 98 to 0.08f, 99 to 0.05f,
        100 to 0.03f, 101 to 0.20f, 102 to 0.04f, 103 to 0.08f, 104 to 0.18f,
        105 to 0.05f, 106 to 0.35f, 107 to 0.10f, 108 to 0.05f, 109 to 0.85f,
        110 to 0.90f, 111 to 0.80f, 112 to 0.15f, 113 to 0.03f, 114 to 0.15f,
        115 to 0.10f, 116 to 0.03f, 117 to 0.40f, 118 to 0.15f, 119 to 0.03f
    )
    const val DEFAULT_HEIGHT = 0.3f

    private val FOUND_WORDS = arrayOf("找到了", "找着了")
    private val TARGET_PREFIXES = arrayOf(
        "帮我找一个", "帮我找下", "帮我找", "帮我寻找", "我要找一个", "我要找下",
        "我要找", "我想找一个", "我想找下", "我想找", "请找一个", "请找下",
        "请找", "找一个", "找下", "找找", "寻找", "找", "换成", "换一个", "换个", "换"
    )

    sealed class Command {
        data object Found : Command()
        data class Target(val classId: Int) : Command()

        /** 词表外的目标：YOLO 不认识，交给识图兜底。 */
        data class FreeTarget(val name: String) : Command()
        data object Help : Command()
    }

    /** 从一句话里按最长别名匹配，返回类别 id；匹配不到返回 null。 */
    fun matchTarget(text: String): Int? {
        for (alias in ALIASES.keys.sortedByDescending { it.length }) {
            if (text.contains(alias)) return ALIASES[alias]
        }
        return null
    }

    // 不是物品名的词：防止把"找一下""找个东西"这类话当成目标
    private val FREE_STOPWORDS = arrayOf(
        "一下", "东西", "帮忙", "谢谢", "没有", "找到", "别", "不", "你", "我", "他", "她", "它")

    /** 剥掉引导词后，剩下的部分像不像一个物品名（耳机/药盒…）。 */
    fun plausibleItemName(rest: String): String? {
        var s = rest.replace(" ", "").trim()   // ASR 可能在字间插空格
        for (lead in arrayOf("一个", "个", "些")) {
            if (s.startsWith(lead)) { s = s.removePrefix(lead).trim(); break }
        }
        if (s.isEmpty() || s.length > 8) return null
        if (FREE_STOPWORDS.any { s.contains(it) }) return null
        // 只收汉字/字母，避免把数字、标点、半截句子当物品
        if (!s.all { it.code in 0x4E00..0x9FFF || it in 'a'..'z' || it in 'A'..'Z' }) return null
        return s
    }

    /** 解析 ASR 文本，返回 Found / Target / FreeTarget / Help / null。 */
    fun parseCommand(text: String): Command? {
        if (text.isBlank()) return null
        // ASR 可能在字间插空格（"寻找 耳机"）：先去掉，防止剥前缀后剩下"寻耳机"
        val t = text.replace(" ", "").trim()
        if (t.isEmpty()) return null
        if (FOUND_WORDS.any { t.contains(it) }) return Command.Found
        // 帮助只认短句：避免"帮助我找杯子"被当成帮助
        val isHelp = t in setOf("帮助", "帮助一下", "使用教程") ||
            (t.length <= 4 && t.contains("帮助")) ||
            (t.length <= 6 && (t.contains("功能") || t.contains("教程") ||
                t.contains("怎么用")))
        if (isHelp) return Command.Help
        for (p in TARGET_PREFIXES.sortedByDescending { it.length }) {
            if (t.contains(p)) {
                val rest = t.replace(p, "", ignoreCase = false)
                (matchTarget(rest) ?: matchTarget(t))?.let { return Command.Target(it) }
                // 不在 COCO 清单里的物品：照样接单，走识图兜底
                plausibleItemName(rest)?.let { return Command.FreeTarget(it) }
            }
        }
        return matchTarget(t)?.let { Command.Target(it) }
    }
}
