package com.carmusic.data.radio

/**
 * 电台静态目录(v6-D-A,用户定稿:国家 → 分类;中国 → 省份)。
 * 全部为编译期常量——分类/省名/国家中文名不随数据变化,"静态分类"的产品承诺。
 */
object RadioCatalog {

    // ---- 静态分类(用户枚举 10 类 + 交通;"音乐"被用户清单移除) ----

    data class RadioCategory(
        val id: String,
        val displayName: String,
        /** tags 字段逗号分隔精确匹配(如 "pop" 不得命中 "synthpop") */
        val matchTags: List<String>,
        /** 台名关键词(覆盖海外中文台;SQLite LIKE 对 ASCII 大小写不敏感) */
        val nameKeywords: List<String> = emptyList()
    )

    val CATEGORIES: List<RadioCategory> = listOf(
        RadioCategory("pop", "流行", listOf("pop", "top 40", "top40")),
        RadioCategory("rock", "摇滚", listOf("rock", "pop rock")),
        RadioCategory("news", "新闻", listOf("news", "information"), listOf("新闻")),
        RadioCategory("classical", "古典", listOf("classical")),
        RadioCategory("jazz", "爵士", listOf("jazz", "smooth jazz")),
        RadioCategory("oldies", "老歌", listOf("oldies", "70s", "80s", "90s")),
        RadioCategory("dance", "舞曲", listOf("dance", "electronic", "house")),
        RadioCategory("finance", "财经", listOf("business", "economics", "finance"), listOf("财经", "经济")),
        RadioCategory("talk", "谈话", listOf("talk")),
        RadioCategory("culture", "文艺", listOf("culture"), listOf("文艺", "戏曲", "评书")),
        RadioCategory("traffic", "交通", listOf("traffic"), listOf("交通"))
    )

    fun category(id: String): RadioCategory? = CATEGORIES.firstOrNull { it.id == id }

    // ---- 省份别名(state 字段邮政罗马音脏数据,2026-09-16 实测 64 变体全覆盖) ----

    /**
     * 省名 → state 别名清单(谓词 = state COLLATE NOCASE IN(别名)
     * ∪ 台名 LIKE 省名 ∪ tag LIKE 省名;大小写变体由 NOCASE 吸收)。
     * 别名 = 省名自身(兼容中文 state 值)+ 全部实测变体(2026-09-16,64 个 state 值):
     * 邮政罗马音(Kiangsu)、拼音(Guangdong)、历史拼写(Amur River=黑龙江)、
     * 城市级脏值(Dalian→辽宁、成都→四川、安丘市→山东)。
     */
    val PROVINCE_ALIASES: Map<String, List<String>> = mapOf(
        "北京" to listOf("北京", "Beijing"),
        "上海" to listOf("上海", "Shanghai", "Shanghai Direct Administered Municipality"),
        "天津" to listOf("天津", "Tientsin"),
        "重庆" to listOf("重庆", "Chungking", "chongqing"),
        "河北" to listOf("河北", "Hopei"),
        "山西" to listOf("山西", "Shansi", "Shanxi"),
        "内蒙古" to listOf("内蒙古", "Inner Mongolia"),
        "辽宁" to listOf("辽宁", "Liaoning", "Dalian"),
        "吉林" to listOf("吉林", "Jilin"),
        "黑龙江" to listOf("黑龙江", "Amur River"),
        "江苏" to listOf("江苏", "Kiangsu", "Jiangsu"),
        "浙江" to listOf("浙江", "Chekiang", "Zhejiang"),
        "安徽" to listOf("安徽", "Anhwei", "Anhui"),
        "福建" to listOf("福建", "Fukien"),
        "江西" to listOf("江西", "Kiangsi"),
        "山东" to listOf("山东", "Shantung", "安丘市"),
        "河南" to listOf("河南", "Honan"),
        "湖北" to listOf("湖北", "Hupei"),
        "湖南" to listOf("湖南", "Hunan", "hun"),
        "广东" to listOf("广东", "Kwangtung", "Guangdong"),
        "广西" to listOf("广西", "Kwangsi"),
        "海南" to listOf("海南", "Hainan"),
        "四川" to listOf("四川", "Szechuan", "Sichuan", "成都"),
        "贵州" to listOf("贵州", "Kweichow"),
        "云南" to listOf("云南", "Yunnan"),
        "西藏" to listOf("西藏", "xizang", "Tibet"),
        "陕西" to listOf("陕西", "Shensi"),
        "甘肃" to listOf("甘肃", "Kansu"),
        "青海" to listOf("青海", "Tsinghai", "Qinghai"),
        "宁夏" to listOf("宁夏", "Ningsia"),
        "新疆" to listOf("新疆", "Sinkiang"),
        "香港" to listOf("香港", "Hong Kong"),
        "澳门" to listOf("澳门", "Macao", "Macau"),
        "台湾" to listOf("台湾", "Taiwan")
    )

    // ---- 国家中文名(硬编码常用国;未映射回退 ISO 码) ----

    val COUNTRY_CN: Map<String, String> = mapOf(
        "CN" to "中国", "US" to "美国", "DE" to "德国", "RU" to "俄罗斯", "FR" to "法国",
        "MX" to "墨西哥", "GB" to "英国", "GR" to "希腊", "AU" to "澳大利亚", "IT" to "意大利",
        "CA" to "加拿大", "BR" to "巴西", "NL" to "荷兰", "ES" to "西班牙", "AR" to "阿根廷",
        "PL" to "波兰", "IN" to "印度", "RO" to "罗马尼亚", "PH" to "菲律宾", "AE" to "阿联酋",
        "TR" to "土耳其", "CO" to "哥伦比亚", "CH" to "瑞士", "ID" to "印度尼西亚", "CL" to "智利",
        "BE" to "比利时", "RS" to "塞尔维亚", "HU" to "匈牙利", "UA" to "乌克兰", "AT" to "奥地利",
        "CZ" to "捷克", "SE" to "瑞典", "NO" to "挪威", "DK" to "丹麦", "FI" to "芬兰",
        "IE" to "爱尔兰", "PT" to "葡萄牙", "JP" to "日本", "KR" to "韩国", "KP" to "朝鲜",
        "TH" to "泰国", "VN" to "越南", "SG" to "新加坡", "MY" to "马来西亚", "NZ" to "新西兰",
        "ZA" to "南非", "EG" to "埃及", "IL" to "以色列", "SA" to "沙特阿拉伯", "HK" to "中国香港",
        "TW" to "中国台湾", "MO" to "中国澳门", "SK" to "斯洛伐克", "SI" to "斯洛文尼亚",
        "HR" to "克罗地亚", "BG" to "保加利亚", "LT" to "立陶宛", "LV" to "拉脱维亚", "EE" to "爱沙尼亚",
        "BY" to "白俄罗斯", "MD" to "摩尔多瓦", "IS" to "冰岛", "LU" to "卢森堡", "MT" to "马耳他",
        "CY" to "塞浦路斯", "GE" to "格鲁吉亚", "AM" to "亚美尼亚", "AZ" to "阿塞拜疆",
        "KZ" to "哈萨克斯坦", "PK" to "巴基斯坦", "BD" to "孟加拉国", "LK" to "斯里兰卡",
        "NP" to "尼泊尔", "KH" to "柬埔寨", "LA" to "老挝", "MM" to "缅甸", "BN" to "文莱",
        "MN" to "蒙古国", "IR" to "伊朗", "IQ" to "伊拉克", "SY" to "叙利亚", "LB" to "黎巴嫩",
        "JO" to "约旦", "KW" to "科威特", "QA" to "卡塔尔", "BH" to "巴林", "OM" to "阿曼",
        "YE" to "也门", "MA" to "摩洛哥", "DZ" to "阿尔及利亚", "TN" to "突尼斯", "LY" to "利比亚",
        "NG" to "尼日利亚", "KE" to "肯尼亚", "ET" to "埃塞俄比亚", "GH" to "加纳", "TZ" to "坦桑尼亚",
        "UG" to "乌干达", "SN" to "塞内加尔", "CI" to "科特迪瓦", "CM" to "喀麦隆",
        "CU" to "古巴", "VE" to "委内瑞拉", "PE" to "秘鲁", "EC" to "厄瓜多尔", "UY" to "乌拉圭",
        "PY" to "巴拉圭", "BO" to "玻利维亚", "DO" to "多米尼加", "CR" to "哥斯达黎加",
        "PA" to "巴拿马", "GT" to "危地马拉", "HN" to "洪都拉斯", "SV" to "萨尔瓦多",
        "NI" to "尼加拉瓜", "PR" to "波多黎各", "JM" to "牙买加", "GL" to "格陵兰",
        "FO" to "法罗群岛", "MC" to "摩纳哥", "AD" to "安道尔", "SM" to "圣马力诺", "VA" to "梵蒂冈"
    )

    fun countryName(code: String): String = COUNTRY_CN[code.uppercase()] ?: code

    /** ISO 2 码 → 国旗 emoji(regional indicator,补充平面码点);非法码返回空串 */
    fun flagEmoji(code: String): String {
        val up = code.uppercase()
        if (up.length != 2 || up.any { it !in 'A'..'Z' }) return ""
        return String(
            intArrayOf(0x1F1E6 + (up[0] - 'A'), 0x1F1E6 + (up[1] - 'A')),
            0, 2
        )
    }

    /** LIKE 模式转义(配 ESCAPE '\';tags 自由文本,防 %/_ 通配注入) */
    fun likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
